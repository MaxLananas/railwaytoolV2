package com.bte.railpathtool.track;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lecture du monde avec un calque d'écritures en cours.
 *
 * Pendant la préparation d'un rail, les blocs déjà « posés » dans le plan doivent être
 * visibles par la suite de l'analyse (ex : les coraux servent d'indices d'orientation,
 * comme dans les scripts Lua où le monde est modifié au fur et à mesure). Cette vue
 * donne exactement cela, sans toucher au vrai niveau avant la validation finale.
 */
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

    /** Écrit dans le calque uniquement (pas dans le monde). */
    public void put(int x, int y, int z, BlockState state) {
        overlay.put(BlockPos.asLong(x, y, z), state);
    }

    /** État du monde réel tel qu'avant tout ce build (le calque est ignoré). */
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
