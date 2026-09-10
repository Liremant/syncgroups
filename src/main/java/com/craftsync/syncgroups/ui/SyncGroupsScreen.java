package com.craftsync.syncgroups.ui;

import com.craftsync.syncgroups.SyncGroupsMod;
import com.craftsync.syncgroups.core.Group;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Spec §5.1/§6 management: list existing groups, remove a server from a group
 * (auto-dissolve at ≤1 participant), or dissolve a group outright.
 */
public class SyncGroupsScreen extends Screen {

    private static final int ROW_HEIGHT = 22;
    private static final int VISIBLE_ROWS = 6;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int scroll;

    private record Row(Group group, String serverKey) {
    }

    public SyncGroupsScreen(Screen parent) {
        super(Component.translatable("syncgroups.manage.title"));
        this.parent = parent;
    }

    private void rebuildRows() {
        rows.clear();
        for (Group g : SyncGroupsMod.store().all()) {
            for (String server : g.servers) {
                rows.add(new Row(g, server));
            }
        }
    }

    @Override
    protected void init() {
        rebuildRows();
        int x0 = width / 2 - 150;
        for (int i = scroll; i < rows.size() && i < scroll + VISIBLE_ROWS; i++) {
            Row row = rows.get(i);
            int y = 40 + (i - scroll) * ROW_HEIGHT;
            addRenderableWidget(Button.builder(Component.translatable("syncgroups.manage.remove"), b -> removeServer(row))
                    .bounds(x0 + 300 - 90, y, 90, 18)
                    .build());
        }
        addRenderableWidget(Button.builder(Component.translatable("syncgroups.manage.new"), b -> minecraft.setScreenAndShow(new ServerPickScreen(this)))
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 100, height - 52, 200, 20)
                .build());
    }

    private void removeServer(Row row) {
        try {
            boolean dissolved = SyncGroupsMod.engine().removeServer(row.group(), row.serverKey(), SyncGroupsMod.store());
            if (dissolved) {
                SyncGroupsMod.LOGGER.info("Group '{}' dissolved", row.group().tag);
            }
        } catch (IOException e) {
            SyncGroupsMod.LOGGER.error("Failed to remove server from group", e);
        }
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 16, 0xFFFFFF);
        int x0 = width / 2 - 150;
        for (int i = scroll; i < rows.size() && i < scroll + VISIBLE_ROWS; i++) {
            Row row = rows.get(i);
            int y = 40 + (i - scroll) * ROW_HEIGHT;
            g.text(font, row.group().tag, x0, y + 5, row.group().colorInt());
            g.text(font, row.serverKey(), x0 + 70, y + 5, 0xB0B0B0);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, rows.size() - VISIBLE_ROWS);
        int newScroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY)));
        if (newScroll != scroll) {
            scroll = newScroll;
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
