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

public final class TrackModel {

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

    public boolean isWoolTrace(int x, int y, int z) {
        return view.at(x, y, z).is(BlockTags.WOOL);
    }

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

    public TrackType typeNear(int x, int y, int z) {
        for (int dy : DY_TOLERANCE) {
            TrackType t = typeAt(x, y + dy, z);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

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

        TrackType hint = hintRailAt(x, y, z);
        if (hint != null) {
            return hint;
        }

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

        if (n || s) {
            return TrackType.NS;
        }
        if (e || o) {
            return TrackType.EW;
        }
        if (ne || no || se || so) {
            return TrackType.DIAG;
        }
        return TrackType.NS;
    }

    private boolean has(int x, int y, int z) {
        for (int dy : DY_TOLERANCE) {
            if (isWoolTrace(x, y + dy, z)) {
                return true;
            }
        }
        return false;
    }

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
