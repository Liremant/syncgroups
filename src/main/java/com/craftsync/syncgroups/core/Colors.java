package com.craftsync.syncgroups.core;

/** Color helpers shared by the engine (random group colors) and the UI picker. */
public final class Colors {

    private Colors() {
    }

    /** h, s, v in [0,1] → 0xRRGGBB (no alpha). */
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

    /** Random, but always bright and saturated enough to stay readable on the dark list. */
    public static int randomGroupColor() {
        return 0xFF000000 | hsvToRgb((float) Math.random(), 0.72f, 0.95f);
    }
}
