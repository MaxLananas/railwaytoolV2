package com.bte.railpathtool.design;

import com.bte.railpathtool.track.Agents;
import com.bte.railpathtool.track.TrackModel;
import com.bte.railpathtool.track.TrackType;
import com.bte.railpathtool.track.WorldView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class NatureDesign implements RailDesign {

    private static final Object[][] LEAF_NS = {
            {new String[]{"N", "S"}, 2, "north", 2, "south"},
            {new String[]{"N", "SE"}, 3, "south", 2, "south"},
            {new String[]{"N", "SO"}, 2, "north", 3, "east"},
            {new String[]{"S", "NE"}, 3, "west", 2, "south"},
            {new String[]{"S", "NO"}, 2, "north", 3, "north"},
            {new String[]{"NE", "SO"}, 3, "west", 3, "east"},
            {new String[]{"NO", "SE"}, 3, "south", 3, "north"},
    };
    private static final Object[][] LEAF_EW = {
            {new String[]{"O", "E"}, 2, "west", 2, "east"},
            {new String[]{"E", "NO"}, 3, "south", 2, "east"},
            {new String[]{"O", "NE"}, 3, "east", 2, "east"},
            {new String[]{"O", "SE"}, 2, "west", 3, "north"},
            {new String[]{"E", "SO"}, 2, "west", 3, "west"},
            {new String[]{"NE", "SO"}, 3, "east", 3, "west"},
            {new String[]{"NO", "SE"}, 3, "south", 3, "north"},
    };

    @Override
    public void emitCases(TrackModel model, DesignOptions options,
                          Long2ObjectOpenHashMap<BlockState> plan) {
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
        Writer w = new Writer(model.view(), plan);
        for (BlockPos v : diags) {
            Agents.DiagResult r = Agents.analyseDiag(model, v.getX(), v.getY(), v.getZ());
            TrackType t = r.coreType != TrackType.EW ? TrackType.NS : TrackType.EW;
            emitBlock(model, w, v, t);
        }
        for (BlockPos v : ns) {
            emitBlock(model, w, v, TrackType.NS);
        }
        for (BlockPos v : ew) {
            emitBlock(model, w, v, TrackType.EW);
        }
        w.fillSupports();
    }

    private void emitBlock(TrackModel model, Writer w, BlockPos v, TrackType t) {
        int x = v.getX();
        int y = v.getY();
        int z = v.getZ();
        boolean dirNS = t == TrackType.NS;

        int[][] mossOffsets = dirNS
                ? new int[][]{{0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}
                : new int[][]{{1, 0}, {-1, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        int[][] ortho = dirNS ? new int[][]{{1, 0}, {-1, 0}} : new int[][]{{0, 1}, {0, -1}};
        int[][] crossQuads = dirNS ? new int[][]{{1, -1}, {-1, -1}, {1, 1}, {-1, 1}} : new int[0][];
        int[][] leafPos = dirNS ? new int[][]{{1, 0}, {-1, 0}} : new int[][]{{0, -1}, {0, 1}};
        Direction facing = dirNS ? Direction.NORTH : Direction.EAST;

        boolean isMoss = false;
        for (int[] off : mossOffsets) {
            if (model.typeAt(x + off[0], y - 1, z + off[1]) == t) {
                isMoss = true;
                break;
            }
        }
        if (!isMoss) {
            w.put(x, y, z, Blocks.LECTERN.defaultBlockState()
                    .setValue(LecternBlock.FACING, facing)
                    .setValue(LecternBlock.HAS_BOOK, false));
            w.put(x, y + 1, z, Blocks.PALE_MOSS_CARPET.defaultBlockState());
        } else {
            w.put(x, y, z, Blocks.PALE_MOSS_BLOCK.defaultBlockState());
            w.put(x, y + 1, z, Blocks.OAK_BUTTON.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.POWERED, true));
        }

        for (int[] off : ortho) {
            w.put(x + off[0], y, z + off[1], Blocks.GRAVEL.defaultBlockState());
        }

        if (dirNS) {
            for (int[] off : crossQuads) {
                for (int dy : TrackModel.DY_TOLERANCE) {
                    if (model.typeAt(x + off[0], y + dy, z + off[1]) == TrackType.EW) {
                        w.put(x, y + dy, z + off[1], Blocks.GRAVEL.defaultBlockState());
                        w.put(x, y + dy + 1, z + off[1],
                                leafLitter(3, crossFacing(off[0], off[1])));
                    }
                }
            }
        }

        List<String> nb = model.neighborDirections(x, y, z);
        int a1;
        String f1;
        int a2;
        String f2;
        if (nb.size() >= 2) {
            String d1 = nb.get(0);
            String d2 = nb.get(1);
            // Derive en coin (le troncon glisse d'1 en X ou Z) : la paire
            // droite (N,S)/(E,O) masque le voisin diagonal — on le substitue
            // pour rendre la litiere a 3 segments du script dans les coins.
            if ((d1.equals("N") && d2.equals("S")) || (d1.equals("S") && d2.equals("N"))
                    || (d1.equals("E") && d2.equals("O")) || (d1.equals("O") && d2.equals("E"))) {
                for (String d : nb) {
                    if (d.equals("NE") || d.equals("NO") || d.equals("SE") || d.equals("SO")) {
                        d2 = d;
                        break;
                    }
                }
            }
            Object[] lv = leafValues(dirNS, d1, d2);
            a1 = (Integer) lv[0];
            f1 = (String) lv[1];
            a2 = (Integer) lv[2];
            f2 = (String) lv[3];
        } else if (dirNS) {
            a1 = 2; f1 = "north"; a2 = 2; f2 = "south";
        } else {
            a1 = 2; f1 = "west"; a2 = 2; f2 = "east";
        }
        w.put(x + leafPos[0][0], y + 1, z + leafPos[0][1], leafLitter(a1, f1));
        w.put(x + leafPos[1][0], y + 1, z + leafPos[1][1], leafLitter(a2, f2));
    }

    private static final java.util.Set<String> CARD_N = java.util.Set.of("N", "NE", "NO");
    private static final java.util.Set<String> CARD_S = java.util.Set.of("S", "SE", "SO");
    private static final java.util.Set<String> CARD_E = java.util.Set.of("E", "NE", "SE");
    private static final java.util.Set<String> CARD_O = java.util.Set.of("O", "NO", "SO");

    /** Valeurs {a1, f1, a2, f2} : script Rouquinator pour les 8 paires canoniques,
     * extension cohérente (litière à 3 segments tournée vers le creux du virage)
     * pour toutes les autres paires — virages durs, traces dégénérées. */
    private static Object[] leafValues(boolean dirNS, String d1, String d2) {
        for (Object[] row : dirNS ? LEAF_NS : LEAF_EW) {
            String[] pair = (String[]) row[0];
            if ((d1.equals(pair[0]) && d2.equals(pair[1]))
                    || (d1.equals(pair[1]) && d2.equals(pair[0]))) {
                return new Object[]{row[1], row[2], row[3], row[4]};
            }
        }
        boolean nDom = CARD_N.contains(d1) || CARD_N.contains(d2);
        boolean sDom = CARD_S.contains(d1) || CARD_S.contains(d2);
        String f1 = (nDom && !sDom) ? "south" : "north";
        boolean eDom = CARD_E.contains(d1) || CARD_E.contains(d2);
        boolean oDom = CARD_O.contains(d1) || CARD_O.contains(d2);
        String f2;
        if (eDom && !oDom) {
            f2 = "east";
        } else if (oDom && !eDom) {
            f2 = "west";
        } else {
            f2 = f1;
        }
        return new Object[]{3, f1, 3, f2};
    }

    private static String crossFacing(int dx, int dz) {
        if (dx == 1 && dz == -1) {
            return "east";
        }
        if (dx == -1 && dz == -1) {
            return "south";
        }
        if (dx == 1 && dz == 1) {
            return "north";
        }
        return "west";
    }

    private static BlockState leafLitter(int segments, String facingName) {
        Direction dir = switch (facingName) {
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> Direction.NORTH;
        };
        BlockState st = Blocks.LEAF_LITTER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, dir);
        return ClassicDesign.Palette.setByName(st, "segment_amount", String.valueOf(segments));
    }

    private static final class Writer {
        private final WorldView view;
        private final Long2ObjectOpenHashMap<BlockState> plan;

        Writer(WorldView view, Long2ObjectOpenHashMap<BlockState> plan) {
            this.view = view;
            this.plan = plan;
        }

        void put(int x, int y, int z, BlockState state) {
            if (ClassicDesign.ColumnWriter.isRailFamily(view.initialAt(x, y, z))) {
                return;
            }
            plan.put(BlockPos.asLong(x, y, z), state);
            view.put(x, y, z, state);
        }

        /**
         * Comble sous les blocs poses qui flottent : aucun bloc du build ne
         * garde de l'air directement sous lui (contrat du script, la voie
         * repose toujours sur le sol — remplissage gravier, max 6 blocs).
         */
        void fillSupports() {
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
                // (un remplissage tronqué laisserait lui-même 1-3 blocs d'air).
                int gap = 0;
                while (view.isAir(x, y - 1 - gap, z) && gap < 64) {
                    gap++;
                }
                if (gap == 0 || gap > depthMax) {
                    continue;
                }
                for (int yy = y - 1; yy >= y - gap; yy--) {
                    long k = BlockPos.asLong(x, yy, z);
                    if (!plan.containsKey(k)
                            && !ClassicDesign.ColumnWriter.isRailFamily(view.initialAt(x, yy, z))) {
                        BlockState fill = Blocks.GRAVEL.defaultBlockState();
                        plan.put(k, fill);
                        view.put(x, yy, z, fill);
                    }
                }
            }
        }
    }
}
