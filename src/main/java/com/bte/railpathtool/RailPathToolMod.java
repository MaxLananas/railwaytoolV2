package com.bte.railpathtool;

import com.bte.railpathtool.axiom.Axiom;
import com.bte.railpathtool.tools.RailwayTool;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Railway Tools for Axiom — point d'entrée.
 *
 * Enregistre l'outil "Rail BTE" auprès d'Axiom via l'API officielle pour addons
 * (com.moulberry.axiomclientapi, chargée par ServiceLoader depuis le jar d'Axiom).
 * Si Axiom n'est pas installé, le mod ne fait simplement rien.
 */
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
