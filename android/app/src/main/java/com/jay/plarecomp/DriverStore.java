package com.jay.plarecomp;

import android.content.Context;
import android.net.Uri;
import android.system.Os;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The imported Turnip driver (Mesa's libvulkan_freedreno.so for Android arm64) and the choice between it and the system's
 * Vulkan. The choice is made in the port menu (Graphics > Graphics driver, runtime/ui/menu.mm through android_glue.c)
 * and applies when the app starts (PlaActivity: PLA_VULKAN_DRIVER for android/vulkan_driver.c).
 */
final class DriverStore {
    private static final int MAX_DRIVER_BYTES = 128 * 1024 * 1024;
    static volatile String status = "";  // (the last import's outcome, shown in the menu)
    static volatile boolean busy;

    private DriverStore() {}

    static File driverFile(Context context) {
        return new File(context.getFilesDir(), "libvulkan_freedreno.so");
    }

    static boolean installed(Context context) { return driverFile(context).isFile(); }

    static boolean turnipChosen(Context context) {
        return context.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("turnip", false);
    }

    static void choose(Context context, boolean turnip) {
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putBoolean("turnip", turnip).commit();
    }

    // Copies the picked .zip / .so into the app's files (on a thread of its own); status says how it went.
    static void importFrom(Context context, Uri source) {
        busy = true;
        status = "Importando…";
        new Thread(() -> {
            String error = null;
            File staged = null;
            try {
                staged = File.createTempFile("turnip-", ".tmp", context.getFilesDir());
                try (InputStream stream = new BufferedInputStream(context.getContentResolver().openInputStream(source))) {
                    stream.mark(4);
                    byte[] magic = new byte[4];
                    readHeader(stream, magic);
                    stream.reset();
                    if (magic[0] == 'P' && magic[1] == 'K') copyZipDriver(stream, staged);
                    else copyArm64Library(stream, staged);
                }
                Os.chmod(staged.getPath(), 0500);
                Os.rename(staged.getPath(), driverFile(context).getPath());
                choose(context, true);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : "No se pudo importar el archivo";
            } finally {
                if (staged != null) staged.delete();
            }
            status = error == null ? "Turnip importado y elegido. Reinicia para usarlo." : error;
            busy = false;
        }, "turnip-import").start();
    }

    private static void copyZipDriver(InputStream source, File staged) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(source)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (!entry.isDirectory() && (name.equals("libvulkan_freedreno.so") || name.endsWith("/libvulkan_freedreno.so"))) {
                    copyArm64Library(zip, staged);
                    return;
                }
            }
        }
        throw new IOException("El ZIP no contiene libvulkan_freedreno.so");
    }

    private static void readHeader(InputStream input, byte[] header) throws IOException {
        int offset = 0;
        while (offset < header.length) {
            int count = input.read(header, offset, header.length - offset);
            if (count < 0) throw new IOException("El archivo está incompleto");
            offset += count;
        }
    }

    private static void copyArm64Library(InputStream input, File output) throws IOException {
        byte[] header = new byte[20];
        readHeader(input, header);
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' || header[4] != 2 ||
                header[5] != 1 || header[16] != 3 || header[17] != 0 || (header[18] & 0xff) != 183 || header[19] != 0)
            throw new IOException("Selecciona una biblioteca Vulkan Android ARM64");
        try (FileOutputStream file = new FileOutputStream(output)) {
            file.write(header);
            byte[] buffer = new byte[64 * 1024];
            int size = header.length;
            for (int count; (count = input.read(buffer)) != -1; ) {
                size += count;
                if (size > MAX_DRIVER_BYTES) throw new IOException("El controlador supera 128 MB");
                file.write(buffer, 0, count);
            }
        }
    }
}
