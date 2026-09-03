package com.bte.railpathtool;

import com.bte.railpathtool.axiom.Axiom;
import com.bte.railpathtool.tools.RailwayTool;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RailPathToolMod implements ClientModInitializer {

    public static final String MOD_ID = "bte_railpathtool";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        if (!Axiom.init()) {
            LOGGER.warn("[RailwayTool] Axiom introuvable — l'outil rail est désactivé.");
            return;
        }
        Axiom.registerTool(new RailwayTool());
        LOGGER.info("[RailwayTool] Outil Rail BTE enregistré dans Axiom.");
    }
}
