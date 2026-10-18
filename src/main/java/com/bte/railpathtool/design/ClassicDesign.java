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
import java.util.Random;

public final class ClassicDesign implements RailDesign {

    @Override
    public void emitCases(TrackModel model, DesignOptions options,
                          Long2ObjectOpenHashMap<BlockState> plan) {
        Palette pal = new Palette(options);
        ColumnWriter writer = new ColumnWriter(model, options, plan);

        List<BlockPos> diags = new ArrayList<>();
        List<BlockPos> ns = new ArrayList<>();
        List<BlockPos> ew = new ArrayList<>();
        for (BlockPos pos : model.orderedTrace()) {
            TrackType t = model.typeOf(pos);
            if (t == null) {
                continue;
            }
            switch (t) {
                case DIAG -> diags.add(pos);
                case NS -> ns.add(pos);
                case EW -> ew.add(pos);
            }
        }

        // Two passes: cores first, then lateral decor. Otherwise a neighbour's
        // side claims a core cell before that core is written and the guard drops
        // the coral, leaving gaps on tight jog lines.
        List<long[]> centers = new ArrayList<>();
        List<long[]> decors = new ArrayList<>();
        List<BlockState> decorStates = new ArrayList<>();
        java.util.Map<Long, BlockState> centerState = new java.util.HashMap<>();

        for (BlockPos v : diags) {
            planDiag(model, pal, v, centers, decors, decorStates, centerState);
        }

        for (BlockPos v : ns) {
            Agents.LineScan n = Agents.scanNorth(model, v.getX(), v.getY(), v.getZ());
            Agents.LineScan s = Agents.scanSouth(model, v.getX(), v.getY(), v.getZ());
            Agents.LatSide side = Agents.decideNs(n, s);
            BlockState lat = side.isWall() ? pal.wallNs : pal.side(side.sideFacing);
            centers.add(new long[]{v.getX(), v.getY(), v.getZ()});
            centerState.put(BlockPos.asLong(v.getX(), v.getY(), v.getZ()), pal.coralSouth);
            decors.add(new long[]{v.getX() - 1, v.getY(), v.getZ()});
            decorStates.add(lat);
            decors.add(new long[]{v.getX() + 1, v.getY(), v.getZ()});
            decorStates.add(lat);
        }

        for (BlockPos v : ew) {
            Agents.LineScan o = Agents.scanWest(model, v.getX(), v.getY(), v.getZ());
            Agents.LineScan e = Agents.scanEast(model, v.getX(), v.getY(), v.getZ());
            Agents.LatSide side = Agents.decideEw(o, e);
            BlockState lat = side.isWall() ? pal.wallEw : pal.side(side.sideFacing);
            centers.add(new long[]{v.getX(), v.getY(), v.getZ()});
            centerState.put(BlockPos.asLong(v.getX(), v.getY(), v.getZ()), pal.coralEast);
            decors.add(new long[]{v.getX(), v.getY(), v.getZ() - 1});
            decorStates.add(lat);
            decors.add(new long[]{v.getX(), v.getY(), v.getZ() + 1});
            decorStates.add(lat);
        }

        for (long[] c : centers) {
            writer.column((int) c[0], (int) c[1], (int) c[2],
                    centerState.get(BlockPos.asLong((int) c[0], (int) c[1], (int) c[2])));
        }
        // Each entry carries its own state, so two decors targeting the same cell
        // no longer overwrite each other's block (corner walls).
        for (int i = 0; i < decors.size(); i++) {
            long[] d = decors.get(i);
            writer.column((int) d[0], (int) d[1], (int) d[2],
                    decorStates.get(i));
        }

        writer.fillSupports(null);
    }

