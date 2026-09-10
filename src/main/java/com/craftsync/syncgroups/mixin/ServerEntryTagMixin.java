package com.craftsync.syncgroups.mixin;

import com.craftsync.syncgroups.SyncGroupsMod;
import com.craftsync.syncgroups.core.Group;
import com.craftsync.syncgroups.core.ServerKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spec §5.4: draw the group tag in the top-right corner of a server entry.
 * One server = one group (invariant in GroupsStore), so at most one tag.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$OnlineServerEntry")
public class ServerEntryTagMixin {

    @Shadow(remap = false)
    @Final
    private ServerData serverData;

    @Inject(method = "extractContent", at = @At("TAIL"))
    private void syncgroups$drawTag(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovering,
                                    float partialTick, CallbackInfo ci) {
        Group group = SyncGroupsMod.store().byServer(ServerKey.normalize(serverData.ip)).orElse(null);
        if (group == null) {
            return;
        }
        // Entry geometry: vanilla draws the name line at contentY+1, starting at
        // contentX+35 (icon 32 + 3 padding); the player counter sits at the right
        // edge (contentRight-10, contentY+10) — so the tag goes right after the
        // name on the first line, never touching the counter.
        ServerSelectionList.Entry entry = (ServerSelectionList.Entry) (Object) this;
        var font = Minecraft.getInstance().font;
        int textWidth = font.width(group.tag);
        int x = entry.getContentX() + 35 + font.width(serverData.name) + 6;
        int y = entry.getContentY() + 1;
        g.fill(x - 3, y - 2, x + textWidth + 3, y + 10, 0x90101010);
        g.text(font, group.tag, x, y, group.colorInt());
    }
}
