package com.jay.plarecomp;

import android.os.Bundle;
import android.system.Os;
import android.util.Log;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.libsdl.app.SDLActivity;

/**
 * Proof of concept: straight into the game. The game files (exefs/ + romfs.bin from the player's own dump) sit in
 * the app's external files folder, extracted/; the runtime's data root is that folder (saves, caches, the log).
 */
public class PlaActivity extends SDLActivity {
    private static final String TAG = "plarecomp";
    private TouchControls touchControls;
    private android.widget.LinearLayout touchEditorBar;

    public void editTouchLayout() {
        runOnUiThread(() -> {
            if (touchControls == null || touchEditorBar != null) return;
            touchControls.editLayout(true);
            touchEditorBar = new android.widget.LinearLayout(this);
            touchEditorBar.setGravity(android.view.Gravity.CENTER);
            touchEditorBar.setBackgroundColor(0xdd20242c);
            addTouchEditorButton("Restablecer", () -> touchControls.reloadLayout(true));
            addTouchEditorButton("Cancelar", () -> closeTouchEditor(false));
            addTouchEditorButton("Guardar", () -> closeTouchEditor(true));
            android.widget.RelativeLayout.LayoutParams params =
                    new android.widget.RelativeLayout.LayoutParams(-2, -2);
            params.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
            params.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL);
            mLayout.addView(touchEditorBar, params);
        });
    }

    private void addTouchEditorButton(String label, Runnable action) {
        android.widget.Button button = new android.widget.Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        touchEditorBar.addView(button);
    }

    private void closeTouchEditor(boolean save) {
        if (touchEditorBar == null) return;
        if (save) touchControls.saveLayout();
        else touchControls.reloadLayout(false);
        touchControls.editLayout(false);
        mLayout.removeView(touchEditorBar);
        touchEditorBar = null;
    }
    static native void touchState(int buttons, int lx, int ly, int rx, int ry);
    static native boolean touchInGame();
    static native boolean touchEnabled();  // (the port menu's "On-screen controls": android/android_glue.c)
    // 120 with the 120 fps mode, else 60 (android/android_glue.c; before the runtime loads its settings: 60)
    static float wantedRefreshRate() {
        try { return nativeRefreshRate(); } catch (UnsatisfiedLinkError e) { return 60f; }
    }
    static native float nativeRefreshRate();

    private static void deleteTree(File file) {
        File[] children = file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath()) ? file.listFiles() : null;
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private void prepareGameCompiler() throws Exception {
        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        File python = new File(nativeDir, "libpla_pyrun.so");
        File clang = new File(nativeDir, "libpla_clang.so");
        File linker = new File(nativeDir, "libpla-gnu-lld.so");
        if (!python.isFile() || !clang.isFile() || !linker.isFile()) return;

        String revision = Long.toString(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
        File home = new File(getFilesDir(), "compiler-" + revision);
        File ready = new File(home, ".ready");
        if (!ready.isFile()) {
            File[] old = getFilesDir().listFiles((dir, name) -> name.startsWith("compiler-"));  // (earlier versions')
            if (old != null) for (File stale : old) if (!stale.equals(home)) deleteTree(stale);
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(getAssets().open("pla-toolchain.zip"))) {
                String root = home.getCanonicalPath() + File.separator;
                for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    File output = new File(home, entry.getName()).getCanonicalFile();
                    if (!output.getPath().startsWith(root)) throw new IOException("Invalid compiler asset path");
                    File directory = entry.isDirectory() ? output : output.getParentFile();
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
                    if (!entry.isDirectory()) {
                        try (FileOutputStream stream = new FileOutputStream(output)) { zip.transferTo(stream); }
                    }
                }
            }
            if (!ready.createNewFile() && !ready.isFile()) throw new IOException("Cannot mark compiler ready");
        }
        Os.setenv("LD_LIBRARY_PATH", nativeDir.getAbsolutePath(), true);
        Os.setenv("PYTHONHOME", new File(home, "python").getAbsolutePath(), true);
        Os.setenv("PYTHONPATH", new File(home, "gen").getAbsolutePath(), true);
        Os.setenv("PYTHONDONTWRITEBYTECODE", "1", true);
        Os.setenv("LIBUNICORN_PATH", nativeDir.getAbsolutePath(), true);
        Os.setenv("LIBCAPSTONE_PATH", nativeDir.getAbsolutePath(), true);
        Os.setenv("PLA_LZ4_LIB", new File(nativeDir, "liblz4.so").getAbsolutePath(), true);
        Os.setenv("LARECOMP_GEN", python.getAbsolutePath(), true);
        Os.setenv("LARECOMP_GEN_SCRIPT", new File(home, "gen/tools/gen.py").getAbsolutePath(), true);
        Os.setenv("LARECOMP_CC", clang.getAbsolutePath(), true);
        Os.setenv("LARECOMP_ANDROID_LLD", linker.getAbsolutePath(), true);
        Os.setenv("LARECOMP_ANDROID_SYSROOT", new File(home, "sysroot").getAbsolutePath(), true);
        Os.setenv("LARECOMP_ANDROID_CLANG_RES", new File(home, "clang-res").getAbsolutePath(), true);
        Os.setenv("LARECOMP_ANDROID_GAMELIB_SRC", new File(home, "gamelib-src").getAbsolutePath(), true);
        Os.setenv("LARECOMP_ANDROID_TOOLCHAIN_ID", new File(home, "toolchain-id.txt").getAbsolutePath(), true);
        Os.setenv("TMPDIR", getCacheDir().getAbsolutePath(), true);
    }

    private void installBundledFonts(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Could not create font directory");
        String[] fonts = {"BIZUDPGothic/BIZUDPGothic-Regular.ttf", "Baloo2/Baloo2-SemiBold.ttf",
                "DejaVu/DejaVuSans.ttf", "DejaVu/DejaVuSans-Bold.ttf"};
        for (String asset : fonts) {
            File font = new File(directory, asset.substring(asset.lastIndexOf('/') + 1));
            if (font.isFile() && font.length() > 0) continue;
            File temp = File.createTempFile("font-", ".tmp", directory);
            try {
                try (InputStream input = getAssets().open(asset); FileOutputStream output = new FileOutputStream(temp)) {
                    input.transferTo(output);
                }
                if (!temp.renameTo(font)) throw new IOException("Could not install " + font.getName());
            } finally {
                temp.delete();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File root = getExternalFilesDir(null);
        try {
            Os.setenv("LARECOMP_DATA_ROOT", root.getAbsolutePath(), true);
            File localGameCode = new File(getFilesDir(), "libgame.so");
            String gameCode = localGameCode.isFile() ? localGameCode.getAbsolutePath()
                    : getApplicationInfo().nativeLibraryDir + "/libgame.so";
            Os.setenv("LARECOMP_GAME_LIB", gameCode, true);
            Os.setenv("LARECOMP_ANDROID_RUNTIME", getApplicationInfo().nativeLibraryDir + "/libmain.so", true);
            Os.setenv("LARECOMP_ANDROID_BUILD_DIR", getFilesDir().getAbsolutePath(), true);
            Os.setenv("LARECOMP_NO_UPDATE_CHECK", "1", true);
            if (getSharedPreferences("launcher", MODE_PRIVATE).getBoolean("turnip", false) ||
                    getIntent().getBooleanExtra("turnip", false)) {
                File driver = DriverStore.driverFile(this);
                if (driver.isFile()) {
                    Os.setenv("PLA_VULKAN_DRIVER", driver.getAbsolutePath(), true);
                    Os.setenv("PLA_DRIVER_DIR", driver.getParent() + "/", true);  // (the pack's folder: its library + helpers)
                    Os.setenv("PLA_NATIVE_DIR", getApplicationInfo().nativeLibraryDir + "/", true);
                }
            }
            File resources = new File(root, "res");
            Os.setenv("LARECOMP_RESOURCE_DIR", resources.getAbsolutePath(), true);
            installBundledFonts(resources);
            try {
                prepareGameCompiler();
            } catch (Exception e) {
                Log.e(TAG, "Could not prepare the Android game compiler", e);
            }
            // (debugging: am start ... --es env "LARECOMP_X=1;LARECOMP_Y=2")
            String extra = getIntent() != null ? getIntent().getStringExtra("env") : null;
            if (extra != null)
                for (String kv : extra.split(";")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) Os.setenv(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim(), true);
                }
        } catch (Exception e) {
            Log.e(TAG, "setenv", e);
        }
        Log.i(TAG, "data root " + root + ", game lib " + getApplicationInfo().nativeLibraryDir);
        super.onCreate(savedInstanceState);
        goFullscreen();
    }

    // Landscape only (either way up). SDL would otherwise ask for its own orientation from the window size and hints
    // (FULL_USER: the picture turned portrait with the device).
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    // ---- the graphics driver, from the port menu (Graphics > Graphics driver: android/android_glue.c -> these)
    private static final int PICK_DRIVER = 41;
    private String activeDriver = "";  // (what this process started with: "turnip" or "system")

    public boolean driverInstalled() { return DriverStore.installed(this); }
    public boolean driverTurnipChosen() { return DriverStore.turnipChosen(this); }
    public void driverChoose(boolean turnip) { DriverStore.choose(this, turnip); }
    public String driverStatus() { return DriverStore.busy ? "Importando…" : DriverStore.status; }
    public String driverName() { return DriverStore.driverName(this); }
    public void driverImport() {
        runOnUiThread(() -> {
            android.content.Intent picker = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            picker.setType("*/*");
            picker.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            startActivityForResult(picker, PICK_DRIVER);
        });
    }
    // The driver is chosen when the process starts: a fresh process on the new choice (the game saves itself only where
    // the player saved - the menu says so)
    public void restartApp() {
        runOnUiThread(() -> {
            android.content.Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 7, i,
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            ((android.app.AlarmManager) getSystemService(ALARM_SERVICE)).set(android.app.AlarmManager.RTC,
                    System.currentTimeMillis() + 400, pi);
            finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    @Override
    protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_DRIVER && result == RESULT_OK && data != null && data.getData() != null)
            DriverStore.importFrom(this, data.getData());
        if (result == RESULT_OK && data != null && data.getData() != null) {
            GameFiles.onPicked(this, request, data.getData());
            SaveTransfer.onPicked(this, request, data.getData());
        }
    }

    // ---- Game Files (runtime/ui/launcher.mm through android/android_glue.c)
    public void gameFilePick(int kind) { GameFiles.pick(this, kind); }
    public String gameFileLabel(int kind) { return GameFiles.label(this, kind); }
    public int gameFileOpen(int kind) { return GameFiles.openFd(this, kind); }
    public String gameFileKeysPath() { return GameFiles.keysFile(this).getAbsolutePath(); }
    // ---- Saves tab: export / import through Android's file picker (SaveTransfer.java)
    public void saveExport() { SaveTransfer.pickExport(this); }
    public void saveImport() { SaveTransfer.pickImport(this); }
    public void logsExport() { SaveTransfer.pickLogs(this); }
    public void gameFilesDone(boolean forget) {
        if (forget) GameFiles.forget(this);
        else GameFiles.closeAll();
    }

    static native void nativeBackground(boolean background);  // (the game waits while off the screen: android_glue.c)
    static native void nativeThermal(float headroom);  // (runtime/nvn/output_scale.c: the thermal governor)

    // Android's thermal headroom every 2 s while on the screen (1.0 = where the system starts throttling)
    private final android.os.Handler thermalHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable thermalTick = new Runnable() {
        @Override public void run() {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                float h = pm != null ? pm.getThermalHeadroom(10) : Float.NaN;  // (forecast 10 s ahead)
                if (!Float.isNaN(h)) nativeThermal(h);
            } catch (Throwable e) { /* (before libmain is loaded / no thermal HAL) */ }
            thermalHandler.postDelayed(this, 2000);
        }
    };
    private static void setBackground(boolean b) {
        try { nativeBackground(b); } catch (UnsatisfiedLinkError e) { /* (before libmain is loaded) */ }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setBackground(false);
        thermalHandler.removeCallbacks(thermalTick);
        thermalHandler.postDelayed(thermalTick, 2000);
        // (always there: whether it shows is the port menu's setting, asked each frame - on / off at once)
        if (mLayout != null && touchControls == null) {
            touchControls = new TouchControls(this);
            mLayout.addView(touchControls, new android.view.ViewGroup.LayoutParams(-1, -1));
        }
    }

    @Override
    protected void onPause() {
        if (touchControls != null) touchControls.reset();
        setBackground(true);
        thermalHandler.removeCallbacks(thermalTick);
        super.onPause();
    }

    // A controller in use hides the on-screen controls (TouchControls: a touch shows them again)
    private static boolean fromPad(int source) {
        return (source & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
               (source & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK ||
               (source & android.view.InputDevice.SOURCE_DPAD) == android.view.InputDevice.SOURCE_DPAD;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (touchControls != null && event.getAction() == android.view.KeyEvent.ACTION_DOWN && fromPad(event.getSource()))
            touchControls.padUsed();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if (touchControls != null && fromPad(event.getSource())) {
            // (sticks / triggers past a small dead zone: a resting pad sends tiny values now and then)
            float m = 0;
            for (int axis : new int[] {android.view.MotionEvent.AXIS_X, android.view.MotionEvent.AXIS_Y,
                                       android.view.MotionEvent.AXIS_Z, android.view.MotionEvent.AXIS_RZ,
                                       android.view.MotionEvent.AXIS_HAT_X, android.view.MotionEvent.AXIS_HAT_Y,
                                       android.view.MotionEvent.AXIS_LTRIGGER, android.view.MotionEvent.AXIS_RTRIGGER})
                m = Math.max(m, Math.abs(event.getAxisValue(axis)));
            if (m > 0.35f) touchControls.padUsed();
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    // The whole screen: no status or navigation bar (they come back for a moment with a swipe from the edge), and the
    // picture under the display cutout too.
    private void goFullscreen() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        w.setDecorFitsSystemWindows(false);
        // The display at 60 Hz (120 only for the 120 fps mode): the game draws 30 / 60 fps, and at the Odin's 120 Hz
        // SurfaceFlinger and the display composer still composed 120 times a second (~19% CPU, the prime cores jumping
        // to 4.3 GHz every few seconds - the fan's surges)
        android.view.Display display = getDisplay();
        if (display != null) {
            float want = wantedRefreshRate();
            android.view.Display.Mode cur = display.getMode(), best = null;
            for (android.view.Display.Mode m : display.getSupportedModes()) {
                if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                if (best == null || Math.abs(m.getRefreshRate() - want) < Math.abs(best.getRefreshRate() - want)) best = m;
            }
            android.view.WindowManager.LayoutParams a = w.getAttributes();
            if (best != null && a.preferredDisplayModeId != best.getModeId()) {
                a.preferredDisplayModeId = best.getModeId();
                w.setAttributes(a);
                Log.i(TAG, "display mode " + best.getRefreshRate() + " Hz (wanted " + want + ")");
            }
        }
        WindowInsetsController c = w.getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL3", "main"};
    }

    @Override
    protected String[] getArguments() {
        String root = new File(getExternalFilesDir(null), "extracted").getAbsolutePath();
        // (always the port's start screen first; straight into the game only when asked: am start ... --ez play true)
        if (getIntent().getBooleanExtra("play", false)) return new String[] {"--play", root};
        return new String[] {root};
    }
}
