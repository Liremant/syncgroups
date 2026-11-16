package com.craftsync.syncgroups.ui;

import com.craftsync.syncgroups.SyncGroupsMod;
import com.craftsync.syncgroups.core.Group;
import com.craftsync.syncgroups.core.ServerKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec §5.1/§6 management: list existing groups, remove a server from a group
 * (auto-dissolve at ≤1 participant), or dissolve a group outright.
 */
public class SyncGroupsScreen extends Screen {

    private static final int ROW_HEIGHT = 22;
    private static final int VISIBLE_ROWS = 6;

    private final Screen parent;
    /** The mod menu screen, when this screen was opened from it. */
    private Screen backScreen;
    private final List<Row> rows = new ArrayList<>();
    private int scroll;
    /** Failure text; null while nothing is wrong. */
    private Component error;
    private Button newGroup;
    private Button rename;
    private Button recolor;
    private Button remove;
    private Button dissolve;
    private String selectedGroupId;
    /** Server key picked by a single click; null when the whole group (or nothing) is selected. */
    private String selectedServerKey;
    private Selection selection = Selection.NONE;

    private void selectServer(Row row) {
        selectedGroupId = row.group().id;
        selectedServerKey = row.serverKey();
        selection = Selection.SERVER;
    }

    private void selectGroup(Row row) {
        selectedGroupId = row.group().id;
        selectedServerKey = null;
        selection = Selection.GROUP;
    }

    private void clearSelection() {
        selectedGroupId = null;
        selectedServerKey = null;
        selection = Selection.NONE;
    }
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

    /**
     * Selection model: single click picks one row (Remove targets that server);
     * double click on a row picks its whole group (Rename/Recolor/Dissolve target
     * the group, and Remove on a group-selected row is disabled in favour of Dissolve).
     */
    private enum Selection { NONE, SERVER, GROUP }

    public SyncGroupsScreen(Screen parent) {
        this(parent, null);
    }

    /** backScreen — where the explicit "Back" button goes (the mod menu), or null for none. */
    public SyncGroupsScreen(Screen parent, Screen backScreen) {
        super(Component.translatable("syncgroups.manage.title"));
        this.parent = parent;
        this.backScreen = backScreen;
    }

    /** Fresh display names from the saved-server list, keyed by normalized address. */
    private final Map<String, String> freshNames = new LinkedHashMap<>();

    private void rebuildRows() {
        freshNames.clear();
        ServerList list = new ServerList(minecraft);
        list.load();
        for (int i = 0; i < list.size(); i++) {
            ServerData data = list.get(i);
            if (data.ip != null && !data.ip.isBlank()) {
                freshNames.putIfAbsent(ServerKey.normalize(data.ip),
                        data.name == null || data.name.isBlank() ? data.ip : data.name);
            }
        }
        rows.clear();
        for (Group g : SyncGroupsMod.store().all()) {
            for (String server : g.servers) {
                rows.add(new Row(g, server));
            }
        }
        // A removed/dissolved group or server must not leave the footer acting on a ghost.
        if (selectedGroupId != null) {
            boolean groupAlive = rows.stream().anyMatch(r -> r.group().id.equals(selectedGroupId));
            boolean serverAlive = selectedServerKey != null && rows.stream()
                    .anyMatch(r -> r.group().id.equals(selectedGroupId) && r.serverKey().equals(selectedServerKey));
            if (!groupAlive || (selection == Selection.SERVER && !serverAlive)) {
                clearSelection();
            }
        }
    }

    /** First row of the selected group, or null when nothing is selected. */
    private Row selectedRow() {
        return rows.stream().filter(r -> r.group().id.equals(selectedGroupId)).findFirst().orElse(null);
    }

