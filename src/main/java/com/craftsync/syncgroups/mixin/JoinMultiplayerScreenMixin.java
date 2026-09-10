package com.craftsync.syncgroups.mixin;

import com.craftsync.syncgroups.ui.SyncGroupsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spec §5.1: "Sync Groups" button on the multiplayer server list screen.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin {

    @Shadow
    protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);

    @Inject(method = "init()V", at = @At("TAIL"))
    private void syncgroups$addButton(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        this.addRenderableWidget(Button.builder(Component.translatable("syncgroups.title"),
                        b -> Minecraft.getInstance().setScreenAndShow(new SyncGroupsScreen(self)))
                .bounds(6, self.height - 26, 100, 20)
                .build());
    }
}
