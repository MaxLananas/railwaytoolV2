package com.bte.railpathtool;

import com.bte.railpathtool.axiom.Axiom;
import com.bte.railpathtool.dev.EditorShot;
import com.bte.railpathtool.dev.InGameSelfTest;
import com.bte.railpathtool.tools.RailwayTool;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RailPathToolMod implements ClientModInitializer {

    public static final String MOD_ID = "bte_railpathtool";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        InGameSelfTest.install();
        EditorShot.install();
        if (!Axiom.init()) {
            LOGGER.warn("[RailwayTool] Axiom not found, the rail tool is disabled.");
            return;
        }
        Axiom.registerTool(new RailwayTool());
        LOGGER.info("[RailwayTool] BTE rail tool registered with Axiom.");
    }
}
