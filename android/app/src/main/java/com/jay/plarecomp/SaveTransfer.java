package com.jay.plarecomp;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

// The launcher's Saves tab on Android (runtime/ui/launcher.mm): the desktop's folder picker and system tar don't
// exist here, so the save goes through Android's file picker.
//  - Export: the player chooses where to create a .zip; it holds save_0/... (the desktop's export, same layout).
//  - Import: a .zip (an export, or a JKSV backup zipped) is opened into save/.import_android; a lone file (the save's
//    "main", as JKSV or an emulator keeps it) is copied there. Then the launcher's own import (find the folder with
//    "main", keep the current save as a backup, copy) runs on that folder.
final class SaveTransfer {
    static final int PICK_EXPORT = 70, PICK_IMPORT = 71, PICK_LOGS = 72;
    private static final String TAG = "plarecomp";
    // results for the launcher (nativeSaveResult): 1 exported, 2 export failed, 3 import file unreadable, 4 nothing to export
    static native void nativeSaveResult(int code, String detail);
    static native void nativeSavePicked(String dir);

    static File root(Activity a) { return a.getExternalFilesDir(null); }

    static void pickExport(Activity a) {
        a.runOnUiThread(() -> {
            if (!new File(root(a), "save/save_0").isDirectory()) { nativeSaveResult(4, ""); return; }
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, "Arceus Recomp save.zip");
            try { a.startActivityForResult(i, PICK_EXPORT); } catch (Exception e) { Log.e(TAG, "export picker", e); }
        });
    }

    // The port menu's "Export logs" (Android tab): lasrecomp.log, the older runs' logs and larecomp.ini in a .zip the
    // player can send with a report of how the game runs on their phone (GPU, driver, profile, fps are in the log).
    static void pickLogs(Activity a) {
        a.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, "Arceus Recomp logs.zip");
            try { a.startActivityForResult(i, PICK_LOGS); } catch (Exception e) { Log.e(TAG, "logs picker", e); }
        });
    }

    private static void exportLogs(Activity a, Uri uri) {
        boolean ok = false;
        try (OutputStream o = a.getContentResolver().openOutputStream(uri, "wt"); ZipOutputStream z = new ZipOutputStream(o)) {
            File[] files = root(a).listFiles();
            byte[] buf = new byte[65536];
            if (files != null)
                for (File f : files) {
                    String n = f.getName();
                    if (!f.isFile() || !(n.endsWith(".log") || n.equals("larecomp.ini"))) continue;
                    z.putNextEntry(new ZipEntry(n));
                    try (InputStream in = new FileInputStream(f)) {
                        for (int k; (k = in.read(buf)) > 0; ) z.write(buf, 0, k);
                    }
                    z.closeEntry();
                }
            z.finish();
            ok = true;
        } catch (Exception e) {
            Log.e(TAG, "logs export", e);
        }
        final boolean done = ok;
        a.runOnUiThread(() -> android.widget.Toast.makeText(a, done ? "Logs saved: " + displayName(a, uri) : "Couldn't save the logs",
                                                            android.widget.Toast.LENGTH_LONG).show());
    }

    static void pickImport(Activity a) {
        a.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");  // (a .zip, or the save's "main": no extension)
            try { a.startActivityForResult(i, PICK_IMPORT); } catch (Exception e) { Log.e(TAG, "import picker", e); }
        });
    }

    // PlaActivity.onActivityResult; the work on a thread of its own (a save is a few MB)
    static boolean onPicked(Activity a, int request, Uri uri) {
        if (request != PICK_EXPORT && request != PICK_IMPORT && request != PICK_LOGS) return false;
        new Thread(() -> {
            if (request == PICK_LOGS) exportLogs(a, uri);
            else if (request == PICK_EXPORT) export(a, uri);
            else importFrom(a, uri);
        }, "save-transfer").start();
        return true;
    }

    private static void export(Activity a, Uri uri) {
        File dir = new File(root(a), "save/save_0");
        try (OutputStream o = a.getContentResolver().openOutputStream(uri, "wt"); ZipOutputStream z = new ZipOutputStream(o)) {
            zipTree(z, dir, "save_0/");
            z.finish();
            nativeSaveResult(1, displayName(a, uri));
        } catch (Exception e) {
            Log.e(TAG, "save export", e);
            nativeSaveResult(2, String.valueOf(e.getMessage()));
        }
    }

    private static void zipTree(ZipOutputStream z, File dir, String prefix) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        byte[] buf = new byte[65536];
        for (File f : files) {
            if (f.isDirectory()) { zipTree(z, f, prefix + f.getName() + "/"); continue; }
            z.putNextEntry(new ZipEntry(prefix + f.getName()));
            try (InputStream in = new FileInputStream(f)) {
                for (int n; (n = in.read(buf)) > 0; ) z.write(buf, 0, n);
            }
            z.closeEntry();
        }
    }

    private static void importFrom(Activity a, Uri uri) {
        File tmp = new File(root(a), "save/.import_android");
        deleteTree(tmp);
        tmp.mkdirs();
        String name = displayName(a, uri);
        byte[] buf = new byte[65536];
        try (InputStream in = a.getContentResolver().openInputStream(uri)) {
            if (name.toLowerCase().endsWith(".zip")) {
                try (ZipInputStream z = new ZipInputStream(in)) {
                    String base = tmp.getCanonicalPath() + File.separator;
                    for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                        File out = new File(tmp, e.getName());
                        if (!out.getCanonicalPath().startsWith(base)) continue;  // (no paths out of the folder)
                        if (e.isDirectory()) { out.mkdirs(); continue; }
                        out.getParentFile().mkdirs();
                        try (OutputStream o = new FileOutputStream(out)) {
                            for (int n; (n = z.read(buf)) > 0; ) o.write(buf, 0, n);
                        }
                    }
                }
            } else {  // a lone save file: the game's save is the file "main" in its folder
                try (OutputStream o = new FileOutputStream(new File(tmp, "main"))) {
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "save import", e);
            nativeSaveResult(3, String.valueOf(e.getMessage()));
            return;
        }
        nativeSavePicked(tmp.getAbsolutePath());
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static String displayName(Activity a, Uri uri) {
        try (Cursor q = a.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) return q.getString(0);
        } catch (Exception ignored) {
        }
        String s = uri.getLastPathSegment();
        return s != null ? s : "";
    }
}
