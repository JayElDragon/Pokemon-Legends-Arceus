package com.jay.plarecomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * On-screen controls, laid out exactly as the user's reference picture (1672x941, scaled to the screen): ZL/L and
 * ZR/R in the top corners, the left stick with the d-pad under it, X/Y/A/B as a diamond with the right stick under
 * it, and -, Home, + along the bottom. (On / off: the port menu, Video > Android.)
 *
 * Buttons carry the Switch HID bit of runtime/hle/input.c (A 0, B 1, X 2, Y 3, L3 4, R3 5, L 6, R 7, ZL 8, ZR 9,
 * + 10, - 11, left 12, up 13, right 14, down 15). Home (bit 16) opens the port's menu (runtime/nvn/vulkan/device.c).
 * A double tap on a stick holds L3 / R3 (the picture has no stick-click buttons).
 */
final class TouchControls extends View {
    private static final float REF_W = 1672f, REF_H = 941f;
    private static final int HOME = 16;

    private enum Shape { CIRCLE, RECT, SHOULDER_L, SHOULDER_R, PILL, STICK, ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT, TOGGLE }

    private static final class Control {
        final String label;
        final Shape shape;
        final int code;          // HID bit; -1 / -2 left / right stick; -3 the toggle
        final RectF box = new RectF();
        float cx, cy, r;         // (circles and sticks)
        int pointer = -1, ax, ay;
        long lastUp;             // (sticks: double tap -> L3 / R3)
        boolean click;
        Control(String label, Shape shape, int code) { this.label = label; this.shape = shape; this.code = code; }
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Control> controls = new ArrayList<>();
    private final HashMap<Integer, Control> active = new HashMap<>();
    private final Path path = new Path();
    private float sx = 1, sy = 1;
    // Hidden while the player uses a controller (PlaActivity: any gamepad key or stick), back at the first touch
    private volatile boolean padHidden;
    private boolean editing;
    private Control dragged;
    private float grabX, grabY;

    void editLayout(boolean edit) {
        reset();
        editing = edit;
        dragged = null;
        invalidate();
    }

    void saveLayout() {
        android.content.SharedPreferences.Editor prefs = layoutPrefs().edit();
        for (Control c : controls) {
            prefs.putFloat(c.code + "x", c.cx / getWidth());
            prefs.putFloat(c.code + "y", c.cy / getHeight());
        }
        prefs.apply();
    }

    private android.content.SharedPreferences layoutPrefs() {
        return getContext().getSharedPreferences("touch_layout", Context.MODE_PRIVATE);
    }

    void reloadLayout(boolean defaults) {
        buildLayout(getWidth(), getHeight(), defaults);
        invalidate();
    }

    private void move(Control c, float x, float y, int width, int height) {
        float halfW = c.box.width() / 2, halfH = c.box.height() / 2;
        c.cx = Math.max(halfW, Math.min(width - halfW, x));
        c.cy = Math.max(halfH, Math.min(height - halfH, y));
        c.box.offsetTo(c.cx - halfW, c.cy - halfH);
    }

    void padUsed() {
        if (padHidden) return;
        padHidden = true;
        post(this::reset);
    }

    TouchControls(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFF4F6F8);
        text.setShadowLayer(3f, 0f, 1.5f, 0xAA000000);
    }

    // (reference picture coordinates -> this view)
    private Control rect(String label, Shape shape, int code, float l, float t, float r, float b) {
        Control c = new Control(label, shape, code);
        c.box.set(l * sx, t * sy, r * sx, b * sy);
        c.cx = c.box.centerX();
        c.cy = c.box.centerY();
        c.r = Math.min(c.box.width(), c.box.height()) / 2;
        controls.add(c);
        return c;
    }
    private Control circle(String label, Shape shape, int code, float x, float y, float radius) {
        Control c = new Control(label, shape, code);
        float rr = radius * Math.min(sx, sy);
        c.cx = x * sx;
        c.cy = y * sy;
        c.r = rr;
        c.box.set(c.cx - rr, c.cy - rr, c.cx + rr, c.cy + rr);
        controls.add(c);
        return c;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        buildLayout(width, height, false);
    }

