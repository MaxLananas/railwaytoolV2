package com.bte.railpathtool.track;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseCoralWallFanBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Modèle d'analyse d'une trace de rail.
 *
 * Classification de chaque voxel en N-S / E-W / DIAG, identique au script de coloration
 * du tuto BTE France, mais résolue de façon purement géométrique (donc déterministe,
 * indépendante de l'ordre d'application). Les couleurs de laine fixées manuellement
 * gardent la priorité (rouge = NS, bleu = EW, vert = DIAG), ainsi qu'un override global.
 * Les coraux déjà construits (facing sud/est) servent d'indices, comme dans les scripts.
 */
public final class TrackModel {

    /** Ordre de tolérance verticale utilisé par les scripts (exact, l'ordre compte). */
    public static final int[] DY_TOLERANCE = {0, 1, -1};

    public enum OverrideMode {AUTO, FORCE_NS, FORCE_EW, FORCE_DIAG}

    private final WorldView view;
    private final LongOpenHashSet trace;
    private final Long2ObjectOpenHashMap<TrackType> types;

    public TrackModel(WorldView view, List<BlockPos> traceVoxels, OverrideMode mode) {
        this.view = view;
        this.trace = new LongOpenHashSet();
        for (BlockPos p : traceVoxels) {
            trace.add(p.asLong());
        }
        this.types = new Long2ObjectOpenHashMap<>();
        for (BlockPos p : traceVoxels) {
            types.put(p.asLong(), classify(p, mode));
        }
    }

    public LongOpenHashSet trace() {
        return trace;
    }

    public Long2ObjectOpenHashMap<TrackType> types() {
        return types;
    }

    public WorldView view() {
        return view;
    }

    public TrackType typeOf(BlockPos pos) {
        return types.get(pos.asLong());
    }

    /** Un bloc fait-il partie du tracé au sens large (n'importe quelle laine) ? */
    public boolean isWoolTrace(int x, int y, int z) {
        return view.at(x, y, z).is(BlockTags.WOOL);
    }

    /**
     * Type effectif d'une position : type calculé pour un voxel de trace,
     * sinon indice laissé par un corail déjà construit (facing sud/est).
     */
    public TrackType typeAt(int x, int y, int z) {
        TrackType t = types.get(BlockPos.asLong(x, y, z));
        if (t != null) {
            return t;
        }
        BlockState st = view.at(x, y, z);
        if (st.is(Blocks.DEAD_BUBBLE_CORAL_WALL_FAN)) {
            return switch (st.getValue(BaseCoralWallFanBlock.FACING)) {
                case NORTH, SOUTH -> TrackType.NS;
                default -> TrackType.EW;
            };
        }
        return null;
    }

    /** Premier type trouvé avec la tolérance verticale {0, +1, -1} (ordre du script). */
    public TrackType typeNear(int x, int y, int z) {
        for (int dy : DY_TOLERANCE) {
            TrackType t = typeAt(x, y + dy, z);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------

    private TrackType classify(BlockPos voxel, OverrideMode mode) {
        BlockState st = view.at(voxel);
        if (mode != OverrideMode.AUTO) {
            return switch (mode) {
                case FORCE_NS -> TrackType.NS;
                case FORCE_EW -> TrackType.EW;
                case FORCE_DIAG -> TrackType.DIAG;
                default -> TrackType.NS;
            };
        }
        // Overrides persistés par la coloration manuelle (mêmes couleurs que le tuto).
        if (st.is(Blocks.RED_WOOL)) {
            return TrackType.NS;
        }
        if (st.is(Blocks.BLUE_WOOL)) {
            return TrackType.EW;
        }
        if (st.is(Blocks.LIME_WOOL)) {
            return TrackType.DIAG;
        }
        int x = voxel.getX();
        int y = voxel.getY();
        int z = voxel.getZ();
        // Rail déjà construit : l'indice (corail/pupitre) pose à y ou y+1 dicte le type.
        TrackType hint = hintRailAt(x, y, z);
        if (hint != null) {
            return hint;
        }

        // Analyse géométrique pure sur les voisins 3x3 (tolérance verticale ±1).
        boolean n = has(x, y, z - 1), s = has(x, y, z + 1);
        boolean e = has(x + 1, y, z), o = has(x - 1, y, z);
        boolean ne = has(x + 1, y, z - 1), no = has(x - 1, y, z - 1);
        boolean se = has(x + 1, y, z + 1), so = has(x - 1, y, z + 1);

        if ((n && s) || (n && se) || (n && so) || (s && ne) || (s && no)) {
            return TrackType.NS;
        }
        if ((e && o) || (e && no) || (e && so) || (o && ne) || (o && se)) {
            return TrackType.EW;
        }
        if ((ne && so) || (no && se)) {
            return TrackType.DIAG;
        }
        // Filets de sécurité (trace non épilée à 100 %, motifs denses).
        if (n || s) {
            return TrackType.NS;
        }
        if (e || o) {
            return TrackType.EW;
        }
        if (ne || no || se || so) {
            return TrackType.DIAG;
        }
        return TrackType.NS; // bloc isolé
    }

    private boolean has(int x, int y, int z) {
        for (int dy : DY_TOLERANCE) {
            if (isWoolTrace(x, y + dy, z)) {
                return true;
            }
        }
        return false;
    }

    /** Type déduit d'un bloc de rail existant à y ou y+1 (corail / pupitre). */
    public TrackType hintRailAt(int x, int y, int z) {
        for (int dy = 0; dy <= 1; dy++) {
            BlockState st = view.at(x, y + dy, z);
            if (st.is(Blocks.DEAD_BUBBLE_CORAL_WALL_FAN)) {
                Direction f = st.getValue(BaseCoralWallFanBlock.FACING);
                return f == Direction.NORTH || f == Direction.SOUTH
                        ? TrackType.NS : TrackType.EW;
            }
            if (st.is(Blocks.LECTERN)) {
                Direction f = st.getValue(LecternBlock.FACING);
                return f == Direction.NORTH || f == Direction.SOUTH
                        ? TrackType.NS : TrackType.EW;
            }
        }
        return null;
    }

    /**
     * Voisins de trace (toute laine) avec tolérance verticale, pour la logique
     * leaf-litter du design Nature ; collecte dans l'ordre exact du script
     * (dx croissant, dz croissant, premier dy gagnant).
     */
    public java.util.List<String> neighborDirections(int x, int y, int z) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy : DY_TOLERANCE) {
                    if (isWoolTrace(x + dx, y + dy, z + dz)) {
                        out.add(directionName(dx, dz));
                        break;
                    }
                }
            }
        }
        return out;
    }

    public static String directionName(int dx, int dz) {
        String d = "";
        if (dz == -1) {
            d = "N";
        } else if (dz == 1) {
            d = "S";
        }
        if (dx == 1) {
            d += "E";
        } else if (dx == -1) {
            d += "O";
        }
        return d;
    }
}
