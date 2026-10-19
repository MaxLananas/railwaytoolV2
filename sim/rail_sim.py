#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
rail_sim.py - reference model of the BTE rail system used by the mod.

It reproduces the four Lua scripts of the BTE France tutorial as a
deterministic pipeline, in the same order as the Java tool:

  1. Trace   : Catmull-Rom sampling, voxelised into a 26-connected path
  2. Rectify : vertical levelling and "L" corner cleanup (all orientations)
  3. Analyse : pure-geometry N-S / E-W / diagonal classification
               (colour overrides: red=NS, blue=EW, green=DIAG)
  4. Build   : "Classic" design (walls/shelf/coral, as the Hamburger script)
               or "Nature" (lecterns/pale moss/gravel, as script 4)

Usage:
  python3 rail_sim.py                 -> every scenario
  python3 rail_sim.py s-curve         -> a single scenario
  python3 rail_sim.py --style nature  -> force the nature design
  python3 rail_sim.py --no-color
"""

import os
import sys
import math
import random

# =============================================================================
# 1. MINIMAL WORLD (dict (x,y,z) -> block state name)
# =============================================================================

class World:
    def __init__(self):
        self.blocks = {}

    def get(self, x, y, z):
        return self.blocks.get((x, y, z), "air")

    def set(self, x, y, z, state):
        if state == "air":
            self.blocks.pop((x, y, z), None)
        else:
            self.blocks[(x, y, z)] = state

    def positions(self):
        return self.blocks.keys()


WOOL = {f"{c}_wool" for c in (
    "white orange magenta light_blue yellow lime pink gray light_gray cyan "
    "purple blue brown green red black".split())}

GROUND = "grass_block"
AIR = "air"

# Positions of the trace voxels currently processed by the low-level passes
# (grounding/purge/flatten/dedupe). This is THE membership reference, never
# `block == *_wool`: a uniform orange wool fill (the mod default) or a
# decorative player-placed wool must never count as a track voxel.
_TRACE_WOOL = {}


def _wool_at(x, y, z):
    return (x, y, z) in _TRACE_WOOL


def _set_trace_wool(world, trace):
    """Records the trace voxels whose cell really holds the wool placed by this
    build. A voxel whose cell is already occupied (rail of a previous line,
    stone, ground) is NOT part of the set: the passes must not move it as if it
    were wool, otherwise a climb's leftover corner erases a neighbouring coral
    (jun-45)."""
    global _TRACE_WOOL
    _TRACE_WOOL = {(v[0], v[1], v[2]): world.get(*v) for v in trace
                   if is_wool(world.get(*v))}


def _move_trace_wool(frm, to):
    st = _TRACE_WOOL.pop((frm[0], frm[1], frm[2]), None)
    if st is not None:
        _TRACE_WOOL[(to[0], to[1], to[2])] = st


def is_wool(state):
    return state in WOOL

# =============================================================================
# 2. TRACE: voxelised Catmull-Rom
# =============================================================================

def _curvature(prev, cur, nxt):
    """Exact copy of the mod's CatmullRom.curvature: angle over mean chord."""
    v1 = tuple(cur[i] - prev[i] for i in range(3))
    v2 = tuple(nxt[i] - cur[i] for i in range(3))
    l1 = math.sqrt(sum(a * a for a in v1))
    l2 = math.sqrt(sum(a * a for a in v2))
    if l1 < 1.0e-9 or l2 < 1.0e-9:
        return 0.0
    dot = sum(v1[i] * v2[i] for i in range(3)) / (l1 * l2)
    dot = max(-1.0, min(1.0, dot))
    return math.acos(dot) / (0.5 * (l1 + l2))


def adaptive_sample(control, base_density=6):
    """Exact copy of the mod sampling pipeline (UI default: density 6, adaptive
    mode on - AdaptiveSampler.sample): the step shrinks with curvature
    (boost 1..3), same Catmull-Rom."""
    if len(control) < 2:
        return list(control)
    pts = [(x + 0.5, y + 0.5, z + 0.5) for x, y, z in control]
    ext = [tuple(2 * pts[0][i] - pts[1][i] for i in range(3))] + pts
    ext.append(tuple(2 * pts[-1][i] - pts[-2][i] for i in range(3)))

    def cr(a, b, c, d, t):
        t2, t3 = t * t, t * t * t
        return 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2
                      + (-a + 3 * b - 3 * c + d) * t3)

    out = []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        mid_curv = _curvature(p0, p1, p2) + _curvature(p1, p2, p3)
        boost = 1.0 + min(2.0, mid_curv * 3.0)
        steps = max(1, int(math.ceil(math.dist(p1, p2) * base_density * boost)))
        for s in range(steps):
            t = s / steps
            out.append(tuple(cr(p0[k], p1[k], p2[k], p3[k], t) for k in range(3)))
    out.append(pts[-1])
    return out


def catmull_rom_points(control, samples_per_block=4):
    """Returns the sampled floating positions along the spline."""
    if len(control) < 2:
        return list(control)
    pts = [(x + 0.5, y + 0.5, z + 0.5) for x, y, z in control]
    ext = [tuple(2 * pts[0][i] - pts[1][i] for i in range(3))] + pts
    ext.append(tuple(2 * pts[-1][i] - pts[-2][i] for i in range(3)))

    def cr(a, b, c, d, t):
        t2, t3 = t * t, t * t * t
        return 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2
                      + (-a + 3 * b - 3 * c + d) * t3)

    out = []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        seg_len = math.dist(p1, p2)
        steps = max(1, int(math.ceil(seg_len * samples_per_block)))
        for s in range(steps):
            t = s / steps
            out.append(tuple(cr(p0[k], p1[k], p2[k], p3[k], t) for k in range(3)))
    out.append(pts[-1])
    return out


def voxelize(float_points):
    """Converts to consecutive 26-connected voxels, filling the gaps."""
    voxels = []
    for fx, fy, fz in float_points:
        v = (math.floor(fx), math.floor(fy), math.floor(fz))
        if not voxels:
            voxels.append(v)
            continue
        last = voxels[-1]
        if v == last:
            continue
        dx, dy, dz = v[0] - last[0], v[1] - last[1], v[2] - last[2]
        n = max(abs(dx), abs(dy), abs(dz))
        if n == 1:
            voxels.append(v)
        else:
            # Reconnect: insert the intermediate voxels.
            for k in range(1, n):
                t = k / n
                voxels.append((math.floor(last[0] + dx * t + 0.5),
                               math.floor(last[1] + dy * t + 0.5),
                               math.floor(last[2] + dz * t + 0.5)))
            voxels.append(v)
    # Deduplicate, keeping the order
    seen, ordered = set(), []
    for v in voxels:
        if v not in seen:
            seen.add(v)
            ordered.append(v)
    return ordered

# =============================================================================
# 3. RECTIFY: levelling and "L" corner cleanup
# =============================================================================

# The Lua script patterns ("north" neighbour dz=-1, lateral neighbour dx=+-1,
# with the vertical tolerances), extended to the four orientations.
def _l_patterns():
    base = []
    for dy1 in (0, 1, -1):
        for dy2 in (0, 1, -1):
            if (dy1, dy2) == (-1, 1):
                continue  # (0,-1,-1)+(+-1,1,0) is not in the script
            base.append((dy1, dy2))
    # The script also lacks (0,1,-1)+(+-1,-1,0); follow the exact table:
    exact = [(0, 0), (1, 0), (0, 1), (0, -1), (1, 1), (-1, -1), (-1, 0)]
    return exact

L_DY_PAIRS = _l_patterns()

