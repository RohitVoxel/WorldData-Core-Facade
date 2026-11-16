package com.example.worlddatafacade.fabric;

import com.example.worlddatafacade.ChangeFeedExample;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric entrypoint. WorldData Core is declared as a required dependency in fabric.mod.json, so the Core API
 * is available here. Only register the change feed if your mod really needs push events.
 */
public final class FacadeFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        ChangeFeedExample.register();
    }
}
