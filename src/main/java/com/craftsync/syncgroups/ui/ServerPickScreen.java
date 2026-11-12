package com.craftsync.syncgroups.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Spec §5.2: the saved-server list with a checkbox per entry.
 * "Next" is enabled once ≥2 servers are selected. Simple manual scroll —
 * rows that do not fit are reached with the mouse wheel.
 *
 * The selection lives here, not in the widgets: rebuilding the widget list on
 * scroll/resize used to throw it away, so ticking a server, scrolling, and
 * pressing Next silently grouped fewer servers than the player picked.
 */
public class ServerPickScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int VISIBLE_ROWS = 6;

    private final Screen parent;
    private final Screen doneScreen; // where a successful create lands (the server list)
    private final ServerList servers;
    /** Selected saved-server addresses (the checkbox identity). */
    private final Set<String> selected = new LinkedHashSet<>();
    private Button next;
    private int scroll;

    public ServerPickScreen(Screen parent, Screen doneScreen) {
        super(Component.translatable("syncgroups.pick.title"));
        this.parent = parent;
        this.doneScreen = doneScreen;
        this.servers = new ServerList(minecraft);
        this.servers.load();
    }

    @Override
    protected void init() {
        // The list can shrink between visits; keep the window inside it.
        scroll = Math.max(0, Math.min(scroll, Math.max(0, servers.size() - VISIBLE_ROWS)));
        int listWidth = Math.min(300, width - 40);
        int x0 = (width - listWidth) / 2;
        int y0 = 40;
        for (int i = scroll; i < servers.size() && i < scroll + VISIBLE_ROWS; i++) {
            ServerData data = servers.get(i);
            String address = data.ip;
            Checkbox box = Checkbox.builder(serverLabel(data), font)
                    .pos(x0, y0 + (i - scroll) * ROW_HEIGHT)
                    .selected(selected.contains(address))
                    .onValueChange((cb, checked) -> {
                        if (checked) {
                            selected.add(address);
                        } else {
                            selected.remove(address);
                        }
                        refreshNext();
                    })
                    .build();
            addRenderableWidget(box);
        }
        next = Button.builder(Component.translatable("syncgroups.pick.next"), b -> onNext())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build();
        addRenderableWidget(next);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 100, height - 52, 200, 20)
                .build());
        refreshNext();
    }

    private static Component serverLabel(ServerData data) {
        // A saved entry can have no name yet (a hand-edited servers.dat): show the address.
        String name = data.name == null || data.name.isBlank() ? data.ip : data.name;
        return Component.literal(name + " (" + data.ip + ")");
    }

    private void refreshNext() {
        if (next != null) {
            next.active = selected.size() >= 2;
        }
    }

    private void onNext() {
        List<ServerData> picked = new ArrayList<>();
        for (int i = 0; i < servers.size() && picked.size() < selected.size(); i++) {
            ServerData data = servers.get(i);
            if (selected.contains(data.ip)) {
                picked.add(data);
            }
        }
        if (picked.size() < 2) {
            return; // the button is disabled; this only guards a stale click
        }
        minecraft.setScreenAndShow(new CreateGroupScreen(this, doneScreen, picked));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 12, 0xFFFFFF);
        g.centeredText(font, Component.translatable("syncgroups.pick.step"), width / 2, 26, 0x909090);
        g.centeredText(font, Component.translatable("syncgroups.pick.selected", selected.size()),
                width / 2, height - 68, 0x909090);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, servers.size() - VISIBLE_ROWS);
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
