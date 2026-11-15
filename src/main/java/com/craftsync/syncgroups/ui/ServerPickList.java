package com.craftsync.syncgroups.ui;

import com.craftsync.syncgroups.core.Diag;
import com.craftsync.syncgroups.core.Group;
import com.craftsync.syncgroups.core.ServerKey;
import com.craftsync.syncgroups.SyncGroupsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The saved-server rows of the picker, on a vanilla ObjectSelectionList: real
 * scrollbar, keyboard navigation and clipping instead of the manual wheel-scroll
 * rebuild that used to live in ServerPickScreen.
 *
 * Each row draws its own checkbox (vanilla's Checkbox widget is a separate
 * focusable widget that does not belong inside a list entry) and toggles the
 * screen's {@code selected} set, keyed by the saved address.
 */
public class ServerPickList extends ObjectSelectionList<ServerPickList.Entry> {

    public static final int ROW_HEIGHT = 36;
    private static final int BOX = 16;
    private static final int ICON = 32;
    private static final int TEXT_X = BOX + 4 + ICON + 3;

    private final ServerPickScreen screen;

    public ServerPickList(Minecraft minecraft, ServerPickScreen screen, int height) {
        super(minecraft, screen.width, height, 0, ROW_HEIGHT);
        this.screen = screen;
        for (int i = 0; i < screen.servers.size(); i++) {
            addEntry(new Entry(screen.servers.get(i)));
        }
    }

    @Override
    public int getRowWidth() {
        // Sane at the 427px minimum window: 300 for the row, room at the edges.
        return Math.min(300, this.width - 60);
    }

    /** Closes every favicon texture; called before a new list is built and from Screen.removed(). */
    public void closeIcons() {
        for (Entry e : children()) {
            e.close();
        }
    }

    public class Entry extends ObjectSelectionList.Entry<ServerPickList.Entry> implements AutoCloseable {

        private final ServerData data;
        private final FaviconTexture icon;
        private byte[] lastIconBytes;

        Entry(ServerData data) {
            this.data = data;
            this.icon = FaviconTexture.forServer(minecraft.getTextureManager(), data.ip);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
            uploadIcon(); // render-thread only, and cheap while the bytes are unchanged
            int x = getContentX();
            int y = getContentY();
            drawCheckbox(g, x, y);
            // textureLocation() falls back to vanilla's unknown-server texture
            // (MISSING_LOCATION) while nothing has been uploaded.
            g.blit(RenderPipelines.GUI_TEXTURED, icon.textureLocation(), x + BOX + 4, y + 2,
                    0f, 0f, ICON, ICON, ICON, ICON);

            Group group = SyncGroupsMod.store().byServer(ServerKey.normalize(data.ip)).orElse(null);
            var font = ServerPickList.this.minecraft.font;
            int textX = x + TEXT_X;
            int nameWidth = getContentWidth() - TEXT_X - 8;
            if (group != null) {
                // Same chip look as ServerEntryTagMixin: backplate + tag in the group color.
                nameWidth -= font.width(group.tag) + 12;
            }
            String name = data.name == null || data.name.isBlank() ? data.ip : data.name;
            g.text(font, SyncGroupsScreen.clip(font, name, nameWidth), textX, y + 1, 0xFFFFFFFF);
            if (group != null) {
                int cx = textX + Math.min(nameWidth, font.width(name)) + 6;
                g.fill(cx - 3, y - 1, cx + font.width(group.tag) + 3, y + 10, 0x90101010);
                g.text(font, group.tag, cx, y, 0xFF000000 | group.colorInt());
            }
            g.text(font, SyncGroupsScreen.clip(font, data.ip, getContentWidth() - TEXT_X - 8),
                    textX, y + 13, 0xFF909090);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
            if (event.button() != 0) {
                return false;
            }
            boolean on = screen.selected.contains(data.ip);
            Diag.info("PICK: %s -> %s", data.ip, !on ? "on" : "off");
            if (on) {
                screen.selected.remove(data.ip);
            } else {
                screen.selected.add(data.ip);
            }
            screen.hint = null;
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.translatable("narrator.select.list.position", getItemCount());
        }

        private void drawCheckbox(GuiGraphicsExtractor g, int x, int y) {
            int bx = x;
            int by = y + (ROW_HEIGHT - BOX) / 2;
            g.fill(bx, by, bx + BOX, by + BOX, 0xFF101010);
            g.fill(bx, by, bx + BOX, by + 1, 0xFF6C6C6C);
            g.fill(bx, by + BOX - 1, bx + BOX, by + BOX, 0xFF6C6C6C);
            g.fill(bx, by, bx + 1, by + BOX, 0xFF6C6C6C);
            g.fill(bx + BOX - 1, by, bx + BOX, by + BOX, 0xFF6C6C6C);
            if (screen.selected.contains(data.ip)) {
                for (int i = 0; i < 4; i++) {
                    g.fill(bx + 3 + i, by + 8 + i, bx + 4 + i, by + 9 + i, 0xFF00C853);
                }
                for (int i = 0; i < 6; i++) {
                    g.fill(bx + 6 + i, by + 11 - i, bx + 7 + i, by + 12 - i, 0xFF00C853);
                }
            }
        }

        private void uploadIcon() {
            byte[] bytes = data.getIconBytes();
            if (Arrays.equals(bytes, lastIconBytes)) {
                return;
            }
            lastIconBytes = bytes;
            icon.clear();
            if (bytes != null) {
                try {
                    // upload() takes ownership of the image (same as vanilla's server entry).
                    icon.upload(com.mojang.blaze3d.platform.NativeImage.read(bytes));
                } catch (Exception e) {
                    SyncGroupsMod.LOGGER.warn("Invalid icon for server {}", data.ip, e);
                }
            }
        }

        @Override
        public void close() {
            if (!icon.isClosed()) {
                icon.close();
            }
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Entry e && Objects.equals(e.data.ip, data.ip);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(data.ip);
        }
    }
}