    private void buildLayout(int width, int height, boolean defaults) {
        reset();
        dragged = null;
        controls.clear();
        if (width <= 0 || height <= 0) return;
        sx = width / REF_W;
        sy = height / REF_H;
        rect("ZL", Shape.SHOULDER_L, 8, 45, 28, 203, 107);
        rect("L", Shape.SHOULDER_L, 6, 45, 128, 205, 208);
        rect("ZR", Shape.SHOULDER_R, 9, 1468, 28, 1627, 107);
        rect("R", Shape.SHOULDER_R, 7, 1467, 128, 1628, 208);
        circle("", Shape.STICK, -1, 172, 462, 112);
        rect("", Shape.ARROW_UP, 13, 160, 610, 250, 712);
        rect("", Shape.ARROW_LEFT, 12, 82, 692, 184, 778);
        rect("", Shape.ARROW_RIGHT, 14, 228, 692, 330, 778);
        rect("", Shape.ARROW_DOWN, 15, 160, 758, 250, 860);
        circle("X", Shape.CIRCLE, 2, 1493, 375, 50);
        circle("Y", Shape.CIRCLE, 3, 1409, 462, 50);
        circle("A", Shape.CIRCLE, 0, 1578, 462, 50);
        circle("B", Shape.CIRCLE, 1, 1493, 548, 50);
        circle("", Shape.STICK, -2, 1468, 742, 112);
        rect("-", Shape.PILL, 11, 652, 838, 743, 897);
        circle("home", Shape.CIRCLE, HOME, 836, 866, 37);
        rect("+", Shape.PILL, 10, 928, 838, 1020, 897);
        if (!defaults) {
            android.content.SharedPreferences prefs = layoutPrefs();
            for (Control c : controls)
                move(c, prefs.getFloat(c.code + "x", c.cx / width) * width,
                        prefs.getFloat(c.code + "y", c.cy / height) * height, width, height);
        }
        // (no on-screen toggle: the controls are switched on / off in the port menu, Video > Android)
    }

    // ---- drawing
    private void body(Canvas canvas, Control c, boolean pressed) {
        fill.setShader(null);
        fill.setColor(pressed ? 0xC07C8796 : 0xA6343B45);
        line.setColor(0xD9F2F4F7);
        line.setStrokeWidth(Math.max(2f, 3f * sy));
        float rad = c.box.height() * .28f;
        switch (c.shape) {
            case CIRCLE:
                canvas.drawCircle(c.cx, c.cy, c.r, fill);
                canvas.drawCircle(c.cx, c.cy, c.r, line);
                break;
            case PILL: case TOGGLE:
                rad = c.box.height() / 2;
                canvas.drawRoundRect(c.box, rad, rad, fill);
                canvas.drawRoundRect(c.box, rad, rad, line);
                break;
            case SHOULDER_L: case SHOULDER_R: {
                // a rounded box whose outer top corner is cut on a slant (as the picture's shoulder buttons)
                RectF b = c.box;
                float cut = b.height() * .62f, k = rad;  // (the slanted outer corner, as in the picture)
                path.reset();
                if (c.shape == Shape.SHOULDER_L) {
                    path.moveTo(b.left + cut, b.top);
                    path.lineTo(b.right - k, b.top);
                    path.quadTo(b.right, b.top, b.right, b.top + k);
                    path.lineTo(b.right, b.bottom - k);
                    path.quadTo(b.right, b.bottom, b.right - k, b.bottom);
                    path.lineTo(b.left + k, b.bottom);
                    path.quadTo(b.left, b.bottom, b.left, b.bottom - k);
                    path.lineTo(b.left, b.top + cut + k * .3f);
                    path.quadTo(b.left, b.top + cut * .55f, b.left + cut * .45f, b.top + cut * .2f);
                } else {
                    path.moveTo(b.right - cut, b.top);
                    path.lineTo(b.left + k, b.top);
                    path.quadTo(b.left, b.top, b.left, b.top + k);
                    path.lineTo(b.left, b.bottom - k);
                    path.quadTo(b.left, b.bottom, b.left + k, b.bottom);
                    path.lineTo(b.right - k, b.bottom);
                    path.quadTo(b.right, b.bottom, b.right, b.bottom - k);
                    path.lineTo(b.right, b.top + cut + k * .3f);
                    path.quadTo(b.right, b.top + cut * .55f, b.right - cut * .45f, b.top + cut * .2f);
                }
                path.close();
                canvas.drawPath(path, fill);
                canvas.drawPath(path, line);
                break;
            }
            default: {  // d-pad keys: separate keys with a small gap between them (the picture's)
                RectF k2 = new RectF(c.box);
                k2.inset(c.box.width() * .06f, c.box.height() * .06f);
                canvas.drawRoundRect(k2, rad, rad, fill);
                canvas.drawRoundRect(k2, rad, rad, line);
            }
        }
    }

