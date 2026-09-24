package com.craftsync.syncgroups.ui;

import com.craftsync.syncgroups.core.Colors;
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
    /** Hue in [0,1), sat/val in [0,1]. */
    private float hue, sat = 0.72f, val = 0.95f;
    private boolean dragSv, dragHue;

    public ColorPicker(int x, int y, int w, int h) {
        this(x, y, w, h, Colors.randomGroupColor());
    }

    public ColorPicker(int x, int y, int w, int h, int initialRgb) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        setRgb(initialRgb);
    }

    /** Points the picker at an existing color without losing the current hue. */
    public final void setRgb(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        val = max;
        sat = max == 0 ? 0 : delta / max;
        if (delta == 0) {
            hue = 0;
        } else if (max == r) {
            hue = ((g - b) / delta % 6 + 6) / 6f;
        } else if (max == g) {
            hue = (((b - r) / delta) + 2) / 6f;
        } else {
            hue = (((r - g) / delta) + 4) / 6f;
        }
    }

    public int colorInt() {
        return 0xFF000000 | Colors.hsvToRgb(hue, sat, val);
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
                gui.fill(px, py, px + cw, py + ch, 0xFF000000 | Colors.hsvToRgb(hue, s, v));
            }
        }
        // Hue strip.
        for (int r = 0; r < cells; r++) {
            float hu = r / (float) cells;
            int py = y + r * h / cells;
            gui.fill(hueX(), py, hueX() + HUE_STRIP_W, py + ch, 0xFF000000 | Colors.hsvToRgb(hu, 1f, 1f));
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
