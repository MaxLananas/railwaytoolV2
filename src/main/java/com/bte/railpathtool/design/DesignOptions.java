package com.bte.railpathtool.design;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Options partagées des deux designs (identiques aux scripts du tuto BTE France).
 */
public class DesignOptions {

    public enum Style {CLASSIC, NATURE}

    /** 0 = sombre (murets mud-brick + étagères sapin), 1 = clair (andésite + portes fer). */
    public enum Theme {DARK, LIGHT}

    /** Comment le sol sous le rail est rempli (design classique). */
    public enum FillMode {UNIFORM, RANDOM}

    public Style style = Style.CLASSIC;
    public Theme theme = Theme.DARK;
    public FillMode fillMode = FillMode.RANDOM;
    /** 0 = rail en surface, -1 = rail enterré d'un bloc (base_dy du script). */
    public int baseDy = 0;

    /** Bloc unique en mode UNIFORM (par défaut orange_wool, comme le tuto). */
    public BlockState uniformBlock = Blocks.ORANGE_WOOL.defaultBlockState();

    /** Les 5 blocs du mélange en mode RANDOM, avec leur part (%). */
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
