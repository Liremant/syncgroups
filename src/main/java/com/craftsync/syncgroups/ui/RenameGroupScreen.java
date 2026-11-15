package com.craftsync.syncgroups.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Tiny dialog: one EditBox for the group tag, Done/Cancel. Replaces the stored
 * tag through {@code onDone} on the calling screen — the caller owns the store
 * write and any error display.
 */
public class RenameGroupScreen extends Screen {

    private static final int MAX_LEN = 16;

    private final Screen parent;
    private final String currentTag;
    private final Consumer<String> onDone;
    private EditBox tagBox;

    public RenameGroupScreen(Screen parent, String currentTag, Consumer<String> onDone) {
        super(Component.translatable("syncgroups.rename.title"));
        this.parent = parent;
        this.currentTag = currentTag == null ? "" : currentTag;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        int w = 220;
        tagBox = new EditBox(font, width / 2 - w / 2, height / 2 - 10, w, 20,
                Component.translatable("syncgroups.create.tag"));
        tagBox.setMaxLength(MAX_LEN);
        tagBox.setValue(currentTag);
        addRenderableWidget(tagBox);
        setInitialFocus(tagBox);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onDone.accept(tagBox.getValue().trim()))
                .bounds(width / 2 - 100, height / 2 + 24, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 + 2, height / 2 + 24, 98, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, height / 2 - 32, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