def l_corners_here(world, x, y, z, spline):
    """True when the spline block at (x,y,z) forms an "L" corner (4 orientations)."""
    def is_spline(dx, dy, dz):
        # Membership is positional: a filling orange wool or a decorative player
        # wool is plain solid ground, never a track voxel.
        return _wool_at(x + dx, y + dy, z + dz)
    # Orientations: (north, east), (north, west), (south, east), (south, west)
    for d_vert in (0, 0, -1), (0, 0, 1):
        for d_hori in (1, 0, 0), (-1, 0, 0):
            for dy1, dy2 in L_DY_PAIRS:
                if is_spline(d_vert[0], dy1, d_vert[2]) and is_spline(d_hori[0], dy2, d_hori[2]):
                    return True
    return False


def diag_segment(world, x, y, z, spline):
    """True when the voxel belongs to a regular diagonal segment (spline at both
    opposite diagonal ends, with a y tolerance). It is NOT an "L" corner to
    purge but a thin diagonal that the script purge would break."""
    for dx, dz in ((1, 1), (1, -1)):
        for dy1 in DY:
            if not _wool_at(x + dx, y + dy1, z + dz):
                continue
            for dy2 in DY:
                if _wool_at(x - dx, y + dy2, z - dz):
                    return True
    return False


def locally_connected_without(world, x, y, z, spline):
    """Do the spline neighbours of (x,y,z) stay mutually connected (26-connectivity
    inside the 3x3x3 cube) when this voxel disappears? Keeps the "L" purge from
    opening a hole in a thin diagonal (missing rail)."""
    neigh = []
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx == 0 and dy == 0 and dz == 0:
                    continue
                if _wool_at(x + dx, y + dy, z + dz):
                    neigh.append((x + dx, y + dy, z + dz))
    if len(neigh) <= 1:
        return True
    allowed = set(neigh)
    seen = {neigh[0]}
    stack = [neigh[0]]
    while stack:
        cx, cy, cz = stack.pop()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    p = (cx + dx, cy + dy, cz + dz)
                    if p in allowed and p not in seen:
                        seen.add(p)
                        stack.append(p)
    return len(seen) == len(neigh)


def _trace_connected(s):
    """True when the voxel set is 26-connected, i.e. a single island."""
    if not s:
        return False
    it = iter(s)
    seed = next(it)
    seen = {seed}
    stack = [seed]
    while stack:
        x, y, z = stack.pop()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    nb = (x + dx, y + dy, z + dz)
                    if nb in s and nb not in seen:
                        seen.add(nb)
                        stack.append(nb)
    return len(seen) == len(s)


def rectify_l(world, trace, spline, corner):
    """Removes or replaces "L" corners, returning the cleaned trace in order.
    Three guards against breakage: (1) diagonal veto, (2) 3^3 local
    connectivity, (3) GLOBAL trace connectivity - a chain of removals can pass
    the first two yet cut the line (articulation points)."""
    removed = set()
    trace_set = set(trace)
    _set_trace_wool(world, trace)
    for (x, y, z) in trace:
        if not _wool_at(x, y, z):
            continue
        if not l_corners_here(world, x, y, z, spline):
            continue
        if diag_segment(world, x, y, z, spline):
            continue
        if not locally_connected_without(world, x, y, z, spline):
            continue
        if not _trace_connected(trace_set - {(x, y, z)}):
            continue  # articulation point: the removal would cut the line
        isolated = (world.get(x + 1, y, z) == AIR and world.get(x - 1, y, z) == AIR
                    and world.get(x, y, z + 1) == AIR and world.get(x, y, z - 1) == AIR)
        # An "L" corner only becomes a corner marker (grass) when it rests on
        # something: floating above the void it would pin the wool voxel above
        # it (is_unstable: non-air below means no descent) and create the mound
        # seen in the reports. In the air it becomes AIR.
        below = world.get(x, y - 1, z)
        supported = below not in (AIR, None) and below != "water"
        if isolated or not supported:
            world.set(x, y, z, AIR)
        else:
            world.set(x, y, z, corner)
        removed.add((x, y, z))
        trace_set.discard((x, y, z))
        _TRACE_WOOL.pop((x, y, z), None)
    return [v for v in trace if v not in removed]


def components_ribbon(pts):
    """Components of a voxel set under the TRACK RIBBON metric: two points are
    linked when their columns touch (|dx|<=1 and |dz|<=1, whatever dy - a
    mountain staircase is a continuous line) or share the same column. This is
    the visible contract (I2): only a HORIZONTAL hole breaks the ribbon."""
    pts = list(pts)
    parent = {p: p for p in pts}

    def find(p):
        while parent[p] != p:
            parent[p] = parent[parent[p]]
            p = parent[p]
        return p

    for i in range(len(pts)):
        for j in range(i + 1, len(pts)):
            a, b = pts[i], pts[j]
            if max(abs(a[0] - b[0]), abs(a[2] - b[2])) <= 1:
                ra, rb = find(a), find(b)
                if ra != rb:
                    parent[ra] = rb
    return len({find(p) for p in pts})


def _count_components(s):
    """Number of 26-connected components in the voxel set."""
    rest = set(s)
    n = 0
    while rest:
        n += 1
        seed = rest.pop()
        stack = [seed]
        seen = {seed}
        while stack:
            x, y, z = stack.pop()
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for dz in (-1, 0, 1):
                        nb = (x + dx, y + dy, z + dz)
                        if nb in rest and nb not in seen:
                            seen.add(nb)
                            stack.append(nb)
        rest -= seen
    return n


def dedupe_columns(world, trace, spline):
    """No vertical stacking: a column (x,z) must never carry two wool voxels
    at different heights. The y+-1 teeth of the spline plus the "L" cleanup
    could freeze a stack (a grass corner under the wool above it), the mound or
    raised rail seen in the reports. The LOWEST level wins; but when purging
    the higher levels would cut the line (a dip whose floor is only held by its
    top), the top is kept and fillSupports fills the dip. A removal is only
    accepted when the component count does not grow. Only wool and air cells
    are touched: rail already in the world is never altered."""
    by_col = {}
    for v in dict.fromkeys(trace):
        by_col.setdefault((v[0], v[2]), []).append(v)
    cur = list(dict.fromkeys(trace))
    _set_trace_wool(world, trace)
    for vals in by_col.values():
        ys = sorted({v[1] for v in vals})
        if len(ys) < 2:
            continue
        # Removal candidates: top levels first (the nominal case), then the
        # lowest level as a last resort. Stop once one level remains.
        remaining = list(ys)
        for yy in ys[1:][::-1] + ys[:1]:
            if len(remaining) <= 1:
                break
            victims = [v for v in vals if v[1] == yy and v in cur]
            if not victims:
                continue
            if any((v[0], v[1], v[2]) in _TRACE_WOOL
                   and world.get(v[0], v[1], v[2]) not in (AIR, None)
                   and world.get(v[0], v[1], v[2]) != "white_wool"
                   for v in victims):
                continue                        # existing rail or terrain: leave it
            trial = [v for v in cur if v not in victims]
            # Ribbon metric (as in I2): a disposable level must not open a
            # HORIZONTAL hole; stacked stairs and crossings no longer count as
            # obstacles to the stack cleanup, which used to freeze the stacks
            # [60,65] of winding tunnels.
            if components_ribbon(trial) <= components_ribbon(cur):
                for v in victims:
                    world.set(v[0], v[1], v[2], AIR)
                    _TRACE_WOOL.pop((v[0], v[1], v[2]), None)
                cur = trial
                remaining.remove(yy)
    return cur


