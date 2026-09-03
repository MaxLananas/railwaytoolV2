package com.bte.railpathtool.design;

import com.bte.railpathtool.track.Agents;
import com.bte.railpathtool.track.TrackModel;
import com.bte.railpathtool.track.TrackType;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseCoralWallFanBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.WallSide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Design « Classique » du tuto BTE France :
 *  - centre NS : dead_bubble_coral_wall_fan facing sud ; centre EW : facing est
 *  - bordures : murets mud_brick_wall (sombre) / andesite_wall (clair), sans colonne
 *    centrale (up=false), murets d'angle aux virages et transitions de diagonale
 *  - side-blocks en bout de ligne : spruce_shelf powered side_chain=center (sombre)
 *    ou iron_door demi-haute (clair), orientés à l'opposé du virage
 *  - remplissage du sol : orange_wool (uni) ou mélange à 5 blocs pondérés (aléatoire)
 * Toutes les décisions suivent exactement les tables des scripts d'origine.
 */
public final class ClassicDesign implements RailDesign {

    @Override
    public void emitCases(TrackModel model, DesignOptions options,
                          Long2ObjectOpenHashMap<BlockState> plan) {
        Palette pal = new Palette(options);
        ColumnWriter writer = new ColumnWriter(model, options, plan);

        List<BlockPos> diags = new ArrayList<>();
        List<BlockPos> ns = new ArrayList<>();
        List<BlockPos> ew = new ArrayList<>();
        for (Map.Entry<Long, TrackType> entry : model.types().entrySet()) {
            BlockPos pos = BlockPos.of(entry.getKey());
            switch (entry.getValue()) {
                case DIAG -> diags.add(pos);
                case NS -> ns.add(pos);
                case EW -> ew.add(pos);
            }
        }

        // 1) Diagonales d'abord (leurs murets d'angle doivent être dessous si conflit).
        for (BlockPos v : diags) {
            emitDiag(model, pal, writer, v);
        }
        // 2) Lignes N-S.
        for (BlockPos v : ns) {
            Agents.LineScan n = Agents.scanNorth(model, v.getX(), v.getY(), v.getZ());
            Agents.LineScan s = Agents.scanSouth(model, v.getX(), v.getY(), v.getZ());
            Agents.LatSide side = Agents.decideNs(n, s);
            writer.column(v.getX(), v.getY(), v.getZ(), pal.coralSouth);
            BlockState lat = side.isWall() ? pal.wallNs : pal.side(side.sideFacing);
            writer.column(v.getX() - 1, v.getY(), v.getZ(), lat);
            writer.column(v.getX() + 1, v.getY(), v.getZ(), lat);
        }
        // 3) Lignes E-O.
        for (BlockPos v : ew) {
            Agents.LineScan o = Agents.scanWest(model, v.getX(), v.getY(), v.getZ());
            Agents.LineScan e = Agents.scanEast(model, v.getX(), v.getY(), v.getZ());
            Agents.LatSide side = Agents.decideEw(o, e);
            writer.column(v.getX(), v.getY(), v.getZ(), pal.coralEast);
            BlockState lat = side.isWall() ? pal.wallEw : pal.side(side.sideFacing);
            writer.column(v.getX(), v.getY(), v.getZ() - 1, lat);
            writer.column(v.getX(), v.getY(), v.getZ() + 1, lat);
        }
    }

    private void emitDiag(TrackModel model, Palette pal, ColumnWriter writer, BlockPos v) {
        int x = v.getX();
        int y = v.getY();
        int z = v.getZ();
        Agents.DiagResult r = Agents.analyseDiag(model, x, y, z);
        if (r.coreType == null) {
            // Indéterminé : signal comme le script (black_wool).
            writer.column(x, y, z, Blocks.BLACK_WOOL.defaultBlockState());
            return;
        }
        writer.column(x, y, z, r.coreType == TrackType.NS ? pal.coralSouth : pal.coralEast);
        boolean swne = r.sense == Agents.DiagSense.SWNE;
        BlockState w1 = swne ? pal.wallNw : pal.wallSw;
        BlockState w2 = swne ? pal.wallSe : pal.wallNe;
        if (r.transition) {
            writer.column(x - 1, y, z, w1);
            writer.column(x + 1, y, z, w2);
            writer.column(x, y, z - 1, w1);
            writer.column(x, y, z + 1, w2);
        } else if (r.coreType == TrackType.NS) {
            writer.column(x - 1, y, z, w1);
            writer.column(x + 1, y, z, w2);
        } else {
            writer.column(x, y, z - 1, w1);
            writer.column(x, y, z + 1, w2);
        }
    }

    // ------------------------------------------------------------------

    /** Palette du thème (murets, side-blocks, coraux). */
    static final class Palette {
        final DesignOptions options;
        final Block wallBlock;

        final BlockState wallNs;
        final BlockState wallEw;
        final BlockState wallNe;
        final BlockState wallNw;
        final BlockState wallSe;
        final BlockState wallSw;
        final BlockState coralSouth;
        final BlockState coralEast;

