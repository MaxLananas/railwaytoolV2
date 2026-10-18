package com.bte.railpathtool.design;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class DesignOptions {

    public enum Style {CLASSIC, NATURE}

    public enum Theme {DARK, LIGHT}

    public enum FillMode {UNIFORM, RANDOM}

    public Style style = Style.CLASSIC;
    public Theme theme = Theme.DARK;
    public FillMode fillMode = FillMode.RANDOM;

    public int baseDy = 0;

    public BlockState uniformBlock = Blocks.ORANGE_WOOL.defaultBlockState();

    public final SoilSlot[] soilSlots = {
            new SoilSlot(Blocks.DEEPSLATE.defaultBlockState(), 45),
            new SoilSlot(Blocks.COBBLED_DEEPSLATE.defaultBlockState(), 40),
            new SoilSlot(Blocks.PALE_OAK_WOOD.defaultBlockState(), 10),
            new SoilSlot(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), 4),
            new SoilSlot(Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(), 2),
    };

    public static final class SoilSlot {
        public BlockState state;
        public int percent;

        public SoilSlot(BlockState state, int percent) {
            this.state = state;
            this.percent = percent;
        }
    }
}