def _flatten_teeth_impl(world, trace, spline):
    """Flattens vertical saw teeth: single peaks and short plateaus (<= 3
    voxels) offset by one block between two segments at the same level
    (a.y == c.y != run.y, |run.y - a.y| == 1). Each plateau voxel is realigned
    when its target cell is free (no wool, no rail). Removes lateral column
    collisions and the floating track fragments seen in game on flat ground
    when voxelisation oscillates around y+-1."""
    if len(trace) < 3:
        return trace
    out = list(trace)
    _set_trace_wool(world, trace)
    n = len(out)
    i = 1
    while i < n - 1:
        ay = out[i - 1][1]
        by = out[i][1]
        if by == ay or abs(by - ay) != 1:
            i += 1
            continue
        j = i
        while j < n and out[j][1] == by:
            j += 1
        run_len = j - i
        ends_ok = j < n and out[j][1] == ay
        if ends_ok and run_len <= 3:
            ok = True
            for k in range(i, j):
                x0, _, z0 = out[k]
                t = world.get(x0, ay, z0)
                if _wool_at(x0, ay, z0) or is_wool(t) or t in RAIL_FAMILY:
                    ok = False
                    break
            if ok:
                # Capture the colours BEFORE erasing: world.set(AIR) drops the
                # entry, a later get would return "air".
                owns = [world.get(out[k][0], by, out[k][2])
                        for k in range(i, j)]
                for k in range(i, j):
                    x0, _, z0 = out[k]
                    world.set(x0, by, z0, AIR)
                for k in range(i, j):
                    x0, _, z0 = out[k]
                    own = owns[k - i]
                    # The tooth keeps its colour (forced wool is possible).
                    world.set(x0, ay, z0, own if is_wool(own) else spline)
                    _move_trace_wool((x0, by, z0), (x0, ay, z0))
                    out[k] = (x0, ay, z0)
        i = j
    return out


def flatten_teeth(world, trace, spline):
    """Full flattening: saw teeth first, then vertical column deduplication.
    Single entry point used by every pipeline before the build."""
    return dedupe_columns(world, _flatten_teeth_impl(world, trace, spline), spline)


def is_unstable(world, x, y, z):
    """Exact port of the is_unstable function of the rectification script."""
    below_is_air = world.get(x, y - 1, z) == AIR
    bn = world.get(x, y - 1, z - 1) == AIR
    bs = world.get(x, y - 1, z + 1) == AIR
    be = world.get(x + 1, y - 1, z) == AIR
    bo = world.get(x - 1, y - 1, z) == AIR
    if below_is_air and bn and bs and be and bo:
        return True

    n = world.get(x, y, z - 1) == AIR
    s = world.get(x, y, z + 1) == AIR
    e = world.get(x + 1, y, z) == AIR
    o = world.get(x - 1, y, z) == AIR
    if sum((n, s, e, o)) >= 3:
        return True
    if (n and s) or (e and o):
        return True

    ne = world.get(x + 1, y, z - 1) == AIR
    so = world.get(x - 1, y, z + 1) == AIR
    no = world.get(x - 1, y, z - 1) == AIR
    se = world.get(x + 1, y, z + 1) == AIR
    if (ne and so) or (no and se):
        return True
    return sum((ne, so, no, se)) >= 3


def rectify_vertical(world, trace, spline, corner, max_up=15, max_down=20, dug=None):
    """Lifts buried wool and lowers floating wool, as in script 1.
    Mod improvement: when a full-height lift is impossible and the block above
    the wool is a 1-2 block non-rail crest, the crest is dug (tunnel) instead of
    pushing the rail one level down; `dug` collects the cells to clear."""
    moved_trace = []
    _set_trace_wool(world, trace)
    for (x, y, z) in trace:
        own = world.get(x, y, z)
        if not _wool_at(x, y, z) or own == AIR or own is None:
            moved_trace.append((x, y, z))
            continue
        # Lift: non-air above means a 1-2 block crest to dig, otherwise air up
        # to +15. Special case: the cell above is ANOTHER trace wool (vertical
        # duplicate from spline teeth) - never lift into it (stacks), but the
        # descent stays allowed, it is what deflates the stack.
        if world.get(x, y + 1, z) != AIR and not _wool_at(x, y + 1, z):
            dug_here = False
            if dug is not None:
                to_dig = []
                ok = True
                # The tunnel cuts ONE crest only: at most 2 blocks per column
                # across all passes, otherwise the terrain is carved open
                # (stupid_stress invariant "dug column > 2").
                col_dug = sum(1 for (dx2, _, dz2) in dug if dx2 == x and dz2 == z)
                for dy in (1, 2):
                    st = world.get(x, y + dy, z)
                    if st == AIR:
                        break
                    if st in RAIL_FAMILY or st == spline or is_wool(st) \
                            or (x, y + dy, z) in _TRACE_WOOL:
                        ok = False
                        break
                    to_dig.append((x, y + dy, z))
                if ok and col_dug + len(to_dig) > 2:
                    ok = False
                if ok and to_dig:
                    above = world.get(x, y + len(to_dig) + 1, z)
                    if above != AIR:
                        ok = False
                if ok and to_dig:
                    for pos in to_dig:
                        world.set(pos[0], pos[1], pos[2], AIR)
                        dug.add(pos)
                    dug_here = True
            if dug_here:
                moved_trace.append((x, y, z))
                continue
            moved_up = False
            for dy in range(1, max_up + 1):
                if world.get(x, y + dy, z) == AIR:
                    landy = y + dy - 1
                    if _wool_at(x, landy, z):
                        break  # never land ON another wool voxel
                    if world.get(x, landy, z) in RAIL_FAMILY:
                        break  # never land INSIDE existing rail
                    # Leftover corner: only when supported, otherwise floating
                    # grass locks a neighbour's descent (mound).
                    below = world.get(x, y - 1, z)
                    world.set(x, y, z,
                              corner if below not in (AIR, None) and below != "water"
                              else AIR)
                    world.set(x, landy, z, own)
                    _move_trace_wool((x, y, z), (x, landy, z))
                    y = landy
                    moved_trace.append((x, y, z))
                    moved_up = True
                    break
            if not moved_up:
                # Lift refused or impossible: the voxel STAYS, its wool is
                # still there. Never drop it silently, or the wool would remain
                # visible and never become rail.
                moved_trace.append((x, y, z))
            continue
        # Descente
        if is_unstable(world, x, y, z):
            target = y
            for _ in range(max_down):
                nxt = target - 1
                if not is_unstable(world, x, target, z):
                    break
                if _wool_at(x, nxt, z):
                    break
                # Never overwrite existing rail: wool from a second trace that
                # descends onto the first line STOPS on it (rail is a support).
                # Otherwise the crossing coral is replaced by wool and the first
                # line breaks ("almost no rail left" at crossed segments).
                if world.get(x, nxt, z) in RAIL_FAMILY:
                    break
                target = nxt
            if target != y:
                world.set(x, y, z, AIR)
                world.set(x, target, z, own)
                _move_trace_wool((x, y, z), (x, target, z))
                y = target
            # JUNCTION: the wool stopped ON a decor column of another line
            # [support soil + decor, NO core]. Without reclaiming it, this rail
            # would sit two blocks above its neighbours, breaking the ribbon at
            # the crossing. The column is reclaimed: the wool descends INTO the
            # support base and its core replaces the decor, keeping the crossing
            # continuous and level.
            if not is_unstable(world, x, y, z):
                d1 = world.get(x, y - 1, z)
                d2 = world.get(x, y - 2, z)
                d3 = world.get(x, y - 3, z)
                if (d1 in DECOR_TOKENS
                        and (d2 in SOIL_TOKENS or d2 == "orange_wool")
                        and d3 not in (None, AIR, "water")
                        and not is_wool(d3) and d3 not in NATURE_CORES):
                    world.set(x, y, z, AIR)
                    world.set(x, y - 1, z, AIR)
                    world.set(x, y - 2, z, own)
                    _move_trace_wool((x, y, z), (x, y - 2, z))
                    y = y - 2
        moved_trace.append((x, y, z))
    return moved_trace

