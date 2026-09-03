package com.bte.railpathtool.axiom;

import com.moulberry.axiomclientapi.CustomTool;
import com.moulberry.axiomclientapi.regions.BlockRegion;
import com.moulberry.axiomclientapi.regions.BooleanRegion;
import com.moulberry.axiomclientapi.service.RegionProvider;
import com.moulberry.axiomclientapi.service.ToolRegistryService;
import com.moulberry.axiomclientapi.service.ToolService;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ServiceLoader;

/**
 * Accès centralisé aux services qu'Axiom expose aux addons
 * (découverts par ServiceLoader, comme le veut l'API officielle).
 */
public final class Axiom {

    private static ToolRegistryService registry;
    private static ToolService tools;
    private static RegionProvider regions;
    private static boolean ready = false;

    private Axiom() {
    }

    /** À appeler une fois au démarrage du client. false => Axiom absent, ne rien faire. */
    public static boolean init() {
        if (ready) {
            return true;
        }
        try {
            var reg = ServiceLoader.load(ToolRegistryService.class,
                    Axiom.class.getClassLoader()).findFirst();
            var ts = ServiceLoader.load(ToolService.class,
                    Axiom.class.getClassLoader()).findFirst();
            var rp = ServiceLoader.load(RegionProvider.class,
                    Axiom.class.getClassLoader()).findFirst();
            if (reg.isEmpty() || ts.isEmpty() || rp.isEmpty()) {
                return false;
            }
            registry = reg.get();
            tools = ts.get();
            regions = rp.get();
            ready = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isReady() {
        return ready;
    }

    public static void registerTool(CustomTool tool) {
        registry.register(tool);
    }

    /** Le raycast « éditeur » d'Axiom (passe à travers les overlays type Terrarenderer). */
    public static BlockHitResult raycastBlock() {
        return tools.raycastBlock();
    }

    /** Le bloc actuellement choisi dans la barre "active block" d'Axiom. */
    public static BlockState getActiveBlock() {
        return tools.getActiveBlock();
    }

    /** Envoie un lot de blocs dans le pipeline Axiom (undo natif + sync serveur). */
    public static void push(BlockRegion region) {
        tools.pushBlockRegionChange(region);
    }

    public static BlockRegion createBlockRegion() {
        return regions.createBlock();
    }

    public static BooleanRegion createBooleanRegion() {
        return regions.createBoolean();
    }

    // --- Aperçu fantôme (override de rendu sans toucher au monde) ---

    public static void acquireChunkRenderOverrider(String id) {
        tools.acquireChunkRenderOverrider(id);
    }

    public static void releaseChunkRenderOverrider(String id) {
        tools.releaseChunkRenderOverrider(id);
    }

    public static void setBlockRenderOverride(int x, int y, int z, BlockState state) {
        tools.setBlockRenderOverride(x, y, z, state);
    }
}
