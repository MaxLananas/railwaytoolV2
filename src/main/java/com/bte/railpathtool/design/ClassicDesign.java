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

        // DEUX passes : tous les cores (corails) d'abord, puis tous les
        // décors latéraux. Sinon le côté d'un voisin s'écrit dans la case du
        // core AVANT lui et la garde « colonne intacte » fait perdre le
        // corail — c'est le « presque plus de rail » des lignes à dérive
        // (jogs latéraux rapprochés) des captures.
        List<long[]> centers = new ArrayList<>();
        List<long[]> decors = new ArrayList<>();
        java.util.Map<Long, BlockState> centerState = new java.util.HashMap<>();
        java.util.Map<Long, BlockState> decorState = new java.util.HashMap<>();

        for (BlockPos v : diags) {
            planDiag(model, pal, v, centers, decors, centerState, decorState);
        }

        for (BlockPos v : ns) {
            Agents.LineScan n = Agents.scanNorth(model, v.getX(), v.getY(), v.getZ());
            Agents.LineScan s = Agents.scanSouth(model, v.getX(), v.getY(), v.getZ());
            Agents.LatSide side = Agents.decideNs(n, s);
            BlockState lat = side.isWall() ? pal.wallNs : pal.side(side.sideFacing);
            centers.add(new long[]{v.getX(), v.getY(), v.getZ()});
            centerState.put(BlockPos.asLong(v.getX(), v.getY(), v.getZ()), pal.coralSouth);
            decors.add(new long[]{v.getX() - 1, v.getY(), v.getZ()});
            decorState.put(BlockPos.asLong(v.getX() - 1, v.getY(), v.getZ()), lat);
            decors.add(new long[]{v.getX() + 1, v.getY(), v.getZ()});
            decorState.put(BlockPos.asLong(v.getX() + 1, v.getY(), v.getZ()), lat);
        }

        for (BlockPos v : ew) {
            Agents.LineScan o = Agents.scanWest(model, v.getX(), v.getY(), v.getZ());
            Agents.LineScan e = Agents.scanEast(model, v.getX(), v.getY(), v.getZ());
            Agents.LatSide side = Agents.decideEw(o, e);
            BlockState lat = side.isWall() ? pal.wallEw : pal.side(side.sideFacing);
            centers.add(new long[]{v.getX(), v.getY(), v.getZ()});
            centerState.put(BlockPos.asLong(v.getX(), v.getY(), v.getZ()), pal.coralEast);
            decors.add(new long[]{v.getX(), v.getY(), v.getZ() - 1});
            decorState.put(BlockPos.asLong(v.getX(), v.getY(), v.getZ() - 1), lat);
            decors.add(new long[]{v.getX(), v.getY(), v.getZ() + 1});
            decorState.put(BlockPos.asLong(v.getX(), v.getY(), v.getZ() + 1), lat);
        }

        for (long[] c : centers) {
            writer.column((int) c[0], (int) c[1], (int) c[2],
                    centerState.get(BlockPos.asLong((int) c[0], (int) c[1], (int) c[2])));
        }
        for (long[] d : decors) {
            writer.column((int) d[0], (int) d[1], (int) d[2],
                    decorState.get(BlockPos.asLong((int) d[0], (int) d[1], (int) d[2])));
        }

        writer.fillSupports(null);
    }

    private void planDiag(TrackModel model, Palette pal, BlockPos v,
                          List<long[]> centers, List<long[]> decors,
                          java.util.Map<Long, BlockState> centerState,
                          java.util.Map<Long, BlockState> decorState) {
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
            decorState.put(BlockPos.asLong(cells[i][0], cells[i][1], cells[i][2]), states[i]);
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
        private final TrackModel model;
        private final DesignOptions options;
        private static final boolean DBG = Boolean.getBoolean("railparity.debug");
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
            for (int yy = startY; yy <= startY + 2; yy++) {
                // Garde anti-écrasement anti-valse : la cellule d'un bloc rail
                // (y compris gravier/litière posés par un autre voxel ou un
                // ancien build) n'est JAMAIS réécrite — sinon on casse le
                // rail d'un voisin sur les traverses et les nœuds (photo 1).
                if (isRailFamily(view.at(x, yy, z))) {
                    if (DBG) {
                        System.out.println("[COLREFUSE] " + x + "," + yy + "," + z
                                + " center=" + net.minecraft.core.registries
                                .BuiltInRegistries.BLOCK.getKey(center.getBlock())
                                + " cur=" + net.minecraft.core.registries
                                .BuiltInRegistries.BLOCK.getKey(
                                        view.at(x, yy, z).getBlock()));
                    }
                    return;
                }
            }
            if (center.getBlock() == Blocks.IRON_DOOR) {
                // Thème clair : panneau de porte basse uniquement (moitié lower),
                // fidèle au script — pas de porte complète à 2 blocs.
                put(x, startY + 2, z, Blocks.AIR.defaultBlockState());
                put(x, startY + 1, z, center);
                put(x, startY, z, pickSoil());
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

        /**
         * Comble sous les blocs de rail qui flottent (descente bloquee, dents
         * residuelles, derive des points de controle) : aucun bloc pose par ce
         * build ne garde de l'air directement sous lui — contrat du script,
         * la voie repose toujours sur le sol (remplissage max 6 blocs).
         *
         * @param soilOverride sol a utiliser (null = melange du theme courant)
         */
        void fillSupports(BlockState soilOverride) {
            final int depthMax = 4;
            // Comme le script (qui parcourt tous les blocs de rail du monde) :
            // on itère tout le rail visible, pas seulement le plan courant —
            // une trace suivante a pu recreuser le support d'un rail que ce
            // build vient de poser (dug de la même passe).
            long[] keys = view.overlay().keySet().toLongArray();
            for (long key : keys) {
                BlockState st = view.overlay().get(key);
                if (st == null || st.isAir() || !isRailFamily(st)) {
                    continue;
                }
                int x = BlockPos.getX(key);
                int y = BlockPos.getY(key);
                int z = BlockPos.getZ(key);
                // Mesure d'abord le trou réel sous le bloc : un gap plus profond
                // que depthMax est un pont/viaduc volontaire — ne RIEN faire
                // (un remplissage tronqué laisserait lui-même 1-3 blocs d'air,
                // exactement le bug des fragments flottants signalé).
                int gap = 0;
                while (view.isAir(x, y - 1 - gap, z) && gap < 64) {
                    gap++;
                }
                if (gap == 0 || gap > depthMax) {
                    continue;
                }
                for (int yy = y - 1; yy >= y - gap; yy--) {
                    long k = BlockPos.asLong(x, yy, z);
                    if (view.isAir(x, yy, z)) {
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
