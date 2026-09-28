package com.craftsync.syncgroups.ui;

import com.craftsync.syncgroups.SyncGroupsMod;
import com.craftsync.syncgroups.integration.PathsConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Mod Menu settings screen. Vanilla widgets only — no cloth-config dependency
 * for what amounts to three controls. Edits apply to the live config object and
 * are written to disk on close.
 */
public class ConfigScreen extends Screen {

    private final Screen parent;
    /** Result of the last mode switch, shown under the button. */
    private Component status;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("syncgroups.config.title"));
        this.parent = parent;
    }

    private PathsConfig cfg() {
        return SyncGroupsMod.config();
    }

    @Override
    protected void init() {
        PathsConfig cfg = cfg();
        int cx = width / 2 - 155;
        int y = 44;
        int w = 310;
        int h = 20;
        int gap = 26;

        addRenderableWidget(Button.builder(tagsLabel(cfg), b -> {
            cfg.showGroupTags = !cfg.showGroupTags;
            b.setMessage(tagsLabel(cfg));
            SyncGroupsMod.saveConfig();
        }).tooltip(tip("syncgroups.config.tags.tip")).bounds(cx, y, w, h).build());
        y += gap;

        addRenderableWidget(Button.builder(modeLabel(cfg), b -> {
            String outcome = SyncGroupsMod.applySyncMode(!cfg.symlinkMode());
            b.setMessage(modeLabel(cfg));
            // Say what actually happened: folders linked now, or the switch is
            // recorded and applied later (next launch / next join).
            status = Component.translatable("syncgroups.config.mode." + outcome);
        }).tooltip(tip("syncgroups.config.mode.tip")).bounds(cx, y, w, h).build());
        y += gap;

        addRenderableWidget(Button.builder(pushLabel(cfg), b -> {
            cfg.periodicPushMinutes = switch (cfg.periodicPushMinutes) {
                case 1 -> 2;
                case 2 -> 5;
                case 5 -> 10;
                case 10 -> 30;
                default -> 1;
            };
            b.setMessage(pushLabel(cfg));
            SyncGroupsMod.saveConfig();
        }).tooltip(tip("syncgroups.config.push.tip")).bounds(cx, y, w, h).build());
        y += gap + 8;

        addRenderableWidget(Button.builder(Component.translatable("syncgroups.config.openFolder"), b -> {
            try {
                net.minecraft.util.Util.getPlatform().openPath(
                        net.minecraft.client.Minecraft.getInstance().gameDirectory
                                .toPath().resolve("config").resolve("syncgroups"));
            } catch (Exception e) {
                SyncGroupsMod.LOGGER.warn("Could not open the syncgroups folder", e);
            }
        }).tooltip(tip("syncgroups.config.openFolder.tip")).bounds(cx, y, w, h).build());
        y += gap;

        addRenderableWidget(Button.builder(Component.translatable("syncgroups.config.manage"), b ->
                        minecraft.setScreenAndShow(new SyncGroupsScreen(this)))
                .tooltip(tip("syncgroups.config.manage.tip")).bounds(cx, y, w, h).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());
    }

    /** Hover text for a settings button: the label stays short, the meaning lives here. */
    private static Tooltip tip(String key) {
        return Tooltip.create(Component.translatable(key));
    }

    private static Component tagsLabel(PathsConfig cfg) {
        return Component.translatable("syncgroups.config.tags",
                onOff(cfg.showGroupTags));
    }

    private static Component modeLabel(PathsConfig cfg) {
        return Component.translatable("syncgroups.config.mode",
                Component.translatable(cfg.symlinkMode()
                        ? "syncgroups.config.mode.symlink" : "syncgroups.config.mode.copy"));
    }

    private static Component pushLabel(PathsConfig cfg) {
        return Component.translatable("syncgroups.config.push", cfg.periodicPushMinutes);
    }

    private static Component onOff(boolean on) {
        return Component.translatable(on ? "syncgroups.config.on" : "syncgroups.config.off");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 16, 0xFFFFFF);
        if (status != null) {
            // Sits in the gap between the push button (ends at 116) and the
            // folder button (starts at 130), so it never covers a control.
            g.centeredText(font, status, width / 2, 118, 0xE0C060);
        }
        g.centeredText(font, Component.translatable("syncgroups.config.hint"), width / 2, height - 46, 0x909090);
    }

    @Override
    public void onClose() {
        SyncGroupsMod.saveConfig();
        minecraft.setScreenAndShow(parent);
    }
}