    private void glyph(Canvas canvas, Control c) {
        fill.setShader(null);
        fill.setColor(0xFFF4F6F8);
        float h = c.box.height();
        switch (c.shape) {
            case ARROW_UP: case ARROW_DOWN: case ARROW_LEFT: case ARROW_RIGHT: {
                float a = Math.min(c.box.width(), h) * .17f, x = c.cx, y = c.cy;
                path.reset();
                if (c.shape == Shape.ARROW_UP) { path.moveTo(x, y - a); path.lineTo(x + a * 1.15f, y + a * .8f); path.lineTo(x - a * 1.15f, y + a * .8f); }
                else if (c.shape == Shape.ARROW_DOWN) { path.moveTo(x, y + a); path.lineTo(x + a * 1.15f, y - a * .8f); path.lineTo(x - a * 1.15f, y - a * .8f); }
                else if (c.shape == Shape.ARROW_LEFT) { path.moveTo(x - a, y); path.lineTo(x + a * .8f, y - a * 1.15f); path.lineTo(x + a * .8f, y + a * 1.15f); }
                else { path.moveTo(x + a, y); path.lineTo(x - a * .8f, y - a * 1.15f); path.lineTo(x - a * .8f, y + a * 1.15f); }
                path.close();
                canvas.drawPath(path, fill);
                return;
            }
            case TOGGLE: {  // two Joy-Con halves
                float w = h * .34f, hh = h * .52f, gap = h * .05f, rr = w * .5f;
                RectF l = new RectF(c.cx - gap - w, c.cy - hh / 2, c.cx - gap, c.cy + hh / 2);
                RectF r = new RectF(c.cx + gap, c.cy - hh / 2, c.cx + gap + w, c.cy + hh / 2);
                line.setStrokeWidth(Math.max(2f, 2.4f * sy));
                line.setColor(0xFFF4F6F8);
                canvas.drawRoundRect(l, rr, rr, line);
                canvas.drawRoundRect(r, rr, rr, fill);
                fill.setColor(0xFF3A414B);
                canvas.drawCircle(l.centerX(), l.top + hh * .3f, w * .17f, line);
                canvas.drawCircle(r.centerX(), r.bottom - hh * .3f, w * .17f, fill);
                return;
            }
            default:
        }
        if (c.code == HOME) {  // a little house
            float s = c.r * .48f, x = c.cx, y = c.cy + s * .1f;
            line.setColor(0xFFF4F6F8);
            line.setStrokeWidth(Math.max(2f, c.r * .11f));
            path.reset();
            path.moveTo(x - s, y - s * .05f);
            path.lineTo(x, y - s * .95f);
            path.lineTo(x + s, y - s * .05f);
            path.moveTo(x - s * .72f, y - s * .25f);
            path.lineTo(x - s * .72f, y + s * .8f);
            path.lineTo(x + s * .72f, y + s * .8f);
            path.lineTo(x + s * .72f, y - s * .25f);
            canvas.drawPath(path, line);
            canvas.drawRect(x - s * .22f, y + s * .25f, x + s * .22f, y + s * .8f, fill);
            return;
        }
        if (c.label.equals("-") || c.label.equals("+")) {  // drawn bars: the font's minus sits too low
            float a = h * .2f, t = Math.max(3f, h * .085f);
            canvas.drawRoundRect(c.cx - a, c.cy - t / 2, c.cx + a, c.cy + t / 2, t / 2, t / 2, fill);
            if (c.label.equals("+")) canvas.drawRoundRect(c.cx - t / 2, c.cy - a, c.cx + t / 2, c.cy + a, t / 2, t / 2, fill);
            return;
        }
        text.setTextSize(c.shape == Shape.CIRCLE ? c.r * 1.05f : h * .55f);
        float cx = c.cx;
        if (c.shape == Shape.SHOULDER_L) cx += c.box.width() * .04f;
        if (c.shape == Shape.SHOULDER_R) cx -= c.box.width() * .04f;
        canvas.drawText(c.label, cx, c.cy - (text.ascent() + text.descent()) / 2, text);
    }

