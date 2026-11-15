package com.craftsync.syncgroups.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import com.craftsync.syncgroups.core.Diag;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Spec §5.2: the saved-server list with a checkbox per entry.
 * "Next" is enabled once ≥2 servers are selected.
 *
 * The list is a vanilla ObjectSelectionList (ServerPickList) with explicit
 * bounds, so scrolling/clipping/resize come from vanilla instead of the manual
 * wheel-scroll widget rebuild that used to throw the selection away:
 * {@link #selected} lives here, not in the rows.
 */
public class ServerPickScreen extends Screen {

    final Screen parent;
    final Screen doneScreen; // where a successful create lands (the server list)
    final ServerList servers;
    /** Selected saved-server addresses (the checkbox identity). */
    final Set<String> selected = new LinkedHashSet<>();
    /** One-line feedback under the list; null while nothing is wrong. */
    Component hint;

    private ServerPickList list;
    private Button next;

    /** Header (title + step line) and footer (two stacked buttons) reserved heights. */
    private static final int HEADER = 40;
    private static final int FOOTER = 56;

    public ServerPickScreen(Screen parent, Screen doneScreen) {
        super(Component.translatable("syncgroups.pick.title"));
        this.parent = parent;
        this.doneScreen = doneScreen;
        this.servers = new ServerList(minecraft);
        this.servers.load();
    }

    @Override
    protected void init() {
        // A rebuild (resize, return from the wizard) must not leak the old favicons.
        if (list != null) {
            list.closeIcons();
        }
        list = new ServerPickList(minecraft, this, height - HEADER - FOOTER);

        next = Button.builder(Component.translatable("syncgroups.pick.next"), b -> onNext())
                .bounds(width / 2 - 100, height - FOOTER + 4, 200, 20)
                .build();
        addRenderableWidget(next);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 100, height - FOOTER + 28, 200, 20)
                .build());

        addRenderableWidget(list);
        repositionElements();
        setInitialFocus(list);
        Diag.info("PICK-SCREEN: list at %dx%d, y=%d", width, height - HEADER - FOOTER, HEADER);
    }

    @Override
    protected void repositionElements() {
        if (list != null) {
            // Explicit bounds: x=0, y right under the header. layout.getContentHeight()
            // at construction time proved unreliable on some window shapes (rows
            // rendered under the footer, clicks eaten).
            list.updateSizeAndPosition(width, height - HEADER - FOOTER, HEADER);
        }
        if (next != null) {
            next.setX(width / 2 - 100);
            next.setY(height - FOOTER + 4);
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
        // Never a dead end: with fewer than two picked the button says what is
        // missing instead of silently doing nothing.
        if (picked.size() < 2) {
            hint = Component.translatable("syncgroups.pick.needTwoPicked", picked.size());
            return;
        }
        minecraft.setScreenAndShow(new CreateGroupScreen(this, doneScreen, picked));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Component.translatable("syncgroups.pick.step"), width / 2, 26, 0x909090);
        Component line = hint != null
                ? hint
                : servers.size() < 2
                        ? Component.translatable("syncgroups.pick.needTwo")
                        : Component.translatable("syncgroups.pick.selected", selected.size());
        g.centeredText(font, line, width / 2, height - FOOTER - 14,
                hint != null ? 0xFF5555 : 0x909090);
    }

    @Override
    public void removed() {
        list.closeIcons();
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
