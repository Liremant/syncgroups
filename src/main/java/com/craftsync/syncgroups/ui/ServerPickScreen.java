package com.craftsync.syncgroups.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
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
 * "Next" is enabled once ≥2 servers are selected.
 *
 * The list is a vanilla ObjectSelectionList (ServerPickList) in a
 * HeaderAndFooterLayout, so scrolling/clipping/resize come from vanilla instead
 * of the manual wheel-scroll widget rebuild that used to throw the selection
 * away: {@link #selected} lives here, not in the rows.
 */
public class ServerPickScreen extends Screen {

    final Screen parent;
    final Screen doneScreen; // where a successful create lands (the server list)
    final ServerList servers;
    /** Selected saved-server addresses (the checkbox identity). */
    final Set<String> selected = new LinkedHashSet<>();
    /** One-line feedback under the list; null while nothing is wrong. */
    Component hint;

    private HeaderAndFooterLayout layout;
    private ServerPickList list;
    private Button next;

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
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(title, font);

        list = new ServerPickList(minecraft, this, layout.getContentHeight());
        layout.addToContents(list);

        LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();
        next = Button.builder(Component.translatable("syncgroups.pick.next"), b -> onNext()).build();
        footer.addChild(next);
        footer.addChild(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).build());

        layout.visitWidgets(w -> addRenderableWidget(w));
        repositionElements();
        setInitialFocus(list);
    }

    @Override
    protected void repositionElements() {
        if (list != null) {
            list.updateSize(width, layout);
        }
        layout.arrangeElements();
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
        g.centeredText(font, Component.translatable("syncgroups.pick.step"), width / 2,
                layout.getHeaderHeight() - 3, 0x909090);
        Component line = hint != null
                ? hint
                : servers.size() < 2
                        ? Component.translatable("syncgroups.pick.needTwo")
                        : Component.translatable("syncgroups.pick.selected", selected.size());
        g.centeredText(font, line, width / 2, height - layout.getFooterHeight() - 10,
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
