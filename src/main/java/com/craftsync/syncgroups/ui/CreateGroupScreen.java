package com.craftsync.syncgroups.ui;

import com.craftsync.syncgroups.SyncGroupsMod;
import com.craftsync.syncgroups.core.Colors;
import com.craftsync.syncgroups.core.Group;
import com.craftsync.syncgroups.core.ServerKey;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec §5.3, step 2 of the wizard: tag (max 16 chars) + full color picker.
 * After a successful create the player is sent back to the server list
 * (doneScreen), not to the management screen.
 */
public class CreateGroupScreen extends Screen {

    private static final int PICKER_W = 220;
    private static final int PICKER_H = 140;
    /** Gap between the picker block and the tag/button column. */
    private static final int COLUMN_GAP = 26;
    private static final int COLUMN_W = 130;

    private final Screen parent;     // cancel goes here (the wizard's previous step)
    private final Screen doneScreen; // successful create goes here (the server list)
    private final List<ServerData> servers;
    private EditBox tagBox;
    private ColorPicker picker;
    private int initialColor;

    public CreateGroupScreen(Screen parent, Screen doneScreen, List<ServerData> servers) {
        super(Component.translatable("syncgroups.create.title"));
        this.parent = parent;
        this.doneScreen = doneScreen;
        this.servers = servers;
        // Chosen once per screen: re-initialising on window resize must not
        // silently hand the user a different color than the one they picked.
        this.initialColor = Colors.randomGroupColor();
    }

    @Override
    protected void init() {
        if (picker != null) {
            initialColor = picker.colorInt();
        }
        // Centre the whole block (picker + tag column) and keep it on screen: at
        // small window sizes the old fixed offset pushed the tag field and the
        // buttons past the right edge.
        int total = ColorPicker.layoutWidth(PICKER_W) + COLUMN_GAP + COLUMN_W;
        int x0 = Math.max(8, (width - total) / 2);
        picker = new ColorPicker(x0, 50, PICKER_W, PICKER_H, initialColor);

        int rx = x0 + ColorPicker.layoutWidth(PICKER_W) + COLUMN_GAP;

        tagBox = new EditBox(font, rx, 70, COLUMN_W, 20, Component.translatable("syncgroups.create.tag"));
        tagBox.setMaxLength(16);
        addRenderableWidget(tagBox);

        Button create = Button.builder(Component.translatable("syncgroups.create.confirm"), b -> onCreate())
                .bounds(rx, 110, COLUMN_W, 20)
                .build();
        create.active = false;
        tagBox.setResponder(s -> create.active = !s.isBlank());
        addRenderableWidget(create);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(rx, 136, COLUMN_W, 20)
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
            Group group = SyncGroupsMod.engine().createGroup(tag, picker.colorInt(), keys, names, SyncGroupsMod.store());
            SyncGroupsMod.LOGGER.info("Created sync group '{}' ({}) for {} server(s)", tag, group.id, keys.size());
        } catch (IOException | IllegalStateException e) {
            SyncGroupsMod.LOGGER.error("Failed to create group", e);
        }
        // Spec: after creating a group, return to the multiplayer server list.
        minecraft.setScreenAndShow(doneScreen);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 12, 0xFFFFFF);
        g.centeredText(font, Component.translatable("syncgroups.create.step"), width / 2, 26, 0x909090);
        g.text(font, Component.translatable("syncgroups.create.color"), pickerX(), 40, 0xA0A0A0);
        int rx = pickerX() + ColorPicker.layoutWidth(PICKER_W) + COLUMN_GAP;
        g.text(font, Component.translatable("syncgroups.create.tag"), rx, 60, 0xA0A0A0);
        picker.draw(g, font);
    }

    private int pickerX() {
        int total = ColorPicker.layoutWidth(PICKER_W) + COLUMN_GAP + COLUMN_W;
        return Math.max(8, (width - total) / 2);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (picker.mouseClicked(event)) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (picker.mouseDragged(event)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        picker.mouseReleased(event);
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