        Palette(DesignOptions options) {
            this.options = options;
            this.wallBlock = options.theme == DesignOptions.Theme.DARK
                    ? Blocks.MUD_BRICK_WALL : Blocks.ANDESITE_WALL;
            this.wallNs = wall(WallSide.TALL, WallSide.NONE, WallSide.TALL, WallSide.NONE);
            this.wallEw = wall(WallSide.NONE, WallSide.TALL, WallSide.NONE, WallSide.TALL);
            this.wallNe = wall(WallSide.TALL, WallSide.TALL, WallSide.NONE, WallSide.NONE);
            this.wallNw = wall(WallSide.TALL, WallSide.NONE, WallSide.NONE, WallSide.TALL);
            this.wallSe = wall(WallSide.NONE, WallSide.TALL, WallSide.TALL, WallSide.NONE);
            this.wallSw = wall(WallSide.NONE, WallSide.NONE, WallSide.TALL, WallSide.TALL);
            this.coralSouth = Blocks.DEAD_BUBBLE_CORAL_WALL_FAN.defaultBlockState()
                    .setValue(BaseCoralWallFanBlock.FACING, Direction.SOUTH)
                    .setValue(BlockStateProperties.WATERLOGGED, false);
            this.coralEast = Blocks.DEAD_BUBBLE_CORAL_WALL_FAN.defaultBlockState()
                    .setValue(BaseCoralWallFanBlock.FACING, Direction.EAST)
                    .setValue(BlockStateProperties.WATERLOGGED, false);
        }

        private BlockState wall(WallSide n, WallSide e, WallSide s, WallSide w) {
            return wallBlock.defaultBlockState()
                    .setValue(BlockStateProperties.UP, false)
                    .setValue(BlockStateProperties.NORTH_WALL, n)
                    .setValue(BlockStateProperties.EAST_WALL, e)
                    .setValue(BlockStateProperties.SOUTH_WALL, s)
                    .setValue(BlockStateProperties.WEST_WALL, w)
                    .setValue(BlockStateProperties.WATERLOGGED, false);
        }

        /** Side-block orienté selon le thème (étagère sapin / porte fer demi-haute). */
        BlockState side(Agents.Turn facing) {
            Direction dir = switch (facing) {
                case NORTH -> Direction.NORTH;
                case SOUTH -> Direction.SOUTH;
                case EAST -> Direction.EAST;
                case WEST -> Direction.WEST;
                default -> Direction.EAST;
            };
            if (options.theme == DesignOptions.Theme.DARK) {
                Block stam = Blocks.SPRUCE_SHELF;
                BlockState st = stam.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, dir)
                        .setValue(BlockStateProperties.POWERED, true)
                        .setValue(BlockStateProperties.WATERLOGGED, false);
                return setByName(st, "side_chain", "center");
            }
            return Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, dir)
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        }

        /** Pose une propriété par nom (robuste, comme le code éprouvé de la v1). */
        @SuppressWarnings({"unchecked", "rawtypes"})
        static BlockState setByName(BlockState state, String name, String wanted) {
            for (Property property : state.getProperties()) {
                if (property.getName().equals(name)) {
                    for (Object value : property.getPossibleValues()) {
                        if (value.toString().equalsIgnoreCase(wanted)) {
                            return state.setValue(property, (Comparable) value);
                        }
                    }
                }
            }
            return state;
        }
    }

    // ------------------------------------------------------------------

    /**
     * Poseur de colonnes : sol + bloc + air au-dessus — exactement la fonction
     * build_column du script (ne réécrit jamais un bloc de rail déjà posé).
     * Les écritures vont dans le plan ET dans le calque du WorldView, pour que
     * la suite de l'analyse voie les coraux/murets comme un vrai environnement.
     */
    static final class ColumnWriter {
        private final TrackModel model;
        private final DesignOptions options;
        private final Long2ObjectOpenHashMap<BlockState> plan;
        private final com.bte.railpathtool.track.WorldView view;

        ColumnWriter(TrackModel model, DesignOptions options,
                     Long2ObjectOpenHashMap<BlockState> plan) {
            this.model = model;
            this.options = options;
            this.plan = plan;
            this.view = model.view();
        }

        void column(int x, int y, int z, BlockState center) {
            int startY = y + options.baseDy;
            BlockState existing = view.at(x, startY + 1, z);
            if (isProtectedRail(existing)) {
                return;
            }
            put(x, startY + 2, z, Blocks.AIR.defaultBlockState());
            put(x, startY + 1, z, center);
            put(x, startY, z, pickSoil());
        }

        private void put(int x, int y, int z, BlockState state) {
            long key = BlockPos.asLong(x, y, z);
            plan.put(key, state);
            view.put(x, y, z, state);
        }

        private BlockState pickSoil() {
            if (options.fillMode == DesignOptions.FillMode.UNIFORM) {
                return options.uniformBlock;
            }
            int total = 0;
            for (DesignOptions.SoilSlot slot : options.soilSlots) {
                total += Math.max(0, slot.percent);
            }
            if (total <= 0) {
                return options.soilSlots[0].state;
            }
            int roll = ThreadLocalRandom.current().nextInt(total);
            int acc = 0;
            for (DesignOptions.SoilSlot slot : options.soilSlots) {
                acc += Math.max(0, slot.percent);
                if (roll < acc) {
                    return slot.state;
                }
            }
            return options.soilSlots[options.soilSlots.length - 1].state;
        }

        /** Famille « rail déjà construit » (protégée des réécritures, comme le script). */
        static boolean isProtectedRail(BlockState st) {
            Block b = st.getBlock();
            return b == Blocks.MUD_BRICK_WALL || b == Blocks.ANDESITE_WALL
                    || b == Blocks.SPRUCE_SHELF || b == Blocks.IRON_DOOR
                    || b == Blocks.DEAD_BUBBLE_CORAL_WALL_FAN;
        }
    }
}