# =============================================================================
# 4. ANALYSIS: geometric N-S / E-W / DIAG classification (+ colour overrides)
# =============================================================================

NS, EW, DIAG = "NS", "EW", "DIAG"
DY = (0, 1, -1)  # vertical tolerance order used by the scripts

COLOR_OVERRIDE = {"red_wool": NS, "blue_wool": EW, "lime_wool": DIAG}

class TrackModel:
    """Analysis model: the trace voxels plus their effective types."""

    def __init__(self, world, trace_voxels):
        self.world = world
        self.trace = set(trace_voxels)
        self.types = {v: self._classify(v) for v in trace_voxels}

    def is_trace(self, x, y, z):
        # "Is this a voxel of the current trace?" reads the trace SET, not the
        # live world: during the build, once a voxel has been replaced
        # (coral/lectern/grass), its diagonal neighbours vanish and neighbouring
        # corner analysis changes on the fly (the leaf_2 bug with X or Z drift).
        return (x, y, z) in self.trace

    def type_at(self, x, y, z):
        """Effective type of a voxel (None when not on the trace)."""
        v = (x, y, z)
        if v in self.types:
            return self.types[v]
        st = self.world.get(x, y, z)
        if st == "coral_south":
            return NS
        if st == "coral_east":
            return EW
        return None

    def type_near(self, x, y, z):
        """First type found with a dy {0,1,-1} tolerance, in script order."""
        for dy in DY:
            t = self.type_at(x, y + dy, z)
            if t is not None:
                return t
        return None

    def _neighbors_of(self, x, y, z):
        """Directions (dx,dz) in which the trace continues (+-1y tolerance)."""
        out = set()
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx == 0 and dz == 0:
                    continue
                for dy in DY:
                    if self.is_trace(x + dx, y + dy, z + dz):
                        out.add((dx, dz))
                        break
        return out

    def _classify(self, voxel):
        x, y, z = voxel
        st = self.world.get(x, y, z)
        if st in COLOR_OVERRIDE:
            return COLOR_OVERRIDE[st]
        # Built rail: the index block (coral/lectern) sits at y or y+1 after the
        # build, the trace voxel having become the column floor.
        for dy in (0, 1):
            above = self.world.get(x, y + dy, z)
            if above == "coral_south" or above == "lectern_north":
                return NS
            if above == "coral_east" or above == "lectern_east":
                return EW
        v = self._neighbors_of(x, y, z)
        n, s = (0, -1) in v, (0, 1) in v
        e, o = (1, 0) in v, (-1, 0) in v
        ne, no = (1, -1) in v, (-1, -1) in v
        se, so = (1, 1) in v, (-1, 1) in v
        if (n and s) or (n and se) or (n and so) or (s and ne) or (s and no):
            return NS
        if (e and o) or (e and no) or (e and so) or (o and ne) or (o and se):
            return EW
        if (ne and so) or (no and se):
            return DIAG
        # Fallbacks for a non-rectified trace or dense patterns
        if n or s:
            return NS
        if e or o:
            return EW
        if v:
            return DIAG
        return NS  # isolated block


# =============================================================================
# 5. AGENTS (directional scans along the trace, faithful port of the scripts)
# =============================================================================

MAX_LINE_SCAN = 20
MAX_DIAG_SCAN = 64  # the script loops forever; safety cap

class Agent:
    def __init__(self):
        self.dist = 0
        self.exit_dir = "none"  # "east"/"west"/"south"/"north"
        self.exit_len = 1       # 2 = continues past the offset, 1 = sharp turn
        self.exit_type = None   # diagonals only: NS / EW / None


def scan_straight(model, x, y, z, want_type, step, side1, side1_name, side2, side2_name):
    """Axial scan (NS or EW line). step is the walking direction, side tests a turn."""
    agent = Agent()
    cur_y = y
    for i in range(1, MAX_LINE_SCAN + 1):
        nx, nz = x + step[0] * i, z + step[1] * i
        found = False
        for dy in DY:
            if model.type_at(nx, cur_y + dy, nz) == want_type:
                agent.dist += 1
                cur_y += dy
                found = True
                break
        if found:
            continue
        for (sdx, sdz), name in ((side1, side1_name), (side2, side2_name)):
            for dy in DY:
                if model.type_at(nx + sdx, cur_y + dy, nz + sdz) == want_type:
                    agent.exit_dir = name
                    vy = cur_y + dy
                    cont = any(model.type_at(nx + sdx + step[0], vy + dy2, nz + sdz + step[1]) == want_type
                               for dy2 in DY)
                    agent.exit_len = 2 if cont else 1
                    return agent
        break
    return agent


OPPOSITE_NS = {"east": "side_west", "west": "side_east"}
OPPOSITE_EW = {"south": "side_north", "north": "side_south"}


def decide_side_ns(agent_nord, agent_sud):
    """Wall versus side-block decision for a N-S line (exact script 3 logic)."""
    length = agent_nord.dist + agent_sud.dist + 1
    pos = agent_sud.dist + 1  # 1-based position from the south
    if length <= 1:
        return "wall_ns"
    if length == 2:
        if pos == 1:
            return OPPOSITE_NS.get(agent_sud.exit_dir, "wall_ns")
        if agent_nord.exit_len == 1:
            return OPPOSITE_NS.get(agent_nord.exit_dir, "wall_ns")
        return "wall_ns"
    if length <= 40:
        q, r = divmod(length, 3)
        if pos <= q:
            return OPPOSITE_NS.get(agent_sud.exit_dir, "wall_ns")
        if pos > 2 * q + r:
            return OPPOSITE_NS.get(agent_nord.exit_dir, "wall_ns")
    return "wall_ns"


def decide_side_ew(agent_ouest, agent_est):
    length = agent_ouest.dist + agent_est.dist + 1
    pos = agent_est.dist + 1  # 1-based position from the east
    if length <= 1:
        return "wall_eo"
    if length == 2:
        if pos == 1:
            return OPPOSITE_EW.get(agent_est.exit_dir, "wall_eo")
        if agent_ouest.exit_len == 1:
            return OPPOSITE_EW.get(agent_ouest.exit_dir, "wall_eo")
        return "wall_eo"
    if length <= 40:
        q, r = divmod(length, 3)
        if pos <= q:
            return OPPOSITE_EW.get(agent_est.exit_dir, "wall_eo")
        if pos > 2 * q + r:
            return OPPOSITE_EW.get(agent_ouest.exit_dir, "wall_eo")
    return "wall_eo"


def diag_sense(model, x, y, z):
    """'swne' or 'senw', same priorities as the script (diagonal > axial > default)."""
    if model.type_near(x + 1, y, z - 1) == DIAG or model.type_near(x - 1, y, z + 1) == DIAG:
        return "swne"
    t_ne = model.type_at(x + 1, y, z - 1)  # y exact
    t_so = model.type_at(x - 1, y, z + 1)
    if t_ne in (NS, EW) or t_so in (NS, EW):
        return "swne"
    return "senw"


