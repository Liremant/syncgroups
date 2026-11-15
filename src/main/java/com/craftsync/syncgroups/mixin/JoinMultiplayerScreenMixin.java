package com.craftsync.syncgroups.mixin;

import com.craftsync.syncgroups.SyncGroupsMod;
import com.craftsync.syncgroups.ui.ServerPickScreen;
import com.craftsync.syncgroups.ui.SyncGroupsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spec §5.1: "Sync Groups" button on the multiplayer server list screen.
 * Extends Screen instead of @Shadow-ing addRenderableWidget: Mixin fails to
 * resolve shadows of inherited methods at runtime under Knot (verified in
 * production), while a plain virtual call through the superclass just works.
 *
 * The button joins the vanilla footer instead of taking a fixed corner: its
 * position is re-derived on every (re)layout from the vanilla "Add Server"
 * button, so it always sits in the button rows FancyMenu lays out — which is
 * what makes it reachable to FancyMenu's widget editor like a native button.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    private Button syncgroups$button;

    @Inject(method = "init()V", at = @At("TAIL"))
    private void syncgroups$addButton(CallbackInfo ci) {
        if (SyncGroupsMod.config() != null && !SyncGroupsMod.config().showMenuButton) {
            return; // user hid the button in the mod settings
        }
        Screen self = (Screen) (Object) this;
        syncgroups$button = Button.builder(Component.translatable("syncgroups.title"),
                        b -> Minecraft.getInstance().setScreenAndShow(syncgroups$openScreen(self)))
                .bounds(0, 0, 100, 20)
                .build();
        this.addRenderableWidget(syncgroups$button);
        syncgroups$placeNextToAdd();
    }

    @Inject(method = "repositionElements()V", at = @At("TAIL"))
    private void syncgroups$reposition(CallbackInfo ci) {
        syncgroups$placeNextToAdd();
    }

    /**
     * Anchors the button to the vanilla "Add Server" button: same row, right of
     * it. Derived on every layout pass, so window resizes and FancyMenu's own
     * repositioning of vanilla buttons keep our button consistent with them.
     */
    private void syncgroups$placeNextToAdd() {
        if (syncgroups$button == null) {
            return;
        }
        Button anchor = this.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.getMessage().getString().equals(
                        net.minecraft.client.resources.language.I18n.get("selectServer.add")))
                .findFirst()
                .orElse(null);
        if (anchor == null) {
            // FancyMenu (or another mod) hid the vanilla row: fall back to the
            // bottom-left, above the footer if one exists.
            syncgroups$button.setX(6);
            syncgroups$button.setY(this.height - 26);
            return;
        }
        int x = anchor.getX() + anchor.getWidth() + 4;
        if (x + syncgroups$button.getWidth() > this.width - 4) {
            x = Math.max(4, anchor.getX() - syncgroups$button.getWidth() - 4);
        }
        syncgroups$button.setX(x);
        syncgroups$button.setY(anchor.getY());
    }

    /**
     * With no groups yet there is nothing to manage, so the button leads straight
     * into the creation wizard — the same way "Create New World" is what you get
     * when the world list is empty, instead of an empty list screen.
     */
    private static Screen syncgroups$openScreen(Screen self) {
        boolean empty = SyncGroupsMod.store() == null || SyncGroupsMod.store().all().isEmpty();
        return empty ? new ServerPickScreen(self, self) : new SyncGroupsScreen(self);
    }
}
