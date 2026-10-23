package com.bte.railpathtool.track;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class Grounding {

    public static final int MAX_UP = 15;
    public static final int MAX_DOWN = 20;

    private Grounding() {
    }

    /**
     * Cell where trace wool may be placed: air or soft natural terrain (grass,
     * rock, dirt, sand, snow, ice, water...). Never rail, wool or player decor,
     * which would erase an existing line. Exact mirror of rail_sim.NATURAL_SOFT.
     */
    public static boolean isWoolLayable(
            BlockState st) {
        if (st.isAir()) {
            return true;
        }
        if (isTraceWool(st)) {
            return false;
        }
        // Terrain is tested before the rail guard: gravel is both soft ground and
        // a nature support block, and laying wool on it never erases a rail.
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(st.getBlock()).getPath();
        return switch (id) {
            // Design-mix soils (and gravel) stay re-traceable: a support placed by an
            // earlier trace must never shift the next trace, or the geometry would
            // depend on the random soil draw (sim/Java parity).
            case "grass_block", "water", "stone", "dirt", "coarse_dirt",
                 "sand", "sandstone", "terracotta", "clay", "snow_block",
                 "ice", "mud", "andesite", "granite", "diorite",
                 "deepslate", "cobbled_deepslate", "pale_oak_wood",
                 "deepslate_iron_ore", "deepslate_coal_ore", "gravel",
                 "oak_leaves", "spruce_leaves" -> true;
            // Everything else (rail, forced wool, player blocks) is protected: a
            // trace never overwrites another line.
            default -> false;
        };
    }

    /** Registered block name ends with {@code _wool} (any colour). */
    static boolean isTraceWool(
            BlockState st) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(st.getBlock()).getPath().endsWith("_wool");
    }

    /**
     * Trace voxels whose cell actually holds the wool placed by this build.
     * Membership is positional, never name-based: fill wool or player decor is
     * not a rail voxel.
     */
    static LongOpenHashSet laidWool(WorldView view, List<BlockPos> trace) {
        LongOpenHashSet wool = new LongOpenHashSet();
        for (BlockPos v : trace) {
            BlockState st =
                    view.at(v.getX(), v.getY(), v.getZ());
            // Registry name rather than BlockTags.WOOL: tags are not loaded in the
            // harness Bootstrap, where tag lookups always return false.
            if (st != null && isTraceWool(st)) {
                wool.add(BlockPos.asLong(v.getX(), v.getY(), v.getZ()));
            }
        }
        return wool;
    }

    static void moveWool(LongOpenHashSet wool, int fx, int fy, int fz,
                         int tx, int ty, int tz) {
        if (wool.remove(BlockPos.asLong(fx, fy, fz))) {
            wool.add(BlockPos.asLong(tx, ty, tz));
        }
    }

    public static boolean isUnstable(WorldView view, int x, int y, int z) {
        boolean below = view.isAir(x, y - 1, z);
        boolean bn = view.isAir(x, y - 1, z - 1);
        boolean bs = view.isAir(x, y - 1, z + 1);
        boolean be = view.isAir(x + 1, y - 1, z);
        boolean bo = view.isAir(x - 1, y - 1, z);
        if (below && bn && bs && be && bo) {
            return true;
        }
        boolean n = view.isAir(x, y, z - 1);
        boolean s = view.isAir(x, y, z + 1);
        boolean e = view.isAir(x + 1, y, z);
        boolean o = view.isAir(x - 1, y, z);
        if (count(n, s, e, o) >= 3) {
            return true;
        }
        if ((n && s) || (e && o)) {
            return true;
        }
        boolean ne = view.isAir(x + 1, y, z - 1);
        boolean so = view.isAir(x - 1, y, z + 1);
        boolean no = view.isAir(x - 1, y, z - 1);
        boolean se = view.isAir(x + 1, y, z + 1);
        if ((ne && so) || (no && se)) {
            return true;
        }
        return count(ne, so, no, se) >= 3;
    }

    private static int count(boolean... bs) {
        int c = 0;
        for (boolean b : bs) {
            if (b) {
                c++;
            }
        }
        return c;
    }

    public static List<BlockPos> apply(WorldView view, List<BlockPos> trace) {
        return apply(view, trace, null);
    }

    public static List<BlockPos> apply(WorldView view, List<BlockPos> trace,
                                       LongOpenHashSet dug) {
        List<BlockPos> moved = new ArrayList<>();
        LongOpenHashSet wool = laidWool(view, trace);
        Long2IntOpenHashMap dugPerColumn = null;
        if (dug != null) {
            dugPerColumn = new Long2IntOpenHashMap();
            for (long p : dug) {
                dugPerColumn.addTo(columnKey(BlockPos.getX(p), BlockPos.getZ(p)), 1);
            }
        }
        for (BlockPos v : trace) {
            int x = v.getX();
            int y = v.getY();
            int z = v.getZ();
            // Only voxels with real wool participate; rail or stone under the cell
            // pass through, otherwise a climb's leftover corner would erase coral.
            if (!wool.contains(BlockPos.asLong(x, y, z))) {
                moved.add(v);
                continue;
            }
            // Never climb into or through other trace wool (vertical spline teeth),
            // or the stack freezes with a corner left behind. Descending is allowed:
            // it deflates the stack; dedupeColumns makes the final decision.
            if (!view.isAir(x, y + 1, z)
                    && !wool.contains(BlockPos.asLong(x, y + 1, z))) {

                boolean dugHere = false;
                if (dug != null) {
                    int[][] toDig = new int[2][];
                    int count = 0;
                    boolean ok = true;
                    // One crest cut per column (max 2 blocks), across all passes.
                    long column = columnKey(x, z);
                    int colDug = dugPerColumn.get(column);
                    for (int dy = 1; ok && dy <= 2; dy++) {
                        BlockState st = view.at(x, y + dy, z);
                        if (st.isAir()) {
                            break;
                        }
                        if (com.bte.railpathtool.design.ClassicDesign.ColumnWriter.isRailFamily(st)
                                || net.minecraft.core.registries
                                        .BuiltInRegistries.BLOCK
                                        .getKey(st.getBlock()).getPath()
                                        .endsWith("_wool")
                                || wool.contains(BlockPos.asLong(
                                        x, y + dy, z))) {
                            ok = false;
                            break;
                        }
                        toDig[count++] = new int[]{x, y + dy, z};
                    }
                    // At most 2 tunnel blocks per column, across all passes; otherwise the
                    // terrain is carved open.
                    if (ok && colDug + count > 2) {
                        ok = false;
                    }
                    if (ok && count > 0 && !view.isAir(x, y + count + 1, z)) {
                        ok = false;
                    }
                    if (ok && count > 0) {
                        for (int i = 0; i < count; i++) {
                            view.put(toDig[i][0], toDig[i][1], toDig[i][2],
                                    Blocks.AIR.defaultBlockState());
                            dug.add(BlockPos.asLong(toDig[i][0], toDig[i][1], toDig[i][2]));
                            dugPerColumn.addTo(column, 1);
                        }
                        dugHere = true;
                    }
                }
                if (dugHere) {
                    moved.add(v);
                    continue;
                }
                boolean movedUp = false;
                for (int dy = 1; dy <= MAX_UP; dy++) {
                    if (view.isAir(x, y + dy, z)) {
                        int ny = y + dy - 1;
                        if (wool.contains(BlockPos.asLong(x, ny, z))) {
                            break;  // never land ON another wool voxel
                        }
                        if (com.bte.railpathtool.design.ClassicDesign.ColumnWriter
                                .isRailFamily(view.at(x, ny, z))) {
                            break;  // never land INSIDE existing rail
                        }
                        // Leftover corner: only placed wool outside water counts, otherwise
                        // floating grass would lock a neighbour's descent.
                        BlockState own =
                                view.at(x, y, z);
                        BlockState below =
                                view.at(x, y - 1, z);
                        view.put(x, y, z,
                                below.isAir() || below.is(Blocks.WATER)
                                        ? Blocks.AIR.defaultBlockState()
                                        : Blocks.GRASS_BLOCK.defaultBlockState());
                        // The moved wool keeps its colour (forced wool included).
                        view.put(x, ny, z, own);
                        moveWool(wool, x, y, z, x, ny, z);
                        moved.add(new BlockPos(x, ny, z));
                        movedUp = true;
                        break;
                    }
                }
                if (!movedUp) {
                    moved.add(v);
                }
                continue;
            }

            int target = y;
            boolean wasUnstable = isUnstable(view, x, y, z);
            for (int i = 0; i < MAX_DOWN; i++) {
                int nxt = target - 1;
                if (!isUnstable(view, x, target, z)) {
                    break;
                }
                if (wool.contains(BlockPos.asLong(x, nxt, z))) {
                    break;
                }
                // Never overwrite existing rail: a second trace descending onto the first
                // stops on it. Otherwise wool would replace the crossing coral and break
                // the first line.
                if (com.bte.railpathtool.design.ClassicDesign.ColumnWriter
                        .isRailFamily(view.at(x, nxt, z))) {
                    break;
                }
                target = nxt;
            }
            if (target != y) {
                BlockState own =
                        view.at(x, y, z);
                view.put(x, y, z, Blocks.AIR.defaultBlockState());
                view.put(x, target, z, own);
                moveWool(wool, x, y, z, x, target, z);
                y = target;
            }
            // JUNCTION: the wool stopped on a decor column of another line (support
            // soil + decor, no core). Without a sink its rail would sit two blocks
            // above its neighbours, breaking the ribbon at the crossing. The column is
            // reclaimed: wool descends into the support base and its core replaces the
            // decor, keeping the crossing level. Exact mirror of
            // rail_sim.rectify_vertical.
            if (wasUnstable && !isUnstable(view, x, y, z)) {
                BlockState d1 =
                        view.at(x, y - 1, z);
                BlockState d2 =
                        view.at(x, y - 2, z);
                BlockState d3 =
                        view.at(x, y - 3, z);
                if (isDecor(d1)
                        && com.bte.railpathtool.design.ClassicDesign
                                .ColumnWriter.isSupportSoil(d2)
                        && !d3.isAir() && !d3.is(Blocks.WATER)
                        && !isTraceWool(d3)
                        && !com.bte.railpathtool.design.ClassicDesign
                                .ColumnWriter.isRailCore(d3)) {
                    BlockState own =
                            view.at(x, y, z);
                    view.put(x, y, z, Blocks.AIR.defaultBlockState());
                    view.put(x, y - 1, z, Blocks.AIR.defaultBlockState());
                    view.put(x, y - 2, z, own);
                    moveWool(wool, x, y, z, x, y - 2, z);
                    y = y - 2;
                }
            }
            moved.add(new BlockPos(x, y, z));
        }
        return moved;
    }

    /** Rail decor (never a core), reclaimable by a junction. */
    static boolean isDecor(
            BlockState st) {
        return st.is(Blocks.MUD_BRICK_WALL) || st.is(Blocks.ANDESITE_WALL)
                || st.is(Blocks.IRON_DOOR) || st.is(Blocks.OAK_BUTTON)
                || st.is(Blocks.PALE_MOSS_CARPET) || st.is(Blocks.LEAF_LITTER)
                || st.is(Blocks.SPRUCE_SHELF);
    }

    /**
     * Flattens vertical saw teeth: single peaks and short plateaus (max 3
     * voxels) offset by one block between two segments at the same level
     * (a.y == c.y != run.y). Each plateau voxel moves if its target cell is
     * free (no wool, no rail). Removes lateral column collisions and floating
     * track fragments on flat terrain.
     */
    public static List<BlockPos> flattenTeeth(WorldView view, List<BlockPos> trace) {
        if (trace.size() < 3) {
            return trace;
        }
        LongOpenHashSet wool = laidWool(view, trace);
        BlockPos[] out = trace.toArray(new BlockPos[0]);
        int n = out.length;
        int i = 1;
        while (i < n - 1) {
            int ay = out[i - 1].getY();
            int by = out[i].getY();
            if (by == ay || Math.abs(by - ay) != 1) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && out[j].getY() == by) {
                j++;
            }
            int runLen = j - i;
            boolean endsOk = j < n && out[j].getY() == ay;
            if (endsOk && runLen <= 3) {
                boolean ok = true;
                for (int k = i; k < j; k++) {
                    BlockState target =
                            view.at(out[k].getX(), ay, out[k].getZ());
                    if (wool.contains(BlockPos.asLong(
                                    out[k].getX(), ay, out[k].getZ()))
                            || isTraceWool(target)
                            || com.bte.railpathtool.design.ClassicDesign.ColumnWriter.isRailFamily(target)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    BlockState[] own =
                            new BlockState[j - i];
                    for (int k = i; k < j; k++) {
                        own[k - i] = view.at(out[k].getX(), by, out[k].getZ());
                        view.put(out[k].getX(), by, out[k].getZ(), Blocks.AIR.defaultBlockState());
                    }
                    for (int k = i; k < j; k++) {
                        int bx = out[k].getX();
                        int bz = out[k].getZ();
                        // The tooth keeps its colour (forced wool included).
                        BlockState st = own[k - i];
                        view.put(bx, ay, bz, st.isAir() ? Blocks.WHITE_WOOL.defaultBlockState() : st);
                        moveWool(wool, bx, by, bz, bx, ay, bz);
                        out[k] = new BlockPos(bx, ay, bz);
                    }
                }
            }
            i = j;
        }
        List<BlockPos> res = new ArrayList<>(out.length);
        java.util.Collections.addAll(res, out);
        return res;
    }

    /**
     * No column (x,z) carries two track levels. Upper levels are removed first;
     * the lower level is only touched as a last resort, so a column always
     * keeps at least one voxel.
     */
    public static List<BlockPos> dedupeColumns(WorldView view, List<BlockPos> trace) {
        List<BlockPos> cur = new ArrayList<>(new LinkedHashSet<>(trace));
        LongOpenHashSet woolSet = laidWool(view, cur);
        Set<BlockPos> present = new LinkedHashSet<>(cur);
        Map<Long, List<BlockPos>> byColumn = new LinkedHashMap<>();
        for (BlockPos v : cur) {
            byColumn.computeIfAbsent(columnKey(v.getX(), v.getZ()), k -> new ArrayList<>())
                    .add(v);
        }
        for (List<BlockPos> levels : byColumn.values()) {
            Set<Integer> ys = new TreeSet<>();
            for (BlockPos v : levels) {
                ys.add(v.getY());
            }
            if (ys.size() < 2) {
                continue;
            }
            List<Integer> remaining = new ArrayList<>(ys);
            List<Integer> order = new ArrayList<>(remaining.subList(1, remaining.size()));
            Collections.reverse(order);
            order.add(remaining.get(0));
            for (int y : order) {
                if (remaining.size() <= 1) {
                    break;
                }
                List<BlockPos> victims = new ArrayList<>();
                for (BlockPos v : levels) {
                    if (v.getY() == y && present.contains(v)) {
                        victims.add(v);
                    }
                }
                if (victims.isEmpty() || !isTouchable(view, woolSet, victims)) {
                    continue;
                }
                for (BlockPos v : victims) {
                    view.put(v.getX(), v.getY(), v.getZ(), Blocks.AIR.defaultBlockState());
                    woolSet.remove(v.asLong());
                    present.remove(v);
                }
                remaining.remove(Integer.valueOf(y));
            }
        }
        List<BlockPos> out = new ArrayList<>(present.size());
        for (BlockPos v : cur) {
            if (present.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static boolean isTouchable(WorldView view, LongOpenHashSet woolSet,
                                       List<BlockPos> victims) {
        for (BlockPos v : victims) {
            BlockState st = view.at(v);
            if (!st.isAir() && !st.is(Blocks.WHITE_WOOL)
                    && woolSet.contains(v.asLong())) {
                return false;
            }
        }
        return true;
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }
}
