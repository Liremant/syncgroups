package com.craftsync.syncgroups.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Full HSV color picker: saturation/value square + hue strip + preview swatch.
 * Self-contained: the screen forwards mouse events and calls draw() from its
 * extractRenderState. Uses only GuiGraphicsExtractor.fill() — no textures.
 */
public class ColorPicker {

    private static final int HUE_STRIP_W = 14;
    private static final int GAP = 10;
    private static final int PREVIEW_H = 16;

    private final int x, y, w, h;
    /** Hue in [0,1), sat/val in [0,1]. Defaults to green (#55FF55). */
    private float hue = 1f / 3f, sat = 0.667f, val = 1f;
    private boolean dragSv, dragHue;

    public ColorPicker(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public int colorInt() {
        return 0xFF000000 | hsvToRgb(hue, sat, val);
    }

    public static int hsvToRgb(float h, float s, float v) {
        int i = (int) (h * 6) % 6;
        float f = h * 6 - (int) (h * 6);
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        return switch (i) {
            case 0 -> rgb(v, t, p);
            case 1 -> rgb(q, v, p);
            case 2 -> rgb(p, v, t);
            case 3 -> rgb(p, q, v);
            case 4 -> rgb(t, p, v);
            default -> rgb(v, p, q);
        };
    }

    private static int rgb(float r, float g, float b) {
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    private int hueX() {
        return x + w + GAP;
    }

    // ---------------------------------------------------------------- events

    public boolean mouseClicked(MouseButtonEvent e) {
        double mx = e.x(), my = e.y();
        if (mx >= x && mx < x + w && my >= y && my < y + h) {
            dragSv = true;
            updateSv(mx, my);
            return true;
        }
        if (mx >= hueX() && mx < hueX() + HUE_STRIP_W && my >= y && my < y + h) {
            dragHue = true;
            updateHue(my);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(MouseButtonEvent e) {
        if (dragSv) {
            updateSv(e.x(), e.y());
            return true;
        }
        if (dragHue) {
            updateHue(e.y());
            return true;
        }
        return false;
    }

    public void mouseReleased(MouseButtonEvent e) {
        dragSv = false;
        dragHue = false;
    }

    private void updateSv(double mx, double my) {
        sat = clampF((float) (mx - x) / w);
        val = 1 - clampF((float) (my - y) / h);
    }

    private void updateHue(double my) {
        hue = clampF((float) (my - y) / h);
    }

    private static float clampF(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    // ---------------------------------------------------------------- render

    public void draw(GuiGraphicsExtractor gui, Font font) {
        // Saturation/value square.
        int cells = 24;
        int cw = w / cells + 1;
        int ch = h / cells + 1;
        for (int r = 0; r <= cells; r++) {
            float v = 1 - r / (float) cells;
            for (int c = 0; c <= cells; c++) {
                float s = c / (float) cells;
                int px = x + c * w / cells;
                int py = y + r * h / cells;
                gui.fill(px, py, px + cw, py + ch, 0xFF000000 | hsvToRgb(hue, s, v));
            }
        }
        // Hue strip.
        for (int r = 0; r < cells; r++) {
            float hu = r / (float) cells;
            int py = y + r * h / cells;
            gui.fill(hueX(), py, hueX() + HUE_STRIP_W, py + ch, 0xFF000000 | hsvToRgb(hu, 1f, 1f));
        }
        // Hue marker: dark line + light line.
        int hy = y + Math.round(hue * h);
        gui.fill(hueX() - 2, hy - 1, hueX() + HUE_STRIP_W + 2, hy + 1, 0xFF000000);
        gui.fill(hueX() - 2, hy - 2, hueX() + HUE_STRIP_W + 2, hy, 0xFFFFFFFF);
        // SV marker: dark dot with white core.
        int sx = x + Math.round(sat * w);
        int sy = y + Math.round((1 - val) * h);
        gui.fill(sx - 3, sy - 3, sx + 3, sy + 3, 0xFF000000);
        gui.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFFFFFFFF);
        // Preview swatch under the square.
        int py = y + h + GAP;
        gui.fill(x - 1, py - 1, x + w + 1, py + PREVIEW_H + 1, 0xFF808080);
        gui.fill(x, py, x + w, py + PREVIEW_H, colorInt());
        gui.text(font, String.format("#%06X", colorInt() & 0xFFFFFF), x + w + GAP, py + 4, 0xFFFFFF);
    }
}
