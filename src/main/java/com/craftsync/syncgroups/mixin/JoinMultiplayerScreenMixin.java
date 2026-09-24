package com.craftsync.syncgroups.mixin;

import com.craftsync.syncgroups.SyncGroupsMod;
import com.craftsync.syncgroups.ui.ServerPickScreen;
import com.craftsync.syncgroups.ui.SyncGroupsScreen;
import net.minecraft.client.Minecraft;
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
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void syncgroups$addButton(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        this.addRenderableWidget(Button.builder(Component.translatable("syncgroups.title"),
                        b -> Minecraft.getInstance().setScreenAndShow(syncgroups$openScreen(self)))
                .bounds(6, self.height - 26, 100, 20)
                .build());
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
