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
        // Chaque entrée porte SON état : deux décors ciblant la même cellule
        // ne partagent plus un slot d'une map par position (sinon le dernier
        // état mis — EW après NS — remplacait aussi l'écriture du premier :
        // wall_ns devenait wall_eo aux coins — bug « palissade tournée »).
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
            if (DBG) {
                System.out.println("[COL] " + x + "," + y + "," + z
                        + " center=" + net.minecraft.core.registries
                        .BuiltInRegistries.BLOCK.getKey(center.getBlock())
                        + " cur59=" + net.minecraft.core.registries
                        .BuiltInRegistries.BLOCK.getKey(
                                view.at(x, y + 1, z).getBlock()).getPath());
            }
            boolean coreWrite = isRailCore(center);
            for (int yy = startY; yy <= startY + 2; yy++) {
                // Garde anti-valse à deux vitesses : (1) un core existant
                // n'est JAMAIS écrasé ; (2) une pose de DÉCOR cède devant
                // n'importe quel bloc de rail existant — sinon un rebuild
                // réécrirait les murets en panneaux/portes (valse des
                // décors, portes orphelines). Mais une pose de CORE reprend
                // la case d'un décor : les jonctions denses ne perdent
                // jamais leur rail visible.
                BlockState cur = view.at(x, yy, z);
                if (isRailCore(cur) || (!coreWrite && isRailFamily(cur))) {
                    if (DBG) {
                        System.out.println("[COLREFUSE] " + x + "," + yy + "," + z
                                + " center=" + net.minecraft.core.registries
                                .BuiltInRegistries.BLOCK.getKey(center.getBlock())
                                + " cur=" + net.minecraft.core.registries
                                .BuiltInRegistries.BLOCK.getKey(
                                        view.at(x, yy, z).getBlock()));
                    }
                    if (isRailCore(cur)
                            && view.at(x, startY, z).is(Blocks.WHITE_WOOL)) {
                        // La laine fraîche du voxel est consommée malgré
                        // tout : son core est précisément celui qui occupe
                        // la case (croisement = colonne partagée) — sinon
                        // elle resterait visible à jamais sans rail.
                        put(x, startY, z, Blocks.AIR.defaultBlockState());
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

        /**
         * Cores de voie des deux designs (corail/pilier noir/pupitre/mousse) :
         * JAMAIS écrasés, même par une trace suivante. Miroir de
         * rail_sim.NATURE_CORES — le décor (murets, portes, panneaux) n'en
         * fait pas partie : il s'incline devant un core de trace voisine
         * (jonctions denses — le rail visible passe avant le décor).
         */
        /** Sols de support posés par les designs (remplissage + bases). */
        static boolean isSupportSoil(BlockState st) {
            return st.is(Blocks.DEEPSLATE) || st.is(Blocks.COBBLED_DEEPSLATE)
                    || st.is(Blocks.PALE_OAK_WOOD)
                    || st.is(Blocks.DEEPSLATE_IRON_ORE)
                    || st.is(Blocks.DEEPSLATE_COAL_ORE)
                    || st.is(Blocks.GRAVEL)
                    || st.is(Blocks.ORANGE_WOOL);   // remplissage uniforme
        }

        static boolean isRailCore(BlockState st) {
            if (st.is(Blocks.BLACK_WOOL) || st.is(Blocks.LECTERN)
                    || st.is(Blocks.PALE_MOSS_BLOCK)
                    || st.is(Blocks.DEAD_BUBBLE_CORAL_WALL_FAN)) {
                return true;
            }
            return false;
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
                if (st == null || st.isAir()
                        || (!isRailFamily(st) && !isSupportSoil(st))) {
                    continue;
                }
                int x = BlockPos.getX(key);
                int y = BlockPos.getY(key);
                int z = BlockPos.getZ(key);
                // Mesure d'abord le trou réel sous le bloc : un gap plus profond
                // que depthMax est un pont/viaduc volontaire — ne RIEN faire
                // (un remplissage tronqué laisserait lui-même 1-3 blocs d'air,
                // exactement le bug des fragments flottants signalé).
                // L'eau n'est pas un support : un rail au-dessus d'une mare
                // flotte — le pilier traverse l'eau jusqu'au lit (<= depthMax
                // blocs, sinon c'est un pont legitime et on ne touche a rien).
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
