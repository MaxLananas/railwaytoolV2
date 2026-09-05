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
        // Nœuds denses : design minimal (core seul) pour la lisibilité des
        // vrilles/carrefours (photo 3 du retour terrain). Détection telle que
        // sim : voxels croisant les deux axes orthogonaux (ou grappe >= 4),
        // et seulement en grappe (>= 2 autres denses à portée 3) — un virage
        // doux de drift reste complet (murets/litière conservés).
        java.util.Set<BlockPos> junctions = denseJunctions(model);

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

        for (BlockPos v : diags) {
            emitDiag(model, pal, writer, v, junctions);
        }

        for (BlockPos v : ns) {
            Agents.LineScan n = Agents.scanNorth(model, v.getX(), v.getY(), v.getZ());
            Agents.LineScan s = Agents.scanSouth(model, v.getX(), v.getY(), v.getZ());
            Agents.LatSide side = Agents.decideNs(n, s);
            writer.column(v.getX(), v.getY(), v.getZ(), pal.coralSouth);
            if (!junctions.contains(v)) {
                BlockState lat = side.isWall() ? pal.wallNs : pal.side(side.sideFacing);
                if (!decorHitsJunction(junctions, v.getX() - 1, v.getY(), v.getZ())) {
                    writer.column(v.getX() - 1, v.getY(), v.getZ(), lat);
                }
                if (!decorHitsJunction(junctions, v.getX() + 1, v.getY(), v.getZ())) {
                    writer.column(v.getX() + 1, v.getY(), v.getZ(), lat);
                }
            }
        }

        for (BlockPos v : ew) {
            Agents.LineScan o = Agents.scanWest(model, v.getX(), v.getY(), v.getZ());
            Agents.LineScan e = Agents.scanEast(model, v.getX(), v.getY(), v.getZ());
            Agents.LatSide side = Agents.decideEw(o, e);
            writer.column(v.getX(), v.getY(), v.getZ(), pal.coralEast);
            if (!junctions.contains(v)) {
                BlockState lat = side.isWall() ? pal.wallEw : pal.side(side.sideFacing);
                if (!decorHitsJunction(junctions, v.getX(), v.getY(), v.getZ() - 1)) {
                    writer.column(v.getX(), v.getY(), v.getZ() - 1, lat);
                }
                if (!decorHitsJunction(junctions, v.getX(), v.getY(), v.getZ() + 1)) {
                    writer.column(v.getX(), v.getY(), v.getZ() + 1, lat);
                }
            }
        }

        writer.fillSupports(null);
    }

    private void emitDiag(TrackModel model, Palette pal, ColumnWriter writer, BlockPos v,
                          java.util.Set<BlockPos> junctions) {
        int x = v.getX();
        int y = v.getY();
        int z = v.getZ();
        Agents.DiagResult r = Agents.analyseDiag(model, x, y, z);
        if (r.coreType == null) {

            writer.column(x, y, z, Blocks.BLACK_WOOL.defaultBlockState());
            return;
        }
        writer.column(x, y, z, r.coreType == TrackType.NS ? pal.coralSouth : pal.coralEast);
        if (junctions.contains(v)) {
            return;  // nœud dense : core seul, pas de murets
        }
        boolean swne = r.sense == Agents.DiagSense.SWNE;
        BlockState w1 = swne ? pal.wallNw : pal.wallSw;
        BlockState w2 = swne ? pal.wallSe : pal.wallNe;
        if (r.transition) {
            if (!decorHitsJunction(junctions, x - 1, y, z)) {
                writer.column(x - 1, y, z, w1);
            }
            if (!decorHitsJunction(junctions, x + 1, y, z)) {
                writer.column(x + 1, y, z, w2);
            }
            if (!decorHitsJunction(junctions, x, y, z - 1)) {
                writer.column(x, y, z - 1, w1);
            }
            if (!decorHitsJunction(junctions, x, y, z + 1)) {
                writer.column(x, y, z + 1, w2);
            }
        } else if (r.coreType == TrackType.NS) {
            if (!decorHitsJunction(junctions, x - 1, y, z)) {
                writer.column(x - 1, y, z, w1);
            }
            if (!decorHitsJunction(junctions, x + 1, y, z)) {
                writer.column(x + 1, y, z, w2);
            }
        } else {
            if (!decorHitsJunction(junctions, x, y, z - 1)) {
                writer.column(x, y, z - 1, w1);
            }
            if (!decorHitsJunction(junctions, x, y, z + 1)) {
                writer.column(x, y, z + 1, w2);
            }
        }
    }

    /**
     * Ensemble des nœuds denses de la coupe. Règle identique au simulateur :
     * un voxel est « candidat » s'il a des branches sur les DEUX axes (ou >= 4
     * branches). Un candidat ne devient nœud qu'en grappe (>= 2 autres
     * candidats à portée Chebyshev 3 en x/z, dy <= 1) — un virage doux de
     * drift mixe lui aussi les axes et doit garder ses murets.
     */
    static java.util.Set<BlockPos> denseJunctions(TrackModel model) {
        java.util.List<BlockPos> cand = new java.util.ArrayList<>();
        for (BlockPos v : model.orderedTrace()) {
            if (isDenseJunction(model, v.getX(), v.getY(), v.getZ())) {
                cand.add(v);
            }
        }
        // les doublons de voxels (spline) ne comptent pas dans la grappe
        java.util.LinkedHashSet<BlockPos> uniq = new java.util.LinkedHashSet<>(cand);
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        for (BlockPos v : uniq) {
            int nClose = 0;
            for (BlockPos o : uniq) {
                if (o == v || o.equals(v)) {
                    continue;
                }
                if (Math.abs(o.getX() - v.getX()) <= 3
                        && Math.abs(o.getY() - v.getY()) <= 1
                        && Math.abs(o.getZ() - v.getZ()) <= 3) {
                    nClose++;
                }
            }
            if (nClose >= 2) {
                out.add(v);
            }
        }
        return out;
    }

    private static boolean isDenseJunction(TrackModel model, int x, int y, int z) {
        java.util.List<String> nb = model.neighborDirections(x, y, z);
        if (nb.size() <= 2) {
            return false;
        }
        boolean ns = false;
        boolean ew = false;
        for (String d : nb) {
            if (d.equals("N") || d.equals("S")) {
                ns = true;
            }
            if (d.equals("E") || d.equals("O")) {
                ew = true;
            }
        }
        if (ns && ew) {
            return true;
        }
        return nb.size() >= 4;
    }

    /** Vrai si la cellule de décor cible est collée au core d'un nœud. */
    static boolean decorHitsJunction(java.util.Set<BlockPos> junctions,
                                     int cx, int cy, int cz) {
        for (BlockPos j : junctions) {
            if (Math.abs(j.getX() - cx) <= 1
                    && Math.abs(j.getY() - cy) <= 1
                    && Math.abs(j.getZ() - cz) <= 1) {
                return true;
            }
        }
        return false;
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
            long[] keys = plan.keySet().toLongArray();
            for (long key : keys) {
                BlockState st = plan.get(key);
                if (st == null || st.isAir()) {
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
                    if (!plan.containsKey(k)) {
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
                    || b == Blocks.PALE_MOSS_BLOCK || b == Blocks.OAK_BUTTON;
        }

        public static boolean isRailFamily(BlockState st) {
            return isProtectedRail(st)
                    || st.is(Blocks.LEAF_LITTER) || st.is(Blocks.GRAVEL);
        }
    }
}
