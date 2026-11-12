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
 * The selection is kept per address, not per widget: the widgets are rebuilt on
 * every scroll and resize, and a selection made before scrolling used to be
 * silently dropped.
 */
public class ServerPickScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int VISIBLE_ROWS = 6;
    private static final int MIN_SERVERS = 2;

    private final Screen parent;
    private final Screen doneScreen; // where a successful create lands (the server list)
    private final ServerList servers;
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
        // Rows may have disappeared (or the window grown): keep the window valid.
        scroll = Math.max(0, Math.min(scroll, Math.max(0, servers.size() - VISIBLE_ROWS)));
        int listWidth = Math.min(300, width - 40);
        int x0 = (width - listWidth) / 2;
        int y0 = 40;
        for (int i = scroll; i < servers.size() && i < scroll + VISIBLE_ROWS; i++) {
            ServerData data = servers.get(i);
            Checkbox box = Checkbox.builder(Component.literal(data.name + " (" + data.ip + ")"), font)
                    .pos(x0, y0 + (i - scroll) * ROW_HEIGHT)
                    .selected(selected.contains(data.ip))
                    .onValueChange((cb, value) -> {
                        if (value) {
                            selected.add(data.ip);
                        } else {
                            selected.remove(data.ip);
                        }
                        updateNext();
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
        updateNext();
    }

    private void updateNext() {
        if (next != null) {
            next.active = selected.size() >= MIN_SERVERS;
        }
    }

    private void onNext() {
        List<ServerData> picked = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            ServerData data = servers.get(i);
            if (selected.contains(data.ip) && picked.stream().noneMatch(s -> s.ip.equals(data.ip))) {
                picked.add(data);
            }
        }
        if (picked.size() < MIN_SERVERS) {
            return;
        }
        minecraft.setScreenAndShow(new CreateGroupScreen(this, doneScreen, picked));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 12, 0xFFFFFF);
        g.centeredText(font, Component.translatable("syncgroups.pick.step"), width / 2, 26, 0x909090);
        if (selected.size() < MIN_SERVERS) {
            g.centeredText(font, Component.translatable("syncgroups.pick.need"), width / 2, height - 68, 0xE0C060);
        }
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