    /** The exact (group, server) row picked by a single click, or null. */
    private Row selectedServerRow() {
        if (selection != Selection.SERVER || selectedServerKey == null) {
            return null;
        }
        return rows.stream().filter(r -> r.group().id.equals(selectedGroupId)
                && r.serverKey().equals(selectedServerKey)).findFirst().orElse(null);
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
        if (width < 480) {
            ax = Math.max(8, width / 2 - 156);
        }
        rename = Button.builder(Component.translatable("syncgroups.manage.rename"), b -> askRename())
                .bounds(ax, height - 76, 100, 20)
                .build();
        recolor = Button.builder(Component.translatable("syncgroups.manage.recolor"), b -> askRecolor())
                .bounds(ax + 104, height - 76, 100, 20)
                .build();
        remove = Button.builder(Component.translatable("syncgroups.manage.remove"), b -> askRemove())
                .bounds(ax + 208, height - 76, 100, 20)
                .build();
        // Whole-group dissolve gets its own clearly-labelled button: the destructive
        // "remove every server and copy everything out" action must not share a slot
        // with per-server removal.
        dissolve = Button.builder(Component.translatable("syncgroups.manage.dissolve"), b -> askDissolve())
                .bounds(ax, height - 100, 312, 20)
                .build();
        addRenderableWidget(dissolve);
        addRenderableWidget(rename);
        addRenderableWidget(recolor);
        addRenderableWidget(remove);
        updateActionButtons();
        // "Done" is the screen's primary exit — keep it at the very bottom, the
        // same slot vanilla uses, with the list action stacked above it.
        newGroup = Button.builder(Component.translatable("syncgroups.manage.new"), b -> minecraft.setScreenAndShow(new ServerPickScreen(this, parent, backScreen)))
                .bounds(width / 2 - 100, height - 52, 200, 20)
                .build();
        addRenderableWidget(newGroup);
        Button done = Button.builder(backScreen != null
                        ? Component.translatable("syncgroups.manage.back")
                        : Component.translatable("gui.done"), b -> onClose())
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
        // Called from init() mid-build and from row selection; buttons may not exist yet.
        if (rename == null || recolor == null || remove == null || dissolve == null) {
            return;
        }
        boolean hasServer = selection == Selection.SERVER && selectedServerRow() != null;
        boolean hasGroup = selection == Selection.GROUP && selectedRow() != null;
        boolean hasAny = hasServer || hasGroup;
        rename.active = hasGroup && !busy;      // group-level: renames the tag
        recolor.active = hasGroup && !busy;     // group-level: recolors the tag
        remove.active = hasServer && !busy;     // server-level: removes this server
        dissolve.active = hasGroup && !busy;    // group-level: dissolves everything
    }

    /**
     * Removing a server from a two-server group dissolves it: every remaining link
     * is replaced by a real copy of the shared folder before the group moves to
     * _dissolved. That is the one destructive, slow step, so it asks first.
     */
    /** Removes the single selected server (dialog when this dissolves a 2-server group). */
    private void askRemove() {
        Row row = selectedServerRow();
        if (row == null || busy) {
            return;
        }
        if (row.group().servers.size() > 2) {
            removeServer(row); // store.update only — no data is copied
            return;
        }
        // Two-server group: removing either server dissolves the whole group. That
        // is inherent to the "one server left = dissolve" rule; the dialog says so.
        minecraft.setScreenAndShow(new ConfirmScreen(confirmed -> {
            minecraft.setScreenAndShow(this);
            if (confirmed) {
                removeServer(row);
            }
        }, Component.translatable("syncgroups.manage.confirm.title"),
                Component.translatable("syncgroups.manage.confirm.message",
                        row.group().tag, displayAddress(row.serverKey()))));
    }

    /**
     * Rename / recolor act on the group of the clicked row, on a copy: if the
     * store write fails the on-screen rows (which the next rebuild re-reads from
     * the store) never disagree with groups.json.
     */
    /**
     * Dissolves the whole group of the selected row: every server is detached with
     * a real copy of the shared data. Heavy (same copy-out as auto-dissolve) and
     * irreversible, so it asks first and runs off-thread.
     */
    private void askDissolve() {
        Row row = selection == Selection.GROUP ? selectedRow() : selectedServerRow();
        if (row == null || busy) {
            return;
        }
        minecraft.setScreenAndShow(new ConfirmScreen(confirmed -> {
            minecraft.setScreenAndShow(this);
            if (!confirmed) {
                return;
            }
            busy = true;
            error = null;
            rebuildWidgets();
            Thread worker = new Thread(() -> {
                String failure = null;
                try {
                    SyncGroupsMod.engine().dissolveGroup(row.group(), SyncGroupsMod.store());
                    SyncGroupsMod.LOGGER.info("Group '{}' dissolved on request", row.group().tag);
                    SyncGroupsMod.toast(Component.translatable("syncgroups.toast.title"),
                            Component.translatable("syncgroups.toast.dissolved", row.group().tag));
                } catch (IOException | RuntimeException e) {
                    SyncGroupsMod.LOGGER.error("Failed to dissolve group", e);
                    failure = String.valueOf(e.getMessage());
                }
                String msg = failure;
                Minecraft.getInstance().execute(() -> {
                    busy = false;
                    if (msg != null) {
                        error = Component.translatable("syncgroups.manage.removeFailed", msg);
                    }
                    if (open) {
                        rebuildWidgets();
                    }
                });
            }, "syncgroups-dissolve");
            worker.setDaemon(true);
            worker.start();
        }, Component.translatable("syncgroups.manage.confirm.title"),
                Component.translatable("syncgroups.manage.confirm.dissolve.message",
                        row.group().tag)));
    }

