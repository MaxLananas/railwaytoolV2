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

/**
 * Design « Nature » du tuto BTE France (variantes forestières) :
 *  - pupitre facing nord (NS) / est (EW) + pale_moss_carpet ; sur une marche haute,
 *    pale_moss_block + bouton de chêne alimenté à la place
 *  - gravier latéral, et gravier + litière (3 segments) aux intersections avec l'E-W
 *  - litière feuille (2 ou 3 segments) au-dessus de chaque côté, orientation et nombre
 *    décidés par la table exacte du script à partir des deux voisins de trace
 *  - les diagonales sont converties au type de leur extrémité (amélioration demandée)
 */
public final class NatureDesign implements RailDesign {

    /** Table litière NS : (paire de voisins) -> {segments1, facing1, segments2, facing2}. */
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
        for (Map.Entry<Long, TrackType> entry : model.types().entrySet()) {
            switch (entry.getValue()) {
                case DIAG -> diags.add(BlockPos.of(entry.getKey()));
                case NS -> ns.add(BlockPos.of(entry.getKey()));
                case EW -> ew.add(BlockPos.of(entry.getKey()));
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
    }

    /** Place un rail Nature complet pour un voxel NS/EW (script 4 porté). */
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

        // Intersections rouge/bleu : gravier + litière 3 segments orientée (NS seulement).
        if (dirNS) {
            for (int[] off : crossQuads) {
                for (int dy : TrackModel.DY_TOLERANCE) {
                    if (model.typeAt(x + off[0], y + dy, z + off[1]) == TrackType.EW) {
                        w.put(x, y + dy, z + off[1], Blocks.GRAVEL.defaultBlockState());
                        w.put(x, y + dy + 1, z + off[1],
                                leafLitter(3, crossFacing(off[0], off[1])));
                        break;
                    }
                }
            }
        }

        // Litière latérale au-dessus : table exacte sur les 2 voisins de trace.
        List<String> nb = model.neighborDirections(x, y, z);
        String d1 = nb.isEmpty() ? "" : nb.get(0);
        String d2 = nb.size() > 1 ? nb.get(1) : "";
        int a1;
        String f1;
        int a2;
        String f2;
        Object[] found = findLeaf(dirNS, d1, d2);
        if (found != null) {
            a1 = (Integer) found[1];
            f1 = (String) found[2];
            a2 = (Integer) found[3];
            f2 = (String) found[4];
        } else if (dirNS) {
            a1 = 2; f1 = "north"; a2 = 2; f2 = "south";
        } else {
            a1 = 2; f1 = "west"; a2 = 2; f2 = "east";
        }
        w.put(x + leafPos[0][0], y + 1, z + leafPos[0][1], leafLitter(a1, f1));
        w.put(x + leafPos[1][0], y + 1, z + leafPos[1][1], leafLitter(a2, f2));
    }

    private static Object[] findLeaf(boolean dirNS, String d1, String d2) {
        for (Object[] row : dirNS ? LEAF_NS : LEAF_EW) {
            String[] pair = (String[]) row[0];
            if ((d1.equals(pair[0]) && d2.equals(pair[1]))
                    || (d1.equals(pair[1]) && d2.equals(pair[0]))) {
                return row;
            }
        }
        return null;
    }

    /** LEAF_FACING du script : {NE:east, NO:south, SE:north, SO:west}. */
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

    /** Poseur vers le plan + calque du WorldView. */
    private static final class Writer {
        private final WorldView view;
        private final Long2ObjectOpenHashMap<BlockState> plan;

        Writer(WorldView view, Long2ObjectOpenHashMap<BlockState> plan) {
            this.view = view;
            this.plan = plan;
        }

        void put(int x, int y, int z, BlockState state) {
            plan.put(BlockPos.asLong(x, y, z), state);
            view.put(x, y, z, state);
        }
    }
}
