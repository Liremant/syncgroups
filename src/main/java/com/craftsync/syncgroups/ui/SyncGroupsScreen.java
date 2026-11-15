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
    private Button rename;
    private Button recolor;
    private Button remove;
    /** id of the group acted on by the footer buttons; selected by clicking a row. */
    private String selectedGroupId;
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
        // A removed/dissolved group must not leave the footer acting on a ghost.
        if (rows.stream().noneMatch(r -> r.group().id.equals(selectedGroupId))) {
            selectedGroupId = null;
        }
    }

    /** First row of the selected group, or null when nothing is selected. */
    private Row selectedRow() {
        return rows.stream().filter(r -> r.group().id.equals(selectedGroupId)).findFirst().orElse(null);
    }

    @Override
    protected void init() {
        // Also runs when we come back from the confirm dialog, which had replaced us.
        open = true;
        rebuildRows();
        // Removing rows while scrolled used to leave the window past the end: an
        // empty list with live buttons until the wheel moved.
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - VISIBLE_ROWS)));
        // Rename/Recolor/Remove act on the row picked with a click; all three sit
        // on one footer line so the rows stay readable.
        int ax = width / 2 - 155;
        rename = Button.builder(Component.translatable("syncgroups.manage.rename"), b -> askRename())
                .bounds(ax, height - 76, 100, 20)
                .build();
        recolor = Button.builder(Component.translatable("syncgroups.manage.recolor"), b -> askRecolor())
                .bounds(ax + 104, height - 76, 100, 20)
                .build();
        remove = Button.builder(Component.translatable("syncgroups.manage.remove"), b -> askRemove(selectedRow()))
                .bounds(ax + 208, height - 76, 100, 20)
                .build();
        addRenderableWidget(rename);
        addRenderableWidget(recolor);
        addRenderableWidget(remove);
        updateActionButtons();
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

    /** Keeps the footer actions honest with the selection and the dissolve worker. */
    private void updateActionButtons() {
        boolean hasRow = selectedRow() != null;
        rename.active = hasRow && !busy;
        recolor.active = hasRow && !busy;
        remove.active = hasRow && !busy;
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

    /**
     * Rename / recolor act on the group of the clicked row, on a copy: if the
     * store write fails the on-screen rows (which the next rebuild re-reads from
     * the store) never disagree with groups.json.
     */
    private void askRename() {
        Row row = selectedRow();
        if (row == null || busy) {
            return;
        }
        minecraft.setScreenAndShow(new RenameGroupScreen(this, row.group().tag, newTag -> {
            try {
                Group updated = row.group().copyOf();
                updated.tag = newTag;
                SyncGroupsMod.store().update(updated);
                SyncGroupsMod.toast(Component.translatable("syncgroups.toast.title"),
                        Component.translatable("syncgroups.toast.renamed", newTag));
            } catch (IOException e) {
                SyncGroupsMod.LOGGER.error("Failed to rename group", e);
                error = Component.translatable("syncgroups.manage.removeFailed", String.valueOf(e.getMessage()));
            }
        }));
    }

    private void askRecolor() {
        Row row = selectedRow();
        if (row == null || busy) {
            return;
        }
        minecraft.setScreenAndShow(new RecolorGroupScreen(this, row.group()::colorInt, picked -> {
            try {
                Group updated = row.group().copyOf();
                updated.color = String.format("#%06X", 0xFFFFFF & picked);
                SyncGroupsMod.store().update(updated);
                SyncGroupsMod.toast(Component.translatable("syncgroups.toast.title"),
                        Component.translatable("syncgroups.toast.recolored", updated.tag));
            } catch (IOException e) {
                SyncGroupsMod.LOGGER.error("Failed to recolor group", e);
                error = Component.translatable("syncgroups.manage.removeFailed", String.valueOf(e.getMessage()));
            }
        }));
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
            boolean selected = row.group().id.equals(selectedGroupId);
            if (selected) {
                g.fill(x0 - 6, y - 1, x0 + 300 - 100, y + ROW_HEIGHT - 2, 0x40FFFFFF);
            }
            int color = row.group().colorInt();
            g.fill(x0, y + 3, x0 + 8, y + 15, 0xFF000000 | color);
            g.fill(x0, y + 3, x0 + 8, y + 4, 0x66FFFFFF);
            g.text(font, clip(font, row.group().tag, 70 - 16), x0 + 12, y + 5, 0xFF000000 | color);
            int keyWidth = x0 + 300 - 100 - (x0 + 70);
            g.text(font, clip(font, row.serverKey(), keyWidth), x0 + 70, y + 5, 0xFFB0B0B0);
        }
        if (rows.isEmpty()) {
            g.centeredText(font, Component.translatable("syncgroups.manage.empty"), width / 2, 60, 0x909090);
        } else if (selectedGroupId == null && !busy) {
            g.centeredText(font, Component.translatable("syncgroups.manage.selectFirst"),
                    width / 2, 30, 0x909090);
        }
        if (busy) {
            g.centeredText(font, Component.translatable("syncgroups.manage.busy"),
                    width / 2, height - 86, 0xE0C060);
        } else if (error != null) {
            g.centeredText(font, error, width / 2, height - 86, 0xFF5555);
        }
    }

    /** Fits one line into a pixel budget, with an ellipsis when cut. Shared by the other screens. */
    static String clip(net.minecraft.client.gui.Font font, String text, int maxWidth) {
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
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        int x0 = width / 2 - 150;
        double mx = event.x(), my = event.y();
        for (int i = scroll; i < rows.size() && i < scroll + VISIBLE_ROWS; i++) {
            int y = 40 + (i - scroll) * ROW_HEIGHT;
            if (mx >= x0 && mx < x0 + 300 - 100 && my >= y - 1 && my < y + ROW_HEIGHT - 2) {
                selectedGroupId = rows.get(i).group().id;
                updateActionButtons();
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
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
