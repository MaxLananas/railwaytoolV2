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

    public static boolean isReady() {
        return ready;
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
