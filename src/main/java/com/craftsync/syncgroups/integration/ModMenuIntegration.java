package com.craftsync.syncgroups.integration;

import com.craftsync.syncgroups.ui.ConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/**
 * Mod Menu entrypoint. Only loaded when Mod Menu is installed — it is declared
 * as an optional dependency and referenced solely from fabric.mod.json, so the
 * mod works fine without it.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return (Screen parent) -> new ConfigScreen(parent);
    }
}
