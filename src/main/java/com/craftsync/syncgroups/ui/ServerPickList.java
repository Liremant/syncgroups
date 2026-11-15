package com.craftsync.syncgroups.ui;

import com.craftsync.syncgroups.core.Diag;
import com.craftsync.syncgroups.core.Group;
import com.craftsync.syncgroups.core.ServerKey;
import com.craftsync.syncgroups.SyncGroupsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.FaviconTexture;
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

    // Same sprites vanilla Checkbox draws — widget/checkbox[_selected][_highlighted].
    private static final net.minecraft.resources.Identifier SPRITE_SELECTED =
            net.minecraft.resources.Identifier.withDefaultNamespace("widget/checkbox_selected");
    private static final net.minecraft.resources.Identifier SPRITE_UNSELECTED =
            net.minecraft.resources.Identifier.withDefaultNamespace("widget/checkbox");

    private final ServerPickScreen screen;

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        Diag.info("PICK-LIST: click at %f,%f btn=%d (list %d..%d x %d..%d)", event.x(), event.y(), event.button(),
                getX(), getX() + getWidth(), getY(), getY() + getHeight());
        boolean handled = super.mouseClicked(event, doubled);
        Entry hit = getEntryAtPosition(event.x(), event.y());
        if (hit != null) {
            Diag.info("PICK-LIST: hit row %s rect %d..%d x %d..%d", hit.data.ip,
                    hit.getX(), hit.getX() + hit.getWidth(), hit.getY(), hit.getY() + hit.getHeight());
        }
        // 26.3 input layer numbers the left mouse button as 1 (verified: vanilla
        // AbstractWidget.isValidClickButton accepts exactly button() == 1).
        if (hit != null && event.button() == 1) {
            Diag.info("PICK: %s -> %s", hit.data.ip, screen.selected.contains(hit.data.ip) ? "off" : "on");
            if (screen.selected.contains(hit.data.ip)) {
                screen.selected.remove(hit.data.ip);
            } else {
                screen.selected.add(hit.data.ip);
            }
            screen.hint = null;
            handled = true;
        }
        return handled;
    }

    public ServerPickList(Minecraft minecraft, ServerPickScreen screen, int height) {
        super(minecraft, screen.width, height, 0, ROW_HEIGHT);
        this.screen = screen;
        for (int i = 0; i < screen.servers.size(); i++) {
            addEntry(new Entry(screen.servers.get(i)));
        }
        Diag.info("PICK-LIST: %d server rows", screen.servers.size());
    }

    @Override
    public int getRowWidth() {
        // Sane at the 427px minimum window: 300 for the row, room at the edges.
        return Math.min(300, this.width - 60);
    }

    /** Releases our own server-icon textures; called before a rebuild and from Screen.removed(). */
    public void closeIcons() {
        for (Entry e : children()) {
            e.close();
        }
    }

    public class Entry extends ObjectSelectionList.Entry<ServerPickList.Entry> implements AutoCloseable {

        private final ServerData data;
        /** Own texture id — NOT FaviconTexture: its forServer() key is shared with the
         * vanilla server list, and closing ours killed vanilla's icon → purple favicons. */
        private final net.minecraft.resources.Identifier textureId;
        private boolean hasIcon;
        private byte[] lastIconBytes;

        Entry(ServerData data) {
            this.data = data;
            this.textureId = net.minecraft.resources.Identifier.withDefaultNamespace(
                    "syncgroups/servericon/" + ServerKey.sanitize(data.ip));
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
            uploadIcon(); // render-thread only, and cheap while the bytes are unchanged
            int x = getContentX();
            int y = getContentY();
            drawCheckbox(g, x, y);
            if (hasIcon) {
                g.blit(RenderPipelines.GUI_TEXTURED, textureId, x + BOX + 4, y + 2,
                        0f, 0f, ICON, ICON, ICON, ICON);
            } else {
                // vanilla's unknown-server placeholder while nothing has been uploaded
                g.blit(RenderPipelines.GUI_TEXTURED,
                        net.minecraft.resources.Identifier.withDefaultNamespace("textures/misc/unknown_server.png"),
                        x + BOX + 4, y + 2, 0f, 0f, ICON, ICON, ICON, ICON);
            }

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
        public Component getNarration() {
            return Component.translatable("narrator.select.list.position", getItemCount());
        }

        private void drawCheckbox(GuiGraphicsExtractor g, int x, int y) {
            int bx = x + 2;
            int by = y + (ROW_HEIGHT - BOX) / 2;
            net.minecraft.resources.Identifier sprite = screen.selected.contains(data.ip)
                    ? SPRITE_SELECTED : SPRITE_UNSELECTED;
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, sprite,
                    bx, by, BOX, BOX);
        }

        private void uploadIcon() {
            byte[] bytes = data.getIconBytes();
            if (Arrays.equals(bytes, lastIconBytes)) {
                return;
            }
            lastIconBytes = bytes;
            var tm = minecraft.getTextureManager();
            if (bytes == null) {
                // No icon yet: unregister ours so the vanilla unknown-server placeholder
                // path applies; the texture id stays reserved for this screen.
                tm.release(textureId);
                hasIcon = false;
                return;
            }
            try {
                com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(bytes);
                tm.register(textureId, new net.minecraft.client.renderer.texture.DynamicTexture(
                        () -> "syncgroups-server-" + data.ip, img));
                hasIcon = true;
            } catch (Exception e) {
                SyncGroupsMod.LOGGER.warn("Invalid icon for server {}", data.ip, e);
            }
        }

        @Override
        public void close() {
            minecraft.getTextureManager().release(textureId);
            hasIcon = false;
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
