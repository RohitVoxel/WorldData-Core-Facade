package com.example.worlddatafacade.neoforge;

import com.example.worlddatafacade.ChangeFeedExample;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entrypoint. WorldData Core is declared as a required dependency in neoforge.mods.toml and loads
 * first (ordering AFTER), so the Core API is available here. Only register the change feed if your mod
 * really needs push events.
 */
@Mod("worlddata_facade")
public final class FacadeNeoForgeMod {
    public FacadeNeoForgeMod() {
        ChangeFeedExample.register();
    }
}