    private void stick(Canvas canvas, Control c) {
        fill.setShader(null);
        fill.setColor(c.pointer >= 0 ? 0x803A414B : 0x593A414B);
        canvas.drawCircle(c.cx, c.cy, c.r, fill);
        line.setColor(0xD9F2F4F7);
        line.setStrokeWidth(Math.max(2f, 3f * sy));
        canvas.drawCircle(c.cx, c.cy, c.r, line);
        float kr = c.r * .56f, kx = c.cx + c.r * .45f * c.ax / 32767f, ky = c.cy - c.r * .45f * c.ay / 32767f;
        knob.setShader(new RadialGradient(kx - kr * .25f, ky - kr * .3f, kr * 1.2f,
                new int[] {0xFFE6E8EB, 0xFFB9BEC5, 0xFF8E949C}, new float[] {0f, .6f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(kx, ky, kr, knob);
        line.setColor(c.click ? 0xFF7FD3E6 : 0xE6FFFFFF);
        canvas.drawCircle(kx, ky, kr, line);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (editing || (PlaActivity.touchInGame() && !padHidden)) {
            boolean all = editing || PlaActivity.touchEnabled();  // (controls off: the Home button alone stays, to reach the menu)
            for (Control c : controls) {
                if (!all && c.code != HOME) continue;
                if (c.shape == Shape.STICK) { stick(canvas, c); continue; }
                body(canvas, c, c.pointer >= 0);
                glyph(canvas, c);
            }
        } else if (!active.isEmpty()) reset();
        postInvalidateDelayed(50);
    }

    // ---- touch
    private boolean hit(Control c, float x, float y) {
        if (c.shape == Shape.CIRCLE) return Math.hypot(x - c.cx, y - c.cy) <= c.r * 1.15f;
        if (c.shape == Shape.STICK) return Math.hypot(x - c.cx, y - c.cy) <= c.r * 1.25f;
        float pad = 10 * sy;
        return x >= c.box.left - pad && x <= c.box.right + pad && y >= c.box.top - pad && y <= c.box.bottom + pad;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editing) return editTouch(event);
        if (!PlaActivity.touchInGame() && active.isEmpty()) return false;
        boolean all = PlaActivity.touchEnabled();
        if (padHidden) { padHidden = false; invalidate(); }  // (a touch brings them back - and this touch counts)
        int action = event.getActionMasked(), index = event.getActionIndex();
        int id = event.getPointerId(index);
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = event.getX(index), y = event.getY(index);
            for (Control c : controls) {
                if (c.pointer >= 0 || (!all && c.code != HOME) || !hit(c, x, y)) continue;
                if (c.shape == Shape.STICK) c.click = SystemClock.uptimeMillis() - c.lastUp < 280;
                c.pointer = id;
                active.put(id, c);
                break;
            }
            if (action == MotionEvent.ACTION_DOWN && active.isEmpty()) return false;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            Control c = active.remove(id);
            if (c != null) {
                c.pointer = -1;
                if (c.shape == Shape.STICK) { c.ax = c.ay = 0; c.click = false; c.lastUp = SystemClock.uptimeMillis(); }
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            reset();
            return true;
        }
        for (int i = 0; i < event.getPointerCount(); i++) {
            Control c = active.get(event.getPointerId(i));
            if (c == null || c.shape != Shape.STICK) continue;
            float x = event.getX(i) - c.cx, y = c.cy - event.getY(i);
            double distance = Math.hypot(x, y), deadzone = c.r * .1;
            double magnitude = Math.max(0, Math.min(1, (distance - deadzone) / (c.r * .8 - deadzone)));
            double scale = distance > 0 ? 32767 * magnitude / distance : 0;
            c.ax = (int) (x * scale);
            c.ay = (int) (y * scale);
        }
        send();
        invalidate();
        return true;
    }

    private boolean editTouch(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                for (int i = controls.size() - 1; i >= 0; --i) {
                    Control c = controls.get(i);
                    if (hit(c, x, y)) {
                        dragged = c;
                        grabX = x - c.cx;
                        grabY = y - c.cy;
                        break;
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragged != null) move(dragged, x - grabX, y - grabY, getWidth(), getHeight());
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                dragged = null;
                break;
        }
        invalidate();
        return true;
    }

    private void send() {
        int buttons = 0, lx = 0, ly = 0, rx = 0, ry = 0;
        for (Control c : active.values()) {
            if (c.code >= 0) buttons |= 1 << c.code;
            else if (c.code == -1) { lx = c.ax; ly = c.ay; if (c.click) buttons |= 1 << 4; }
            else if (c.code == -2) { rx = c.ax; ry = c.ay; if (c.click) buttons |= 1 << 5; }
        }
        PlaActivity.touchState(buttons, lx, ly, rx, ry);
    }

    void reset() {
        for (Control c : active.values()) { c.pointer = -1; c.ax = c.ay = 0; c.click = false; }
        active.clear();
        PlaActivity.touchState(0, 0, 0, 0, 0);
        invalidate();
    }
}
