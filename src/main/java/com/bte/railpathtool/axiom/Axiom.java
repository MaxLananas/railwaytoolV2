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

public final class Axiom {

    private static ToolRegistryService registry;
    private static ToolService tools;
    private static RegionProvider regions;
    private static boolean ready = false;

    private Axiom() {
    }

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

    public static void registerTool(CustomTool tool) {
        registry.register(tool);
    }

    public static BlockHitResult raycastBlock() {
        return tools.raycastBlock();
    }

    public static BlockState getActiveBlock() {
        return tools.getActiveBlock();
    }

    public static void push(BlockRegion region) {
        tools.pushBlockRegionChange(region);
    }

    public static BlockRegion createBlockRegion() {
        return regions.createBlock();
    }

    public static BooleanRegion createBooleanRegion() {
        return regions.createBoolean();
    }

    /**
     * Le verrou de rendu est global : une acquisition en echec (deja pris par
     * un autre outil) ne doit jamais faire tomber le client, on desactive
     * simplement l'apercu.
     */
    public static boolean acquireChunkRenderOverrider(String id) {
        try {
            tools.acquireChunkRenderOverrider(id);
            return true;
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    public static void releaseChunkRenderOverrider(String id) {
        try {
            tools.releaseChunkRenderOverrider(id);
        } catch (RuntimeException | Error ignored) {
            // Verrou deja libere (changement de monde / desactivation).
        }
    }

    public static void setBlockRenderOverride(int x, int y, int z, BlockState state) {
        tools.setBlockRenderOverride(x, y, z, state);
    }
}