def scan_diag(model, x, y, z, sdx, sdz):
    """Scans along a diagonal and detects the connection type at the end."""
    agent = Agent()
    cur_y = y
    for i in range(1, MAX_DIAG_SCAN + 1):
        nx, nz = x + sdx * i, z + sdz * i
        found = False
        for dy in DY:
            if model.type_at(nx, cur_y + dy, nz) == DIAG:
                agent.dist += 1
                cur_y += dy
                found = True
                break
        if found:
            continue
        ext = None
        for dy in DY:
            t = model.type_at(nx, cur_y + dy, nz)
            if t == NS:
                ext = NS
                break
            if t == EW:
                ext = EW
                break
        agent.exit_type = ext
        break
    return agent


def diag_design(model, x, y, z):
    """Decides how a DIAG block is rendered: (coral, [(dx,dz,state)])."""
    sense = diag_sense(model, x, y, z)
    swne = sense == "swne"
    # Walking vectors towards the "north" (z-) and "south" (z+) of the diagonal
    if swne:
        n = scan_diag(model, x, y, z, +1, -1)
        s = scan_diag(model, x, y, z, -1, +1)
    else:
        n = scan_diag(model, x, y, z, -1, -1)
        s = scan_diag(model, x, y, z, +1, +1)
    w1, w2 = ("wall_nw", "wall_se") if swne else ("wall_sw", "wall_ne")

    four = [(-1, 0, w1), (1, 0, w2), (0, -1, w1), (0, 1, w2)]
    false_diag = (n.exit_type is not None and n.exit_type == s.exit_type)

    if false_diag:
        coral = "coral_south" if n.exit_type == NS else "coral_east"
        return coral, four

    length = n.dist + s.dist + 1
    mid = length // 2
    rel = s.dist + 1
    if length == 1:
        coral, transition = "coral_south", True
    else:
        ext = s.exit_type if rel <= mid else n.exit_type
        coral = {NS: "coral_south", EW: "coral_east"}.get(ext)
        transition = (rel == mid) or (rel == mid + 1)

    if coral is None:
        # Undetermined (both exits empty): an NS transition fragment rather than
        # the black_wool sentinel, so the rail stays continuous in game.
        return "coral_south", four
    if transition:
        return coral, four
    if coral == "coral_south":
        return coral, [(-1, 0, w1), (1, 0, w2)]
    return coral, [(0, -1, w1), (0, 1, w2)]

# =============================================================================
# 6. CONSTRUCTION
# =============================================================================

# Track decor (never a core): a junction may reclaim it.
DECOR_TOKENS = {"wall_ns", "wall_eo", "wall_ne", "wall_nw", "wall_se", "wall_sw",
                "side_east", "side_west", "side_north", "side_south",
                "pale_moss_carpet", "button_north", "button_east",
                "door_lower", "door_upper"} \
    | {f"door_{h}_{f}" for h in ("lower", "upper")
       for f in ("north", "south", "east", "west")} \
    | {f"leaf_{a}_{f}" for a in (1, 2, 3, 4)
       for f in ("north", "south", "east", "west")}
# Support base placed by the designs, reusable as a junction base.
SOIL_TOKENS = {"deepslate", "cobbled_deepslate", "pale_oak_wood",
               "deepslate_iron_ore", "deepslate_coal_ore", "gravel"}

PROTECTED = {"wall_ns", "wall_eo", "wall_ne", "wall_nw", "wall_se", "wall_sw",
             "side_east", "side_west", "side_north", "side_south",
             "coral_south", "coral_east", "black_wool",
             "lectern_north", "lectern_east", "pale_moss_carpet", "pale_moss_block",
             "button_north", "button_east",
             "door_lower", "door_upper", "gravel"} | {f"door_{h}_{f}" for h in ("lower", "upper")
                                            for f in ("north", "south", "east", "west")} | {f"leaf_{a}_{f}"
                                            for a in (1, 2, 3, 4)
                                            for f in ("north", "south", "east", "west")}

DOOR_FACING = {"side_north": "door_north", "side_south": "door_south",
               "side_east": "door_east", "side_west": "door_west"}

random.seed(42)
SOIL_MIX = [("deepslate", 45), ("cobbled_deepslate", 40), ("pale_oak_wood", 10),
            ("deepslate_iron_ore", 4), ("deepslate_coal_ore", 2)]
_total = sum(p for _, p in SOIL_MIX)
SOIL_MIX = [(b, p / _total) for b, p in SOIL_MIX]

class Options:
    def __init__(self, style="classic", theme=1, fill_mode=2, buried=False):
        self.style = style      # "classic" | "nature"
        self.theme = theme      # 1 = sombre, 2 = clair
        self.fill_mode = fill_mode  # 1 = uniform (orange_wool), 2 = random
        self.base_dy = -1 if buried else 0

def pick_soil(opt):
    if opt.fill_mode == 1:
        return "orange_wool"
    n = random.random()
    acc = 0.0
    for block, p in SOIL_MIX:
        acc += p
        if n <= acc:
            return block
    return SOIL_MIX[-1][0]


def build_column(world, opt, x, y, z, center):
    """Places soil, core and headroom as the script does, never rewriting an
    existing rail. All three levels of the column are protected, so a rebuild
    is idempotent. Light theme: the side block is a door PANEL (lower half,
    y+1 only) as in the script, not a full two-block door."""
    start_y = y + opt.base_dy
    is_core_write = center in NATURE_CORES
    for yy in (start_y, start_y + 1, start_y + 2):
        # Two-tier guard: (1) an existing core is NEVER overwritten; (2) a
        # DECOR write yields to any existing rail block, otherwise a rebuild
        # would turn walls into panels or doors (decor churn, orphan doors).
        # A CORE write may reclaim a decor cell, so dense junctions never lose
        # their visible rail.
        cur = world.get(x, yy, z)
        if cur in NATURE_CORES or (not is_core_write
                                   and cur in RAIL_FAMILY):
            if os.environ.get("RAIL_DEBUG"):
                print(f"[COLREFUSE] {x},{yy},{z} center={center}"
                      f" cur={world.get(x, yy, z)}")
            # The voxel wool is consumed anyway: its core is exactly the block
            # occupying the cell (a crossing shares the column), otherwise the
            # wool would stay visible forever without rail.
            if world.get(x, start_y, z) == "white_wool":
                # Strictly the fresh trace wool, never a wool fill or a
                # decorative player wool.
                world.set(x, start_y, z, AIR)
            return
    # A core only takes the core cell of an existing decor: the support soil
    # and the headroom air must never overwrite a wall, panel or door of
    # another line (C7).
    if world.get(x, start_y + 2, z) not in RAIL_FAMILY:
        world.set(x, start_y + 2, z, AIR)
    if opt.theme == 2 and center in DOOR_FACING:
        f = DOOR_FACING[center].split("_", 1)[1]
        world.set(x, start_y + 1, z, f"door_lower_{f}")
    else:
        world.set(x, start_y + 1, z, center)
    if world.get(x, start_y, z) not in RAIL_FAMILY:
        world.set(x, start_y, z, pick_soil(opt))


LEAF_FACING = {(1, -1): "east", (-1, -1): "south", (1, 1): "north", (-1, 1): "west"}

RAIL_FAMILY = {"wall_ns", "wall_eo", "wall_ne", "wall_nw", "wall_se", "wall_sw",
               "side_east", "side_west", "side_north", "side_south",
               "coral_south", "coral_east", "gravel",
               "lectern_north", "lectern_east", "pale_moss_carpet", "pale_moss_block",
               "button_north", "button_east",
               "door_lower", "door_upper",
               } | {f"door_{h}_{f}" for h in ("lower", "upper")
                    for f in ("north", "south", "east", "west")} | {f"leaf_{a}_{f}" for a in (1, 2, 3, 4)
                                              for f in ("north", "south", "east", "west")}

