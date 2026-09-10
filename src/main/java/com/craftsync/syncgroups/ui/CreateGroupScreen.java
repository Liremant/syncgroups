package com.craftsync.syncgroups.ui;

import com.craftsync.syncgroups.SyncGroupsMod;
import com.craftsync.syncgroups.core.Group;
import com.craftsync.syncgroups.core.ServerKey;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec §5.3: tag (max 16 chars) + 16-color palette, then create.
 * Runs the engine's createGroup (§4.1 + §4.2) for every selected server.
 */
public class CreateGroupScreen extends Screen {

    /** The 16 vanilla chat colors, in ChatFormatting order. */
    private static final int[] PALETTE = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    };

    private static final int SWATCH = 22;
    private static final int PER_ROW = 8;

    private final Screen parent;
    private final List<ServerData> servers;
    private EditBox tagBox;
    private int selectedColor = 0x55FF55;

    public CreateGroupScreen(Screen parent, List<ServerData> servers) {
        super(Component.translatable("syncgroups.create.title"));
        this.parent = parent;
        this.servers = servers;
    }

    @Override
    protected void init() {
        tagBox = new EditBox(font, width / 2 - 100, 40, 200, 20, Component.translatable("syncgroups.create.tag"));
        tagBox.setMaxLength(16);
        addRenderableWidget(tagBox);

        int startX = width / 2 - (PER_ROW * (SWATCH + 4)) / 2 + 2;
        for (int i = 0; i < PALETTE.length; i++) {
            int idx = i;
            addRenderableWidget(Button.builder(Component.literal(" "), b -> selectedColor = PALETTE[idx])
                    .bounds(startX + (i % PER_ROW) * (SWATCH + 4), 80 + (i / PER_ROW) * (SWATCH + 4), SWATCH, SWATCH)
                    .build());
        }

        Button create = Button.builder(Component.translatable("syncgroups.create.confirm"), b -> onCreate())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build();
        create.active = false;
        tagBox.setResponder(s -> create.active = !s.isBlank());
        addRenderableWidget(create);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 100, height - 52, 200, 20)
                .build());
    }

    private void onCreate() {
        String tag = tagBox.getValue().trim();
        if (tag.isEmpty()) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>();
            Map<String, String> names = new LinkedHashMap<>();
            for (ServerData server : servers) {
                String key = ServerKey.normalize(server.ip);
                keys.add(key);
                names.put(key, server.name);
            }
            Group group = SyncGroupsMod.engine().createGroup(tag, selectedColor, keys, names, SyncGroupsMod.store());
            SyncGroupsMod.LOGGER.info("Created sync group '{}' ({}) for {} server(s)", tag, group.id, keys.size());
        } catch (IOException | IllegalStateException e) {
            SyncGroupsMod.LOGGER.error("Failed to create group", e);
        }
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 16, 0xFFFFFF);
        // Selection ring around the active swatch.
        int startX = width / 2 - (PER_ROW * (SWATCH + 4)) / 2 + 2;
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i] == selectedColor) {
                int x = startX + (i % PER_ROW) * (SWATCH + 4);
                int y = 80 + (i / PER_ROW) * (SWATCH + 4);
                g.outline(x - 2, y - 2, SWATCH + 4, SWATCH + 4, 0xFFFFFFFF);
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
