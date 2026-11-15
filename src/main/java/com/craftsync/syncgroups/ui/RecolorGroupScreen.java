package com.craftsync.syncgroups.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tiny dialog: the existing HSV ColorPicker bound to a group. Hands the picked
 * colour to {@code onDone} — the caller owns the store write. The picked colour
 * survives a rebuild (resize) the same way CreateGroupScreen's tag does.
 */
public class RecolorGroupScreen extends Screen {

    private static final int PICKER_W = 220;
    private static final int PICKER_H = 140;

    private final Screen parent;
    private final Supplier<Integer> currentColor;
    private final Consumer<Integer> onDone;
    private ColorPicker picker;
    private int initialColor;

    public RecolorGroupScreen(Screen parent, Supplier<Integer> currentColor, Consumer<Integer> onDone) {
        super(Component.translatable("syncgroups.recolor.title"));
        this.parent = parent;
        this.currentColor = currentColor;
        this.onDone = onDone;
        this.initialColor = currentColor.get();
    }

    @Override
    protected void init() {
        if (picker != null) {
            initialColor = picker.colorInt();
        }
        picker = new ColorPicker(Math.max(8, (width - PICKER_W) / 2), 60, PICKER_W, PICKER_H, initialColor);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
                    onDone.accept(picker.colorInt());
                    onClose();
                }).bounds(width / 2 - 100, height - FOOTER_BUTTON_Y, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 + 2, height - FOOTER_BUTTON_Y, 98, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 24, 0xFFFFFF);
        picker.draw(g, font);
    }

    private static final int FOOTER_BUTTON_Y = 30; // buttons at height-30; picker never overlaps

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (picker.mouseClicked(event)) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        if (picker.mouseDragged(event)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        picker.mouseReleased(event);
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
