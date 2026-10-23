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
        w.fillSupports(options);
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
            w.putDecorVisible(x, y + 1, z, Blocks.PALE_MOSS_CARPET.defaultBlockState());
        } else {
            w.put(x, y, z, Blocks.PALE_MOSS_BLOCK.defaultBlockState());
            w.putDecorVisible(x, y + 1, z, Blocks.OAK_BUTTON.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.POWERED, true));
        }

        for (int[] off : ortho) {
            w.putDecorVisible(x + off[0], y, z + off[1], Blocks.GRAVEL.defaultBlockState());
        }

        if (dirNS) {
            for (int[] off : crossQuads) {
                for (int dy : TrackModel.DY_TOLERANCE) {
                    if (model.typeAt(x + off[0], y + dy, z + off[1]) == TrackType.EW) {
                        w.putDecorVisible(x, y + dy, z + off[1], Blocks.GRAVEL.defaultBlockState());
                        w.putDecorVisible(x, y + dy + 1, z + off[1],
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
            // Corner drift (the segment slides one block in X or Z): the straight
            // N/S or E/W pair hides the diagonal neighbour. Replace it so corners
            // get the reference three-segment litter.
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
        w.putDecorVisible(x + leafPos[0][0], y + 1, z + leafPos[0][1], leafLitter(a1, f1));
        w.putDecorVisible(x + leafPos[1][0], y + 1, z + leafPos[1][1], leafLitter(a2, f2));
    }

    private static final java.util.Set<String> CARD_N = java.util.Set.of("N", "NE", "NO");
    private static final java.util.Set<String> CARD_S = java.util.Set.of("S", "SE", "SO");
    private static final java.util.Set<String> CARD_E = java.util.Set.of("E", "NE", "SE");
    private static final java.util.Set<String> CARD_O = java.util.Set.of("O", "NO", "SO");

    /**
     * {segments, facing, segments, facing} for the seven canonical pairs. Other
     * pairs (hard turns, degenerate traces) fall back to a consistent
     * three-segment litter turned toward the inside of the curve.
     */
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

        /**
         * Places visible decor. If the cell above is solid terrain (not rail, air
         * or water) the decor would be buried, so the write is skipped.
         */
        void putDecorVisible(int x, int y, int z, BlockState state) {
            BlockState above = view.at(x, y + 1, z);
            if (!above.isAir()
                    && above.getBlock() != Blocks.WATER
                    && !ClassicDesign.ColumnWriter.isRailFamily(above)) {
                return;
            }
            put(x, y, z, state);
        }

        static boolean isNatureCore(BlockState st) {
            return st.getBlock() == Blocks.LECTERN
                    || st.getBlock() == Blocks.PALE_MOSS_BLOCK;
        }

        void put(int x, int y, int z, BlockState state) {
            long key = BlockPos.asLong(x, y, z);
            boolean planned = plan.containsKey(key);
            // Live overlay, as in the reference script: any rail already visible
            // before this build is untouchable. Without a level (parity harness)
            // the initial-state veto is silent rather than wrong.
            BlockState cur = planned ? plan.get(key) : view.at(x, y, z);
            if (!planned
                    && ClassicDesign.ColumnWriter.isRailFamily(cur)) {
                if (ClassicDesign.ColumnWriter.isRailCore(cur)) {
                    return;                    // never overwrite a core
                }
                if (!ClassicDesign.ColumnWriter.isRailCore(state)) {
                    // Later decor leaves earlier decor and beds alone, but a core may take
                    // them (junctions: visible rail wins).
                    return;
                }
            }
            // Intra-build priority: a core (lectern/moss) always wins its cell,
            // otherwise the litter of a lower voxel could claim the core cell of the
            // voxel above on a stair. Decor only replaces identical decor; gravel is
            // the passive bed that decor may improve.
            if (planned && cur != null && cur != state
                    && ClassicDesign.ColumnWriter.isRailFamily(cur)) {
                if (isNatureCore(cur)) {
                    return;                     // a placed core never moves
                }
                if (!isNatureCore(state) && cur.getBlock() != Blocks.GRAVEL) {
                    return;                     // decor over a different decor: no
                }
            }
            plan.put(key, state);
            view.put(x, y, z, state);
        }

        /**
         * Fills under floating rail blocks: no block of this build keeps air
         * directly below it. Gravel fill, at most four blocks deep.
         */
        void fillSupports(DesignOptions options) {
            final int depthMax = 4;
            // Whole visible overlay, as in the reference script: a later trace may
            // have dug the support of a rail placed just before.
            long[] keys = view.overlay().keySet().toLongArray();
            for (long key : keys) {
                BlockState st = view.overlay().get(key);
                if (st == null || st.isAir()
                        || (!ClassicDesign.ColumnWriter.isRailFamily(st)
                                && !ClassicDesign.ColumnWriter.isBaseSoil(st)
                                && !(options.fillMode
                                        == DesignOptions.FillMode.UNIFORM
                                        && st.is(Blocks.ORANGE_WOOL)))) {
                    continue;
                }
                int x = BlockPos.getX(key);
                int y = BlockPos.getY(key);
                int z = BlockPos.getZ(key);
                // Measure the real gap: deeper than the cap is a deliberate bridge, and
                // a truncated fill would itself leave air.
                int gap = 0;
                // Water is not support: the gravel pillar runs down to the bed.
                while (gap < 64) {
                    BlockState belowSt = view.at(x, y - 1 - gap, z);
                    if (!belowSt.isAir() && !belowSt.is(Blocks.WATER)) {
                        break;
                    }
                    gap++;
                }

                if (gap == 0 || gap > depthMax) {
                    continue;
                }
                for (int yy = y - 1; yy >= y - gap; yy--) {
                    BlockState curSt = view.at(x, yy, z);
                    if (curSt.isAir() || curSt.is(Blocks.WATER)) {
                        BlockState fill = Blocks.GRAVEL.defaultBlockState();
                        plan.put(BlockPos.asLong(x, yy, z), fill);
                        view.put(x, yy, z, fill);
                    }
                }
            }
        }
    }
}
