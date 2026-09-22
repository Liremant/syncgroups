package com.craftsync.syncgroups.mixin;

import com.craftsync.syncgroups.SyncGroupsMod;
import com.craftsync.syncgroups.core.Diag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Earliest possible hook: the player just pressed "Join Server" and we know
 * the exact ServerData, before a single packet is sent and long before any
 * data-owning mod (Xaero, Bobby) opens its storage.
 *
 * This exists because ClientPlayConnectionEvents.INIT turned out to be an
 * unreliable source of the target address: getCurrentServer() reads from the
 * connection handler, which may not be populated yet at INIT time, and a null
 * there made the whole join pull a silent no-op.
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void syncgroups$beforeConnect(Screen parent, Minecraft minecraft, ServerAddress address,
                                                 ServerData data, boolean quickPlay, TransferState transferState,
                                                 CallbackInfo ci) {
        try {
            if (data == null) {
                Diag.warn("CONNECT: startConnecting called with null ServerData (address=%s)", address);
                return;
            }
            Diag.info("CONNECT: target='%s' ip='%s' quickPlay=%s — running pre-connect pull",
                    data.name, data.ip, quickPlay);
            SyncGroupsMod.session().onLogin(data.ip, data.name);
        } catch (Throwable t) {
            Diag.error("CONNECT: pre-connect pull threw", t);
        }
    }
}