    private void askRename() {
        Row row = selection == Selection.GROUP ? selectedRow() : selectedServerRow();
        if (row == null || busy) {
            return;
        }
        minecraft.setScreenAndShow(new RenameGroupScreen(this, row.group().tag, newTag -> {
            try {
                Group updated = row.group().copyOf();
                updated.tag = newTag;
                SyncGroupsMod.store().update(updated);
                rebuildRows(); // rows held the stale instance; Remove would revert the rename
                SyncGroupsMod.toast(Component.translatable("syncgroups.toast.title"),
                        Component.translatable("syncgroups.toast.renamed", newTag));
            } catch (IOException e) {
                SyncGroupsMod.LOGGER.error("Failed to rename group", e);
                error = Component.translatable("syncgroups.manage.removeFailed", String.valueOf(e.getMessage()));
            }
        }));
    }

    private void askRecolor() {
        Row row = selection == Selection.GROUP ? selectedRow() : selectedServerRow();
        if (row == null || busy) {
            return;
        }
        minecraft.setScreenAndShow(new RecolorGroupScreen(this, row.group()::colorInt, picked -> {
            try {
                Group updated = row.group().copyOf();
                updated.color = String.format("#%06X", 0xFFFFFF & picked);
                SyncGroupsMod.store().update(updated);
                rebuildRows(); // refresh chips with the new colour
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
            boolean rowSelected = selection == Selection.SERVER
                    && row.group().id.equals(selectedGroupId) && row.serverKey().equals(selectedServerKey);
            boolean groupSelected = selection == Selection.GROUP && row.group().id.equals(selectedGroupId);
            if (rowSelected) {
                g.fill(x0 - 6, y - 1, x0 + 300 - 100, y + ROW_HEIGHT - 2, 0x60FFFFFF);
            } else if (groupSelected) {
                g.fill(x0 - 6, y - 1, x0 + 300 - 100, y + ROW_HEIGHT - 2, 0x30FFFFFF);
            }
            int color = row.group().colorInt();
            g.fill(x0, y + 3, x0 + 8, y + 15, 0xFF000000 | color);
            g.fill(x0, y + 3, x0 + 8, y + 4, 0x66FFFFFF);
            g.text(font, clip(font, row.group().tag, 70 - 16), x0 + 12, y + 5, 0xFF000000 | color);
            // Line 1: the name the user gave the server (from the server-list entry);
            // fall back to the address when no name is known. Line 2: the address.
            String serverName = freshNames.getOrDefault(row.serverKey(),
                    row.group().nameOf(row.serverKey()));
            if (serverName == null || serverName.isBlank()) {
                serverName = row.serverKey();
            }
            int keyWidth = x0 + 300 - 100 - (x0 + 70);
            boolean hideIp = SyncGroupsMod.config() != null && SyncGroupsMod.config().hideServerAddresses;
            String key = hideIp ? "••••••••" : row.serverKey();
            g.text(font, clip(font, serverName, keyWidth), x0 + 70, y + 1, 0xFFFFFFFF);
            g.text(font, clip(font, key, keyWidth), x0 + 70, y + 11, 0xFF808080);
        }
        if (rows.isEmpty()) {
            g.centeredText(font, Component.translatable("syncgroups.manage.empty"), width / 2, 60, 0x909090);
        } else if (selectedGroupId == null && !busy) {
            g.centeredText(font, Component.translatable("syncgroups.manage.selectFirst"),
                    width / 2, 30, 0x909090);
        }
        if (busy) {
            g.centeredText(font, Component.translatable("syncgroups.manage.busy"),
                    width / 2, height - 106, 0xE0C060);
        } else if (error != null) {
            g.centeredText(font, error, width / 2, height - 106, 0xFF5555);
        }
    }

    /** Address as shown to the user — masked when hideServerAddresses is on. */
    static String displayAddress(String serverKey) {
        return SyncGroupsMod.config() != null && SyncGroupsMod.config().hideServerAddresses
                ? "••••••••" : serverKey;
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

    private long lastRowClickAt;
    private Row lastRowClickRow;

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        int x0 = width / 2 - 150;
        double mx = event.x(), my = event.y();
        for (int i = scroll; i < rows.size() && i < scroll + VISIBLE_ROWS; i++) {
            Row row = rows.get(i);
            int y = 40 + (i - scroll) * ROW_HEIGHT;
            if (mx >= x0 && mx < x0 + 300 - 100 && my >= y - 1 && my < y + ROW_HEIGHT - 2) {
                long now = System.currentTimeMillis();
                boolean isDoubleClick = lastRowClickRow == row && now - lastRowClickAt < 400;
                lastRowClickRow = row;
                lastRowClickAt = now;
                if (isDoubleClick) {
                    selectGroup(row);
                } else {
                    selectServer(row);
                }
                updateActionButtons();
                return true;
            }
        }
        boolean handled = super.mouseClicked(event, doubled);
        if (!handled) {
            // Click on empty space: drop the selection. Button clicks (which dispatched
            // above through super) must NOT clear it — the button handler needs it.
            clearSelection();
            updateActionButtons();
        }
        return handled;
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
        // Back goes to the mod menu when we came from there; the wizard parent chain otherwise.
        minecraft.setScreenAndShow(backScreen != null ? backScreen : parent);
    }
}
