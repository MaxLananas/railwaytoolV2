package com.bte.railpathtool;

import com.bte.railpathtool.tools.RailPathTool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RailPathToolMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("bte_railpathtool");

    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("axiom")) {
            LOGGER.warn("[RailPath] Axiom not found - mod is disabled.");
            return;
        }
        try {
            RailPathTool.register();
            LOGGER.info("[RailPath] Tool registered successfully.");
        } catch (Exception e) {
            LOGGER.error("[RailPath] Registration failed: {}", e.getMessage(), e);
        }
    }
}