# Rail blocks present BEFORE the current build (nature-specific protection).
rail_before = set()


def nature_decor_set(world, x, y, z, state):
    """Places visible decor, unless the cell above is a hostile solid (not
    rail, air or water): the decor would be buried, which is the "rail
    missing" case of photo 2 (litter or ortho block under grass)."""
    above = world.get(x, y + 1, z)
    if above not in (AIR, None) and above not in RAIL_FAMILY and above != "water":
        return
    nature_set(world, x, y, z, state)


NATURE_CORES = {"lectern_north", "lectern_east", "pale_moss_block",
                "coral_south", "coral_east", "black_wool"}


def _rail_prio(st):
    """Priority inside one build: a CORE always wins (3), decor yields (2) and
    gravel is the passive bed (1). Without it, the litter of a low voxel lands
    in the core cell of a staircase's high voxel and the core is then refused,
    which was the missing-core failure of the nature fuzz runs."""
    if st in NATURE_CORES:
        return 3
    if st == "gravel":
        return 1
    return 2 if st in RAIL_FAMILY else 0


def nature_set(world, x, y, z, state):
    """Like world.set, but never overwrites a rail block:
    - rail present before the build (classic protection);
    - rail placed DURING this build by another voxel, with increasing priority
      (gravel < decor < core): a core overwrites a decor that claimed its cell
      first, never the opposite, and gravel stays an upgradable bed.
    This is the anti-overlap fix: decor no longer pushes a neighbour's rail
    down (ties, knots, micro-drifts, staircases)."""
    cur = world.get(x, y, z)
    if (x, y, z) in rail_before and cur in RAIL_FAMILY:
        if cur in NATURE_CORES:
            return                       # never overwrite a core
        if _rail_prio(state) < 3:
            # A later decor touches neither the decor nor the gravel bed of a
            # previous line, but a CORE reclaims it: at a junction the visible
            # rail comes before the neighbouring litter or wall.
            return
    if cur == state:
        return
    if _rail_prio(cur) > _rail_prio(state):
        return
    if _rail_prio(cur) == _rail_prio(state) and cur in RAIL_FAMILY:
        return
    world.set(x, y, z, state)

def ordered_neighbors(model, x, y, z):
    """Direction names of the trace neighbours, in script collection order."""
    out = []
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            if dx == 0 and dz == 0:
                continue
            for dy in DY:
                if model.is_trace(x + dx, y + dy, z + dz):
                    name = ""
                    if dz == -1:
                        name = "N"
                    elif dz == 1:
                        name = "S"
                    if dx == 1:
                        name += "E"
                    elif dx == -1:
                        name += "O"
                    out.append(name)
                    break
    return out


def pm(d1, d2, a, b):
    return (d1 == a and d2 == b) or (d1 == b and d2 == a)


LEAF_NS = [
    (("N", "S"), (2, "north", 2, "south")),
    (("N", "SE"), (3, "south", 2, "south")),
    (("N", "SO"), (2, "north", 3, "east")),
    (("S", "NE"), (3, "west", 2, "south")),
    (("S", "NO"), (2, "north", 3, "north")),
    (("NE", "SO"), (3, "west", 3, "east")),
    (("NO", "SE"), (3, "south", 3, "north")),
]
LEAF_EW = [
    (("O", "E"), (2, "west", 2, "east")),
    (("E", "NO"), (3, "south", 2, "east")),
    (("O", "NE"), (3, "east", 2, "east")),
    (("O", "SE"), (2, "west", 3, "north")),
    (("E", "SO"), (2, "west", 3, "west")),
    (("NE", "SO"), (3, "east", 3, "west")),
    (("NO", "SE"), (3, "south", 3, "north")),
]

_CARD_NS = ({"N", "NE", "NO"}, {"S", "SE", "SO"})
_CARD_HO = ({"E", "NE", "SE"}, {"O", "NO", "SO"})


def leaf_pair(table, d1, d2):
    """Values (a1, f1, a2, f2) for a neighbour pair (exact Rouquinator script).
    For pairs the script never covers (sharp turns, degenerate traces) the
    extension is consistent: a 3-segment litter facing the inside of the turn."""
    for pair, val in table:
        if pm(d1, d2, *pair):
            return val
    pair = {d1, d2}
    n_dom = bool(pair & _CARD_NS[0])
    s_dom = bool(pair & _CARD_NS[1])
    f_1 = "south" if (n_dom and not s_dom) else "north"
    e_dom = bool(pair & _CARD_HO[0])
    o_dom = bool(pair & _CARD_HO[1])
    if e_dom and not o_dom:
        f_2 = "east"
    elif o_dom and not e_dom:
        f_2 = "west"
    else:
        f_2 = f_1
    return (3, f_1, 3, f_2)


def build_nature_block(model, world, opt, x, y, z, t):
    """Places the nature rail for an NS or EW voxel (port of script 4)."""
    if t == NS:
        moss_offsets = [(0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)]
        facing = "north"
        table = LEAF_NS
        ortho = [(1, 0), (-1, 0)]          # gravel east and west
        cross_quads = [(1, -1), (-1, -1), (1, 1), (-1, 1)]
        leaf_pos = [(1, 0), (-1, 0)]
    else:
        moss_offsets = [(1, 0), (-1, 0), (1, 1), (-1, 1), (1, -1), (-1, -1)]
        facing = "east"
        table = LEAF_EW
        ortho = [(0, 1), (0, -1)]
        cross_quads = []
        leaf_pos = [(0, -1), (0, 1)]

    is_moss = any(model.type_at(x + dx, y - 1, z + dz) == t for dx, dz in moss_offsets)
    if not is_moss:
        nature_set(world, x, y, z, f"lectern_{facing}")
        nature_decor_set(world, x, y + 1, z, "pale_moss_carpet")
    else:
        nature_set(world, x, y, z, "pale_moss_block")
        nature_decor_set(world, x, y + 1, z, f"button_{facing}")

    for dx, dz in ortho:
        nature_decor_set(world, x + dx, y, z + dz, "gravel")

    # Red/blue crossings (nature design, N-S lines only)
    if t == NS:
        for dx, dz in cross_quads:
            for dy in (0, 1, -1):
                if model.type_at(x + dx, y + dy, z + dz) == EW:
                    nature_decor_set(world, x, y + dy, z + dz, "gravel")
                    nature_decor_set(world, x, y + dy + 1, z + dz, f"leaf_3_{LEAF_FACING[(dx, dz)]}")

    nb = ordered_neighbors(model, x, y, z)
    d1 = nb[0] if len(nb) > 0 else ""
    d2 = nb[1] if len(nb) > 1 else ""
    if len(nb) >= 2:
        # Corner drift (the section slides by one in X or Z): the straight pair
        # (N,S)/(E,O) hides the diagonal neighbour, so substitute it to get the
        # 3-segment litter of the script.
        diag_nb = [d for d in nb if d in ("NE", "NO", "SE", "SO")]
        if diag_nb and ({d1, d2} == {"N", "S"} or {d1, d2} == {"E", "O"}):
            d2 = diag_nb[0]
        a1, f1, a2, f2 = leaf_pair(table, d1, d2)
    else:
        a1, f1, a2, f2 = (2, "north", 2, "south") if t == NS else (2, "west", 2, "east")
    (dx1, dz1), (dx2, dz2) = leaf_pos
    nature_decor_set(world, x + dx1, y + 1, z + dz1, f"leaf_{a1}_{f1}")
    nature_decor_set(world, x + dx2, y + 1, z + dz2, f"leaf_{a2}_{f2}")