    private void planDiag(TrackModel model, Palette pal, BlockPos v,
                          List<long[]> centers, List<long[]> decors,
                          List<BlockState> decorStates,
                          java.util.Map<Long, BlockState> centerState) {
        int x = v.getX();
        int y = v.getY();
        int z = v.getZ();
        Agents.DiagResult r = Agents.analyseDiag(model, x, y, z);
        if (r.coreType == null) {
            centers.add(new long[]{x, y, z});
            centerState.put(v.asLong(), Blocks.BLACK_WOOL.defaultBlockState());
            return;
        }
        centers.add(new long[]{x, y, z});
        centerState.put(v.asLong(),
                r.coreType == TrackType.NS ? pal.coralSouth : pal.coralEast);
        boolean swne = r.sense == Agents.DiagSense.SWNE;
        BlockState w1 = swne ? pal.wallNw : pal.wallSw;
        BlockState w2 = swne ? pal.wallSe : pal.wallNe;
        int[][] cells;
        BlockState[] states;
        if (r.transition) {
            cells = new int[][]{{x - 1, y, z}, {x + 1, y, z}, {x, y, z - 1}, {x, y, z + 1}};
            states = new BlockState[]{w1, w2, w1, w2};
        } else if (r.coreType == TrackType.NS) {
            cells = new int[][]{{x - 1, y, z}, {x + 1, y, z}};
            states = new BlockState[]{w1, w2};
        } else {
            cells = new int[][]{{x, y, z - 1}, {x, y, z + 1}};
            states = new BlockState[]{w1, w2};
        }
        for (int i = 0; i < cells.length; i++) {
            decors.add(new long[]{cells[i][0], cells[i][1], cells[i][2]});
            decorStates.add(states[i]);
        }
    }

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
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        }

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

    public static final class ColumnWriter {
        /** Seeded soil RNG: reproducible across runs. */
        private static final Random SOIL_RNG = new Random(20240913L);
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
            boolean coreWrite = isRailCore(center);
            for (int yy = startY; yy <= startY + 2; yy++) {
                // Two-speed guard: an existing core is never replaced, and a decor write
                // yields to any existing rail block (rebuilds must not swap walls for
                // panels or leave orphan doors). A core may still take a decor cell at
                // junctions.
                BlockState cur = view.at(x, yy, z);
                if (isRailCore(cur) || (!coreWrite && isRailFamily(cur))) {
                    if (isRailCore(cur)
                            && view.at(x, startY, z).is(Blocks.WHITE_WOOL)) {
                        // The fresh trace wool is consumed anyway: the core already occupies
                        // this cell (shared crossing column), so the wool would otherwise stay
                        // visible forever.
                        put(x, startY, z, Blocks.AIR.defaultBlockState());
                    }
                    return;
                }
            }
            // A core only replaces a decor cell; support soil and headroom air must
            // never erase another line's wall, panel or door.
            if (!isRailFamily(view.at(x, startY + 2, z))) {
                put(x, startY + 2, z, Blocks.AIR.defaultBlockState());
            }
            put(x, startY + 1, z, center);
            if (!isRailFamily(view.at(x, startY, z))) {
                put(x, startY, z, pickSoil());
            }
        }

        /**
         * Soils placed by the designs (support fill and column bases). Orange wool
         * only counts when uniform fill is active; otherwise player-placed orange
         * wool would be treated as a pillar base and filled underneath.
         */
        public static boolean isSupportSoil(BlockState st) {
            return isBaseSoil(st) || st.is(Blocks.ORANGE_WOOL);
        }

        /** Base soils, excluding the uniform-fill orange wool. */
        public static boolean isBaseSoil(BlockState st) {
            return st.is(Blocks.DEEPSLATE) || st.is(Blocks.COBBLED_DEEPSLATE)
                    || st.is(Blocks.PALE_OAK_WOOD)
                    || st.is(Blocks.DEEPSLATE_IRON_ORE)
                    || st.is(Blocks.DEEPSLATE_COAL_ORE)
                    || st.is(Blocks.GRAVEL);
        }

        /**
         * Rail cores of both designs (coral, black pillar, lectern, pale moss):
         * never replaced. Mirrors rail_sim.NATURE_CORES. Decor is not a core and
         * may be taken by a joining core.
         */
        public static boolean isRailCore(BlockState st) {
            return st.is(Blocks.BLACK_WOOL) || st.is(Blocks.LECTERN)
                    || st.is(Blocks.PALE_MOSS_BLOCK)
                    || st.is(Blocks.DEAD_BUBBLE_CORAL_WALL_FAN);
        }

        private void put(int x, int y, int z, BlockState state) {
            long key = BlockPos.asLong(x, y, z);
            plan.put(key, state);
            view.put(x, y, z, state);
        }

        /**
         * Fills under floating rail blocks (blocked descents, leftover teeth,
         * control-point drift): no block placed by this build keeps air below it.
         * Gaps deeper than four blocks are deliberate bridges, left untouched.
         *
         * @param soilOverride soil to use, or null for the theme mix
         */
        void fillSupports(BlockState soilOverride) {
            final int depthMax = 4;
            // Iterate the whole visible overlay, not just this plan: a later trace
            // may have dug the support of a rail placed earlier.
            long[] keys = view.overlay().keySet().toLongArray();
            for (long key : keys) {
                BlockState st = view.overlay().get(key);
                if (st == null || st.isAir()
                        || (!isRailFamily(st) && !isBaseSoil(st)
                                && !(options.fillMode
                                        == DesignOptions.FillMode.UNIFORM
                                        && st.is(Blocks.ORANGE_WOOL)))) {
                    continue;
                }
                int x = BlockPos.getX(key);
                int y = BlockPos.getY(key);
                int z = BlockPos.getZ(key);
                // Measure the real gap first: anything deeper than the cap is a
                // deliberate bridge, and a truncated fill would itself leave floating
                // blocks. Water is not support either: the pillar runs through water to
                // the bed, unless the gap exceeds the cap (bridge).
                int gap = 0;
                while (gap < 64) {
                    BlockState below = view.at(x, y - 1 - gap, z);
                    if (!below.isAir() && !below.is(Blocks.WATER)) {
                        break;
                    }
                    gap++;
                }
                if (gap == 0 || gap > depthMax) {
                    continue;
                }
                for (int yy = y - 1; yy >= y - gap; yy--) {
                    long k = BlockPos.asLong(x, yy, z);
                    BlockState cur = view.at(x, yy, z);
                    if (cur.isAir() || cur.is(Blocks.WATER)) {
                        BlockState fill = soilOverride != null ? soilOverride : pickSoil();
                        plan.put(k, fill);
                        view.put(x, yy, z, fill);
                    }
                }
            }
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
            // Seeded RNG (never ThreadLocalRandom): soil choice stays stable across
            // runs. Geometry does not depend on the draw (every mix block is
            // wool-layable), but stable draws keep parity reproducible block for
            // block.
            int roll = SOIL_RNG.nextInt(total);
            int acc = 0;
            for (DesignOptions.SoilSlot slot : options.soilSlots) {
                acc += Math.max(0, slot.percent);
                if (roll < acc) {
                    return slot.state;
                }
            }
            return options.soilSlots[options.soilSlots.length - 1].state;
        }

        public static boolean isProtectedRail(BlockState st) {
            Block b = st.getBlock();
            return b == Blocks.MUD_BRICK_WALL || b == Blocks.ANDESITE_WALL
                    || b == Blocks.SPRUCE_SHELF || b == Blocks.IRON_DOOR
                    || b == Blocks.DEAD_BUBBLE_CORAL_WALL_FAN
                    || b == Blocks.LECTERN || b == Blocks.PALE_MOSS_CARPET
                    || b == Blocks.PALE_MOSS_BLOCK || b == Blocks.OAK_BUTTON
                    || b == Blocks.BLACK_WOOL;
        }

        public static boolean isRailFamily(BlockState st) {
            return isProtectedRail(st)
                    || st.is(Blocks.LEAF_LITTER) || st.is(Blocks.GRAVEL);
        }
    }
}
