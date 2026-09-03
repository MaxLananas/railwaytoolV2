package com.bte.railpathtool.track;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class WorldView {

    private final ClientLevel level;
    private final Long2ObjectOpenHashMap<BlockState> overlay = new Long2ObjectOpenHashMap<>();

    public WorldView(ClientLevel level) {
        this.level = level;
    }

    public BlockState at(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        BlockState o = overlay.get(key);
        if (o != null) {
            return o;
        }
        if (level == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return level.getBlockState(new BlockPos(x, y, z));
    }

    public BlockState at(BlockPos pos) {
        return at(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean isAir(int x, int y, int z) {
        return at(x, y, z).isAir();
    }

    public void put(int x, int y, int z, BlockState state) {
        overlay.put(BlockPos.asLong(x, y, z), state);
    }

    public BlockState initialAt(int x, int y, int z) {
        if (level == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return level.getBlockState(new BlockPos(x, y, z));
    }

    public Long2ObjectOpenHashMap<BlockState> overlay() {
        return overlay;
    }
}
