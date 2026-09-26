package com.craftsync.syncgroups.ui;

import com.craftsync.syncgroups.core.Colors;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Full HSV color picker: saturation/value square + hue strip + preview swatch.
 * Self-contained: the screen forwards mouse events and calls draw() from its
 * extractRenderState. Uses only GuiGraphicsExtractor.fill() — no textures.
 *
 * The geometry below is the single source of truth for both drawing and
 * hit-testing. An earlier version sized each drawn cell as {@code w/cells + 1}
 * to avoid seams, which made the painted square ~10 px wider than the area it
 * accepted clicks in: the rightmost column looked selectable but was dead,
 * reported as "I can't pick a color further right". Cells are now laid out on
 * exact boundaries, so what you see is exactly what you can click.
 */
public class ColorPicker {

    private static final int HUE_STRIP_W = 14;
    private static final int GAP = 10;
    private static final int PREVIEW_H = 16;
    /** Cells per axis in the SV square; also the hue strip resolution. */
    private static final int CELLS = 24;
    /** Rough width of the "#RRGGBB" label drawn next to the preview swatch. */
    private static final int HEX_LABEL_W = 60;

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

    /** Total width needed, including the hue strip and the hex label. */
    public static int layoutWidth(int squareWidth) {
        return squareWidth + GAP + HUE_STRIP_W + GAP + HEX_LABEL_W;
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
        float sector;
        if (delta == 0) {
            sector = 0;
        } else if (max == r) {
            // Normalised into [0,6): without the outer %6 a saturated red would
            // land on hue > 1 and draw the hue marker outside the strip.
            sector = ((g - b) / delta + 6) % 6;
        } else if (max == g) {
            sector = (b - r) / delta + 2;
        } else {
            sector = (r - g) / delta + 4;
        }
        hue = sector / 6f;
    }

    public int colorInt() {
        return 0xFF000000 | Colors.hsvToRgb(hue, sat, val);
    }

    private int hueX() {
        return x + w + GAP;
    }

    // ---------------------------------------------------------------- events

    /** The SV square covers exactly the painted area; the right/bottom edges count as inside. */
    private boolean inSquare(double mx, double my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private boolean inHueStrip(double mx, double my) {
        return mx >= hueX() && mx <= hueX() + HUE_STRIP_W && my >= y && my <= y + h;
    }

    public boolean mouseClicked(MouseButtonEvent e) {
        double mx = e.x(), my = e.y();
        if (inSquare(mx, my)) {
            dragSv = true;
            updateSv(mx, my);
            return true;
        }
        if (inHueStrip(mx, my)) {
            dragHue = true;
            updateHue(my);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(MouseButtonEvent e) {
        // Keeps tracking once a drag started, even when the pointer leaves the
        // widget — values are clamped, so pulling past the edge pins to max.
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
        // Saturation/value square. Cells are laid out on exact boundaries
        // ([c*w/CELLS, (c+1)*w/CELLS)) so the painted rect is precisely the
        // hit-tested rect, and s/v span the full 0..1 range.
        for (int r = 0; r < CELLS; r++) {
            int y0 = y + r * h / CELLS;
            int y1 = y + (r + 1) * h / CELLS;
            float v = 1 - r / (float) (CELLS - 1);
            for (int c = 0; c < CELLS; c++) {
                int x0 = x + c * w / CELLS;
                int x1 = x + (c + 1) * w / CELLS;
                float s = c / (float) (CELLS - 1);
                gui.fill(x0, y0, x1, y1, 0xFF000000 | Colors.hsvToRgb(hue, s, v));
            }
        }
        // Hue strip.
        for (int r = 0; r < CELLS; r++) {
            int y0 = y + r * h / CELLS;
            int y1 = y + (r + 1) * h / CELLS;
            float hu = r / (float) (CELLS - 1);
            gui.fill(hueX(), y0, hueX() + HUE_STRIP_W, y1, 0xFF000000 | Colors.hsvToRgb(hu, 1f, 1f));
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
