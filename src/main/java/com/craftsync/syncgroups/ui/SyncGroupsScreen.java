package com.craftsync.syncgroups.ui;

import com.craftsync.syncgroups.SyncGroupsMod;
import com.craftsync.syncgroups.core.Group;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
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
    /** Failure text; null while nothing is wrong. */
    private Component error;
    private Button newGroup;
    /**
     * Removal (and, at two servers, the whole dissolve) copies whole folders, so it
     * runs off the render thread — materialising a Bobby/DH group is hundreds of
     * MB and used to freeze the screen until it finished.
     */
    private volatile boolean busy;
    /** False once the player leaves this screen; the worker thread must not touch it then. */
    private volatile boolean open = true;

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
        // Also runs when we come back from the confirm dialog, which had replaced us.
        open = true;
        rebuildRows();
        // Removing rows while scrolled used to leave the window past the end: an
        // empty list with live buttons until the wheel moved.
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - VISIBLE_ROWS)));
        int x0 = width / 2 - 150;
        for (int i = scroll; i < rows.size() && i < scroll + VISIBLE_ROWS; i++) {
            Row row = rows.get(i);
            int y = 40 + (i - scroll) * ROW_HEIGHT;
            Button remove = Button.builder(Component.translatable("syncgroups.manage.remove"), b -> askRemove(row))
                    .bounds(x0 + 300 - 90, y, 90, 18)
                    .build();
            remove.active = !busy;
            addRenderableWidget(remove);
        }
        // "Done" is the screen's primary exit — keep it at the very bottom, the
        // same slot vanilla uses, with the list action stacked above it.
        newGroup = Button.builder(Component.translatable("syncgroups.manage.new"), b -> minecraft.setScreenAndShow(new ServerPickScreen(this, parent)))
                .bounds(width / 2 - 100, height - 52, 200, 20)
                .build();
        addRenderableWidget(newGroup);
        Button done = Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build();
        // A dissolve copies whole folders in the background; every action that could
        // touch the same groups waits until it is done.
        done.active = !busy;
        newGroup.active = !busy;
        addRenderableWidget(done);
    }

    /**
     * Removing a server from a two-server group dissolves it: every remaining link
     * is replaced by a real copy of the shared folder before the group moves to
     * _dissolved. That is the one destructive, slow step, so it asks first.
     */
    private void askRemove(Row row) {
        if (busy) {
            return;
        }
        if (row.group().servers.size() > 2) {
            removeServer(row); // store.update only — no data is copied
            return;
        }
        minecraft.setScreenAndShow(new ConfirmScreen(confirmed -> {
            minecraft.setScreenAndShow(this);
            if (confirmed) {
                removeServer(row);
            }
        }, Component.translatable("syncgroups.manage.confirm.title"),
                Component.translatable("syncgroups.manage.confirm.message",
                        row.group().tag, row.serverKey())));
    }

    private void removeServer(Row row) {
        if (busy) {
            return;
        }
        busy = true;
        error = null;
        rebuildWidgets();
        Thread worker = new Thread(() -> {
            String failure = null;
            try {
                boolean dissolved = SyncGroupsMod.engine()
                        .removeServer(row.group(), row.serverKey(), SyncGroupsMod.store());
                if (dissolved) {
                    SyncGroupsMod.LOGGER.info("Group '{}' dissolved", row.group().tag);
                }
            } catch (IOException | RuntimeException e) {
                // RuntimeException too: DirOps throws DirOps.UncheckedIOException out of a
                // delete walk, and letting that escape would leave the screen stuck on
                // "busy" with nothing in the log.
                SyncGroupsMod.LOGGER.error("Failed to remove server from group", e);
                failure = String.valueOf(e.getMessage());
            }
            String message = failure;
            Minecraft.getInstance().execute(() -> {
                busy = false;
                if (message != null) {
                    error = Component.translatable("syncgroups.manage.removeFailed", message);
                }
                if (open) {
                    rebuildWidgets();
                }
            });
        }, "syncgroups-dissolve");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 16, 0xFFFFFF);
        int x0 = width / 2 - 150;
        for (int i = scroll; i < rows.size() && i < scroll + VISIBLE_ROWS; i++) {
            Row row = rows.get(i);
            int y = 40 + (i - scroll) * ROW_HEIGHT;
            int keyWidth = x0 + 300 - 100 - (x0 + 70); // stop before the Remove button
            g.text(font, clip(font, row.group().tag, 70 - 4), x0, y + 5, row.group().colorInt());
            g.text(font, clip(font, row.serverKey(), keyWidth), x0 + 70, y + 5, 0xB0B0B0);
        }
        if (rows.isEmpty()) {
            g.centeredText(font, Component.translatable("syncgroups.manage.empty"), width / 2, 60, 0x909090);
        }
        if (busy) {
            g.centeredText(font, Component.translatable("syncgroups.manage.busy"),
                    width / 2, height - 72, 0xE0C060);
        } else if (error != null) {
            g.centeredText(font, error, width / 2, height - 72, 0xFF5555);
        }
    }

    /** Fits one line into a pixel budget, with an ellipsis when cut. */
    private static String clip(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && font.width(cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
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
    public void removed() {
        open = false;
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
