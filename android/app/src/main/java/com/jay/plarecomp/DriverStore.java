package com.jay.plarecomp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.system.Os;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/**
 * An imported Vulkan driver for Adreno and the choice between it and the system's Vulkan. Any AdrenoTools driver pack
 * works: Mesa's Turnip (libvulkan_freedreno.so) or Qualcomm's own newer drivers (libvulkan_adreno.so, ...) - the pack's
 * meta.json names its library ("libraryName"); a bare .so is taken as it is. The files go to files/driver/ under their
 * own names (android/vulkan_driver.c loads the library through AdrenoTools from there). The choice is made in the port
 * menu (Graphics > Graphics driver, runtime/ui/menu.mm through android_glue.c) and applies when the app starts
 * (PlaActivity: PLA_VULKAN_DRIVER / PLA_DRIVER_DIR).
 */
final class DriverStore {
    private static final int MAX_DRIVER_BYTES = 128 * 1024 * 1024;
    private static final String PREFS = "launcher";
    static volatile String status = "";  // (the last import's outcome, shown in the menu)
    static volatile boolean busy;

    private DriverStore() {}

    static File driverDir(Context context) { return new File(context.getFilesDir(), "driver"); }

    // the imported driver's library (files/driver/<its name>); a Turnip imported before packs were read: files/ itself
    static File driverFile(Context context) {
        String lib = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("driver_lib", "");
        if (!lib.isEmpty()) return new File(driverDir(context), lib);
        return new File(context.getFilesDir(), "libvulkan_freedreno.so");
    }

    // "Turnip 25.1 (Mesa)", from the pack's meta.json; the library's name without one
    static String driverName(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String n = p.getString("driver_name", "");
        return !n.isEmpty() ? n : installed(context) ? driverFile(context).getName() : "";
    }

    static boolean installed(Context context) { return driverFile(context).isFile(); }

    static boolean turnipChosen(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("turnip", false);
    }

    static void choose(Context context, boolean imported) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("turnip", imported).commit();
    }

    // Copies the picked .zip / .so into files/driver/ (on a thread of its own); status says how it went.
    static void importFrom(Context context, Uri source) {
        busy = true;
        status = "Importando…";
        new Thread(() -> {
            String error = null, lib = null, name = "";
            File stage = new File(context.getFilesDir(), "driver.new");
            try {
                deleteTree(stage);
                if (!stage.mkdirs()) throw new IOException("No se pudo preparar la carpeta del driver");
                try (InputStream stream = new BufferedInputStream(context.getContentResolver().openInputStream(source))) {
                    stream.mark(4);
                    byte[] magic = new byte[4];
                    readHeader(stream, magic);
                    stream.reset();
                    if (magic[0] == 'P' && magic[1] == 'K') {
                        String[] got = copyZipDriver(stream, stage);
                        lib = got[0];
                        name = got[1];
                    } else {
                        lib = "libvulkan_freedreno.so";  // (a bare library: AdrenoTools needs a name, any works)
                        copyArm64Library(stream, new File(stage, lib));
                    }
                }
                for (File f : stage.listFiles()) Os.chmod(f.getPath(), 0500);
                File dir = driverDir(context);
                deleteTree(dir);
                Os.rename(stage.getPath(), dir.getPath());
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("driver_lib", lib).putString("driver_name", name).commit();
                choose(context, true);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : "No se pudo importar el archivo";
            } finally {
                deleteTree(stage);
            }
            status = error == null ? (name.isEmpty() ? lib : name) + " importado y elegido. Reinicia para usarlo." : error;
            busy = false;
        }, "driver-import").start();
    }

    // An AdrenoTools pack: meta.json names the library; every .so in it is copied (a driver may come with helper
    // libraries). Without meta.json: the zip's Vulkan library (libvulkan_*.so). Returns {library, display name}.
    private static String[] copyZipDriver(InputStream source, File stage) throws IOException {
        String metaLib = null, display = "";
        String found = null;
        try (ZipInputStream zip = new ZipInputStream(source)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                String path = entry.getName(), base = path.substring(path.lastIndexOf('/') + 1);
                if (base.equals("meta.json")) {
                    try {
                        JSONObject m = new JSONObject(readAll(zip, 1 << 20));
                        metaLib = m.optString("libraryName", null);
                        String n = m.optString("name", ""), v = m.optString("driverVersion", "");
                        String vendor = m.optString("vendor", "");
                        display = (n + (v.isEmpty() ? "" : " " + v) + (vendor.isEmpty() ? "" : " (" + vendor + ")")).trim();
                    } catch (Exception e) {
                        throw new IOException("El meta.json del paquete no se puede leer");
                    }
                } else if (base.endsWith(".so") && !base.contains("..")) {
                    copyArm64Library(zip, new File(stage, base));
                    if (base.startsWith("libvulkan") && found == null) found = base;
                }
            }
        }
        String lib = metaLib != null && !metaLib.isEmpty() ? metaLib : found;
        if (lib == null) throw new IOException("El ZIP no contiene un driver Vulkan (.so)");
        if (!new File(stage, lib).isFile()) throw new IOException("El paquete no trae " + lib + ", que su meta.json nombra");
        return new String[]{lib, display};
    }

    private static String readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) {
            o.write(b, 0, n);
            if (o.size() > max) throw new IOException("meta.json demasiado grande");
        }
        return o.toString("UTF-8");
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
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
