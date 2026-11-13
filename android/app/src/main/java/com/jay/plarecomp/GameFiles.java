package com.jay.plarecomp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

// Game Files (the launcher's setup page, runtime/ui/launcher.mm): the player's own prod.keys, game (.nsp / .xci) and
// update (.nsp), chosen with Android's file picker. prod.keys is copied into the app's files (a few KB); the game and
// the update stay where the player keeps them - the app holds a permission to read them and hands their file
// descriptors to android/gamefiles.c, which writes the extracted game (exefs/ + romfs.bin) into the app's files.
// After that the player may delete them: the port runs from its own copy.
final class GameFiles {
    static final int KEYS = 0, GAME = 1, UPDATE = 2;
    static final int PICK_BASE = 60;  // (request codes 60, 61, 62)
    private static final String TAG = "plarecomp";
    private static final String PREFS = "game_files";
    private static final ParcelFileDescriptor[] open = new ParcelFileDescriptor[3];

    static void pick(Activity a, int kind) {
        a.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");  // (.keys / .nsp / .xci have no MIME type Android knows)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                a.startActivityForResult(i, PICK_BASE + kind);
            } catch (Exception e) {
                Log.e(TAG, "file picker", e);
            }
        });
    }

    static File keysFile(Context c) {
        return new File(c.getExternalFilesDir(null), "import/prod.keys");
    }

    // the picker's answer (PlaActivity.onActivityResult)
    static boolean onPicked(Activity a, int request, Uri uri) {
        int kind = request - PICK_BASE;
        if (kind < 0 || kind > 2 || uri == null) return false;
        String name = displayName(a, uri);
        long size = displaySize(a, uri);
        SharedPreferences.Editor ed = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (kind == KEYS) {
            File out = keysFile(a);
            out.getParentFile().mkdirs();
            try (InputStream in = a.getContentResolver().openInputStream(uri); OutputStream o = new FileOutputStream(out)) {
                byte[] b = new byte[65536];
                for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            } catch (Exception e) {
                Log.e(TAG, "prod.keys copy", e);
                return true;
            }
        } else {
            try {
                a.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                Log.w(TAG, "persistable permission", e);
            }
            ed.putString("uri" + kind, uri.toString());
        }
        ed.putString("name" + kind, name).putLong("size" + kind, size).apply();
        return true;
    }

    // "name (size)" of the chosen file, "" if none
    static String label(Context c, int kind) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String name = p.getString("name" + kind, "");
        if (name.isEmpty()) return "";
        if (kind == KEYS && !keysFile(c).exists()) return "";
        long size = p.getLong("size" + kind, -1);
        return size > (1L << 30) ? String.format("%s (%.1f GB)", name, size / 1073741824.0)
             : size > (1L << 20) ? String.format("%s (%.0f MB)", name, size / 1048576.0) : name;
    }

    // a file descriptor of the chosen game / update for the extraction (-1: none or unreadable); held until close()
    static int openFd(Context c, int kind) {
        String u = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("uri" + kind, "");
        if (u.isEmpty()) return -1;
        try {
            if (open[kind] != null) open[kind].close();
            open[kind] = c.getContentResolver().openFileDescriptor(Uri.parse(u), "r");
            return open[kind] != null ? open[kind].getFd() : -1;
        } catch (Exception e) {
            Log.e(TAG, "open " + u, e);
            return -1;
        }
    }

    static void closeAll() {
        for (int i = 0; i < open.length; i++) {
            try {
                if (open[i] != null) open[i].close();
            } catch (Exception ignored) {
            }
            open[i] = null;
        }
    }

    // after a finished import: the choices are forgotten (the player may delete the files now)
    static void forget(Context c) {
        closeAll();
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions())
            c.getContentResolver().releasePersistableUriPermission(p.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        keysFile(c).delete();
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) return q.getString(0);
        } catch (Exception ignored) {
        }
        String s = uri.getLastPathSegment();
        return s != null ? s : "?";
    }

    private static long displaySize(Context c, Uri uri) {
        try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (q != null && q.moveToFirst() && !q.isNull(0)) return q.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }
}