def orphan_diag_conversion(model, x, y, z):
    """A DIAG voxel with no diagonal TRACE neighbour (an isolated perpendicular
    fragment, e.g. a trace jumping from one line) renders as an ugly four-wall
    coral of the other direction. Convert it to the dominant axial type, NS by
    default. The criterion is geometric (raw trace, not the types), so it is
    stable across rebuilds: the coral indices of the first build cannot
    reclassify a neighbour and cause churn, keeping the rebuild idempotent."""
    nb = model._neighbors_of(x, y, z)
    for dx, dz in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        if (dx, dz) in nb:
            return None
    has_ns = model.type_near(x, y, z - 1) == NS or model.type_near(x, y, z + 1) == NS
    has_ew = model.type_near(x + 1, y, z) == EW or model.type_near(x - 1, y, z) == EW
    if has_ew and not has_ns:
        return EW
    return NS


def support_fill(world, opt, pre_keys, depth_max=4):
    """Fills under floating rail blocks (blocked descent, residual teeth,
    control drift): no block placed by this build keeps air right below it,
    including a surviving block whose support was dug out by a later pass.
    The script always lays the track on the ground, and this keeps that
    contract up to depth_max blocks of fill. Residual gaps are 1 to 4 blocks
    deep; a deliberately high line is a legitimate bridge, not a tooth."""
    # Column bases (soil or the supports of earlier passes) get the same pillar
    # as the rails: a dirt stump in mid-air beside a pillar would be a visible
    # wart in the screenshots.
    placed = [(pos, st) for pos, st in world.blocks.items()
              if st in RAIL_FAMILY or st in SOIL_TOKENS
              or (opt.fill_mode == 1 and st == "orange_wool")]
    for (x, y, z), st in placed:
        # Measure the real gap first: deeper than depth_max means a legitimate
        # bridge, and a truncated fill would itself leave 1-4 blocks of air and
        # bring the bug back. Otherwise fill it all.
        gap = 0
        # Water is not a support: rail above a pond floats, so the pillar
        # crosses the water down to the bed (<= depth_max, else a bridge).
        while (world.get(x, y - 1 - gap, z) in (AIR, None, "water")
               and gap < 64):
            gap += 1
        if gap == 0 or gap > depth_max:
            continue
        soil = "gravel" if opt.style == "nature" else pick_soil(opt)
        for yy in range(y - 1, y - 1 - gap, -1):
            if world.get(x, yy, z) in (AIR, None, "water"):
                world.set(x, yy, z, soil)


def build_all(world, trace, opt):
    global rail_before
    rail_before = {pos for pos, st in world.blocks.items() if st in RAIL_FAMILY}
    pre_keys = set(world.blocks.keys())
    model = TrackModel(world, trace)

    # The classic design runs in TWO passes: every core (coral) first, then the
    # side decor (walls, panels). Otherwise a neighbour's side lands in the core
    # cell of the voxel before it and the intact-column guard loses the coral,
    # the "almost no rail left" symptom on drifting lines with close jogs.
    # With separate passes the core always gets its cell and the decor, emitted
    # afterwards, yields to any rail already present (per-cell guard).
    classic_centers = []
    classic_sides = []

    for v in [v for v in trace if model.types.get(v) == DIAG]:
        conv = orphan_diag_conversion(model, *v)
        if conv is not None:
            model.types[v] = conv
    for (x, y, z) in [v for v in trace if model.types.get(v) == DIAG]:
        if opt.style == "classic":
            coral, sides = diag_design(model, x, y, z)
            if coral is None:
                classic_centers.append((x, y, z, "black_wool"))
                continue
            classic_centers.append((x, y, z, coral))
            for dx, dz, w in sides:
                classic_sides.append((x + dx, y, z + dz, w))
        else:
            # Nature design: the diagonal is converted from its end point.
            c, _ = diag_design(model, x, y, z)
            t = NS if c != "coral_east" else EW
            build_nature_block(model, world, opt, x, y, z, t)
    for (x, y, z) in [v for v in trace if model.types.get(v) == NS]:
        if opt.style == "classic":
            n = scan_straight(model, x, y, z, NS, (0, -1), (1, 0), "east", (-1, 0), "west")
            s = scan_straight(model, x, y, z, NS, (0, +1), (1, 0), "east", (-1, 0), "west")
            side = decide_side_ns(n, s)
            classic_centers.append((x, y, z, "coral_south"))
            classic_sides.append((x - 1, y, z, side))
            classic_sides.append((x + 1, y, z, side))
        else:
            build_nature_block(model, world, opt, x, y, z, NS)
    for (x, y, z) in [v for v in trace if model.types.get(v) == EW]:
        if opt.style == "classic":
            o = scan_straight(model, x, y, z, EW, (-1, 0), (0, +1), "south", (0, -1), "north")
            e = scan_straight(model, x, y, z, EW, (+1, 0), (0, +1), "south", (0, -1), "north")
            side = decide_side_ew(o, e)
            classic_centers.append((x, y, z, "coral_east"))
            classic_sides.append((x, y, z - 1, side))
            classic_sides.append((x, y, z + 1, side))
        else:
            build_nature_block(model, world, opt, x, y, z, EW)

    for (x, y, z, c) in classic_centers:
        build_column(world, opt, x, y, z, c)
    for (x, y, z, w) in classic_sides:
        build_column(world, opt, x, y, z, w)
    support_fill(world, opt, pre_keys)
    return model

# =============================================================================
# 7. RENDU ASCII
# =============================================================================

USE_COLOR = "--no-color" not in sys.argv

def ansi(code):
    return lambda s: f"\033[{code}m{s}\033[0m" if USE_COLOR else s

BLUE, MAG, YEL, GRN, GRY, RED, ORA, WHT = (ansi(c) for c in
    ("34", "35;1", "33", "32", "90", "31;1", "38;5;208", "37;1"))

GLYPHS = {
    "wall_ns": BLUE("│"), "wall_eo": BLUE("─"),
    "wall_ne": BLUE("└"), "wall_nw": BLUE("┘"), "wall_se": BLUE("┌"), "wall_sw": BLUE("┐"),
    "side_north": YEL("n"), "side_south": YEL("s"), "side_east": YEL("e"), "side_west": YEL("w"),
    "coral_south": MAG("S"), "coral_east": MAG("E"),
    "black_wool": RED("X"),
    "white_wool": WHT("W"), "red_wool": WHT("R"), "blue_wool": WHT("B"), "lime_wool": WHT("V"),
    "orange_wool": ORA("o"),
    "deepslate": GRY("░"), "cobbled_deepslate": GRY("▒"), "pale_oak_wood": GRY("▓"),
    "deepslate_iron_ore": GRY("i"), "deepslate_coal_ore": GRY("c"),
    "gravel": ORA("g"),
    "lectern_north": ORA("N"), "lectern_east": ORA("E"),
    "pale_moss_block": GRN("M"), "pale_moss_carpet": GRN("~"),
    "button_north": GRN("+"), "button_east": GRN("+"),
    "grass_block": " ",
}
for a in "1234":
    for f in ("north", "south", "east", "west"):
        GLYPHS[f"leaf_{a}_{f}"] = GRN("'" if a == "2" else '"')


def topmost_glyph(world, x, z, min_y=-64, max_y=64):
    for y in range(max_y, min_y - 1, -1):
        st = world.get(x, y, z)
        if st != AIR:
            return GLYPHS.get(st, "?")
    return " "


def layer_glyph(world, x, y, z):
    st = world.get(x, y, z)
    if st == AIR or st == GROUND:
        return " "
    return GLYPHS.get(st, "?")


def render(world, trace_voxels, title, subtitle=""):
    xs = [p[0] for p in world.positions()]
    zs = [p[2] for p in world.positions()]
    if not xs:
        return
    x0, x1, z0, z1 = min(xs) - 1, max(xs) + 1, min(zs) - 1, max(zs) + 1

    # Layers holding non-terrain rail blocks inside the bounding box
    layers = {}
    for (x, y, z), st in world.blocks.items():
        if st not in (GROUND, AIR):
            layers.setdefault(y, []).append((x, z))
    layers = {y: pts for y, pts in layers.items()
              if any(x0 <= x <= x1 and z0 <= z <= z1 for x, z in pts)}

    print(f"\n=== {title} ===")
    if subtitle:
        print(subtitle)

    if "--layers" in sys.argv or style_nature_requested():
        for y in sorted(layers):
            print(f"-- layer y={y} --")
            for z in range(z0, z1 + 1):
                print("".join(layer_glyph(world, x, y, z) for x in range(x0, x1 + 1)))
    else:
        print("(top view; add --layers for the per-layer detail)")
        for z in range(z0, z1 + 1):
            print("".join(topmost_glyph(world, x, z) for x in range(x0, x1 + 1)))


def style_nature_requested():
    return "--style" in sys.argv and "nature" in sys.argv


LEGEND = """
Legend (classic design):
  S/E  dead_bubble_coral_wall_fan facing south/east (N-S or E-W rail centre)
  │ ─  axial wall (mud_brick_wall / andesite_wall, tall connections)
  └ ┘ ┌ ┐  corner walls (diagonals and false diagonals)
  n/s/e/w  oriented side block (spruce_shelf dark theme / iron_door light theme)
  ░▒▓ic  soil fill  ·  X = undetermined (black_wool, degenerate case)
Legend (nature design):
  N/E  lectern  ·  M pale_moss_block  ·  + button  ·  g gravel  ·  '~ leaf_litter
"""

# =============================================================================
# 8. SCENARIOS
# =============================================================================

SPLINE = "white_wool"
CORNER = "grass_block"


def flat_world(x0, x1, z0, z1, height_fn=None, depth=4):
    """Terrain: grass surface at height_fn(x,z) or 0, plus `depth` layers below."""
    w = World()
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            h = height_fn(x, z) if height_fn else 0
            for y in range(h - depth, h + 1):
                w.set(x, y, z, GROUND)
    return w


def scenario(name, control, opt, pad=3, colorize=None, subtitle="", height_fn=None):
    """Samples the spline, rectifies and builds it, then renders the result."""
    floats = adaptive_sample(control)
    vox = voxelize(floats)
    xs = [v[0] for v in vox]
    zs = [v[2] for v in vox]
    w = flat_world(min(xs) - pad, max(xs) + pad, min(zs) - pad, max(zs) + pad,
                   height_fn=height_fn)
    for x, y, z in vox:
        w.set(x, y, z, SPLINE)
    # Rectification: levelling, "L" cleanup, levelling again
    trace = list(vox)
    trace = rectify_vertical(w, trace, SPLINE, CORNER)
    trace = rectify_l(w, trace, SPLINE, CORNER)
    trace = rectify_vertical(w, trace, SPLINE, CORNER)
    if colorize:
        colorize(w, trace)
    build_all(w, trace, opt)
    render(w, trace, name, subtitle)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    style = "nature" if "--style" in sys.argv and "nature" in sys.argv else "classic"
    only = set(args)

    def want(name):
        return not only or name in only

    if want("straight-ns"):
        scenario("Straight N-S line (13 blocks)", [(8, 1, 20), (8, 1, 8)],
                 Options(style=style), subtitle="Coral S at the centre, walls on both sides.")

    if want("straight-ew"):
        scenario("Straight E-W line (13 blocks)", [(5, 1, 8), (17, 1, 8)],
                 Options(style=style))

    if want("halfturn"):
        # N-S, short L then an eastward turn: side_west expected at the ends if L=2
        scenario("N-S -> E turn (smooth curve)",
                 [(10, 1, 24), (10, 1, 8), (26, 1, 4)],
                 Options(style=style),
                 subtitle="Yellow side blocks at the line ends where the trace turns.")

    if want("s-curve"):
        scenario("Wide S curve (N-S -> E -> N-S)",
                 [(8, 1, 36), (8, 1, 22), (20, 1, 12), (20, 1, 2)],
                 Options(style=style),
                 subtitle="The key test: lines, wall/side thirds, mixed diagonals.")

    if want("diag-true"):
        # Canonical connected hand trace: 6 N-S, 5 strict SW-NE diagonal, 6 E-W.
        w = flat_world(0, 24, 0, 24)
        trace = [(10, 1, z) for z in range(14, 20)]           # N-S segment (south)
        trace += [(10 + i, 1, 14 - i) for i in range(1, 6)]   # strict SW->NE diagonal
        trace += [(x, 1, 8) for x in range(16, 22)]           # E-W segment (north)
        for x, y, z in trace:
            w.set(x, y, z, SPLINE)
        build_all(w, trace, Options(style=style))
        render(w, trace, "True diagonal (N-S / strict diag / E-W)",
               "Coral S on the south half, coral E on the north half, 4-wall transition between.")

    if want("diag-false"):
        # Hand trace: 5 N-S, 4 strict diagonal, 5 N-S, giving a false diagonal.
        w = flat_world(0, 16, 0, 22)
        trace = [(8, 1, z) for z in range(13, 18)]
        trace += [(8 + i, 1, 13 - i) for i in range(1, 5)]
        trace += [(12, 1, z) for z in range(4, 9)][::-1]
        for x, y, z in trace:
            w.set(x, y, z, SPLINE)
        build_all(w, trace, Options(style=style))
        render(w, trace, "False diagonal (N-S on both sides)",
               "Coral S over the whole diagonal plus four corner walls per block.")

    if want("corner-90"):
        scenario("Sharp 90 degree turn", [(6, 1, 16), (6, 1, 6), (16, 1, 6)],
                 Options(style=style))

    if want("slope"):
        # Staircase terrain with the spline drawn above it: rectification lays
        # the rail back on the ground.
        def hill(x, z):
            if z >= 18: return 0
            if z >= 12: return 2
            if z >= 6:  return 4
            return 4
        scenario("Sloped N-S line (stepped terrain, +0 then +2 then +4)",
                 [(8, 5, 22), (8, 5, 12), (8, 7, 6), (8, 8, 2)],
                 Options(style=style), height_fn=hill,
                 subtitle="Nature design: pale_moss_block and button on the rises and drops.")

    if want("crossing"):
        if style != "nature":
            print("(the 'crossing' scenario uses the nature design: rerun with --style nature)")
        w = flat_world(0, 14, 0, 14)
        for z in range(1, 14):
            w.set(7, 1, z, "red_wool")
        for x in range(1, 14):
            w.set(x, 1, 7, "blue_wool")
        trace = [(7, 1, z) for z in range(1, 14)] + [(x, 1, 7) for x in range(1, 14)]
        build_all(w, trace, Options(style="nature"))
        render(w, trace, "N-S x E-W crossing (nature design)",
               "Gravel and leaf_litter at the intersection.")

    print(LEGEND)


if __name__ == "__main__":
    main()
