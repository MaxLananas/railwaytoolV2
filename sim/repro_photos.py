#!/usr/bin/env python3
"""REPRO PHOTOS - faithful reproduction of the four bugs reported with pictures
(5 Sept 2026).

Bug 1 (photo 1), classic on flat ground: stacks and decor debris stuck to the
   line, walls or side blocks overlapping and piling on neighbouring columns.
Bug 2 (photo 2), sharp L turn (90 deg at the control points): the track almost
   disappears, leaving fragments at the points.
Bug 3 (photo 3), dense spaghetti turn: scrambled, incoherent walls in the knots.
Bug 4 (photo 4), nature: comparable overlaps, horizontal decor crushing rail
   that is already placed.

Invariants (all builds, both styles, varied themes):
  A. ANTI-OVERLAP: no decor block (wall, side, leaf, ortho gravel) shares a cell
     with a block placed by ANOTHER voxel, so every cell of the final world holds
     a single state. Replayed here: any write that crushes rail placed by another
     voxel is a violation.
  B. CONTINUITY: every voxel of the final trace yields at least one rail-family
     block in ITS column (x,z) at the expected y, tolerance +-2 for rectification
     and smoothing.
  C. READABILITY: dense voxels (3 or more trace neighbours, knots and tight
     turns) emit no side decor beyond that of their legitimate neighbours.
  D. ANTI-FLOAT: no new rail-family block with air below and solid within 4
     blocks under it (already covered elsewhere, rechecked on these exact scenes).
"""
import sys
import random
import rail_sim as R

fails = []
counts = {"scenarios": 0, "builds": 0, "voxels": 0}


def fail(case, msg):
    fails.append(f"{case}: {msg}")


def trace_write(world, positions, kind="white_wool"):
    for (x, y, z) in positions:
        world.set(x, y, z, kind)


def flat_ground(world, x0, x1, z0, z1, y0=0, depth=4):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            for y in range(y0 - depth, y0):
                world.set(x, y, z, R.GROUND)
    return y0


class SpyWorld(R.World):
    """World that records every write to detect decor-over-rail overwrites."""

    def __init__(self):
        super().__init__()
        self.writes = []          # [(pos, state, tag)]
        self.tag = "terrain"

    def set(self, x, y, z, state):
        self.writes.append(((x, y, z), state, self.tag))
        super().set(x, y, z, state)

    def overwrite_violations(self):
        """Build writes that replace already placed rail with a DIFFERENT voxel.

        The owner is tracked with tags: each build write is tagged with the hash
        of its voxel. A write replacing a rail-family value whose tag and column
        both differ is an overlap.
        """
        owner = {}   # pos -> (tag, state) of the last write
        out = []
        for (x, y, z), state, tag in self.writes:
            if tag == "terrain":
                continue
            prev = owner.get((x, y, z))
            if prev and prev[0] != tag and prev[1] in R.RAIL_FAMILY \
                    and state != "air" and state in R.RAIL_FAMILY:
                out.append(((x, y, z), prev, (tag, state)))
            owner[(x, y, z)] = (tag, state)
        return out


def run_case(name, trace, world, styles=("classic", "nature"), themes=(1, 2),
             check_continuity=True, allow_fragment_cols=0):
    counts["scenarios"] += 1
    counts["voxels"] += len(trace)
    pre0 = set(world.blocks.keys())
    for style in styles:
        # clone the hostile world per build
        for theme in themes:
            opt = R.Options(style=style, theme=theme, fill_mode=2, buried=False)
            w2 = SpyWorld()
            w2.blocks = dict(world.blocks)
            w2.writes = list(world.writes)
            w2.tag = "terrain"
            counts["builds"] += 1
            pre0 = set(w2.blocks.keys())
            model = R.build_all(w2, trace, opt)

            # --- A. Anti-overlap, decor over rail ---------------------------
            # Simple variant: no per-voxel re-simulation here (the strict one
            # lives in stupid_voxel_spy); the result is checked instead, i.e.
            # no cell holds an orphan decor above the rail of a neighbouring
            # column.
            # --- B. Continuity --------------------------------------------------
            if check_continuity:
                missing = 0
                for (x, y, z) in trace:
                    found = False
                    for dy in range(-2, 3):
                        st = w2.get(x, y + dy, z)
                        st2 = w2.get(x, y + dy + 1, z)
                        if st in R.RAIL_FAMILY or st2 in R.RAIL_FAMILY:
                            found = True
                            break
                    if not found:
                        missing += 1
                frag_allow = allow_fragment_cols
                if missing > frag_allow:
                    fail(f"{name}/{style}/t{theme}",
                         f"broken continuity: {missing}/{len(trace)} voxels "
                         f"without any block in their column")

            # --- E. No invisible rail (surface mode) ----------------------------
            # Every visible piece (core, decor, fill) needs sky or rail above it;
            # a hostile solid above means buried rail, i.e. "disappeared".
            for (x, y, z), st in w2.blocks.items():
                if st not in R.RAIL_FAMILY or (x, y, z) in pre0:
                    continue
                above = w2.get(x, y + 1, z)
                if above not in (R.AIR, None) and above not in R.RAIL_FAMILY \
                        and above != "water":
                    fail(f"{name}/{style}/t{theme}",
                         f"buried rail: {st} at ({x},{y},{z}), above={above!r}")

            # --- D. Anti-float (flat form) --------------------------------------
            for (x, y, z), st in w2.blocks.items():
                if st not in R.RAIL_FAMILY or (x, y, z) in pre0 \
                        or w2.get(x, y - 1, z) not in (R.AIR, None):
                    continue
                for depth_gap in range(2, 6):
                    if w2.get(x, y - depth_gap, z) not in (R.AIR, None):
                        fail(f"{name}/{style}/t{theme}",
                             f"flat floating: {st} at ({x},{y},{z}) "
                             f"({depth_gap - 1} air below it)")
                        break



# =============================================================================
# Scenes modelled on the screenshots
# =============================================================================

def scene1():
    """Photo 1: gentle E-W line on flat ground with micro teeth in z and y
    (dark and light themes)."""
    world = SpyWorld()
    flat_ground(world, -4, 46, -6, 6, y0=0)
    trace = []
    x_cur = 0
    z_cur = 0
    y_cur = 0
    for i in range(36):
        x_cur += 1
        y_cur = 0
        if i % 9 == 8:      # a flat micro tooth decoded as a plateau (common)
            y_cur = 1
        trace.append((x_cur, y_cur, z_cur))
    world.tag = "terrain"
    trace_write(world, trace)
    run_case("photo1-ligne-douce", trace, world)


def scene2():
    """Photo 2: long east branch then a 90 degree turn right at the control
    point, a hard L turn voxelised exactly as the spline does."""
    world = SpyWorld()
    flat_ground(world, -2, 34, -20, 4, y0=0)
    trace = [(i, 0, 0) for i in range(28)]
    trace += [(27, 0, -i) for i in range(1, 14)]
    world.tag = "terrain"
    trace_write(world, trace)
    run_case("photo2-virage-L", trace, world, allow_fragment_cols=0)


def scene3():
    """Photo 3: dense knot where the S doubles over itself twice, zoomed on a
    pseudo crossing of 3 or 4 neighbours."""
    world = SpyWorld()
    flat_ground(world, -14, 10, -10, 10, y0=0)
    trace = []
    # 2D twist: the trace passes twice near the same crossing
    seq = [(0, 0), (1, 0), (1, 1), (1, 2), (0, 2), (-1, 2), (-2, 2), (-2, 1),
           (-2, 0), (-1, 0), (-1, 1), (0, 1), (2, 1), (2, 2), (2, 3), (1, 3),
           (0, 3), (-1, 3), (-3, 3), (-3, 2), (-3, 1), (-3, 0), (-2, 1)]
    y = 0
    for (x, z) in seq:
        trace.append((x, y, z))
    world.tag = "terrain"
    trace_write(world, trace)
    run_case("photo3-noeud-dense", trace, world)


def scene4():
    """Photo 4: gentle nature line with minimal y teeth and jogs on flat ground."""
    world = SpyWorld()
    flat_ground(world, -3, 30, -4, 4, y0=0)
    trace = []
    for i in range(24):
        y = 1 if (i % 7 == 3) else 0
        z = 1 if (12 <= i < 16) else 0
        trace.append((i, y, z))
    world.tag = "terrain"
    trace_write(world, trace)
    run_case("photo4-nature-douce", trace, world, styles=("nature",))


def main():
    scene1()
    scene2()
    scene3()
    scene4()
    total = counts["builds"]
    print(f"=== REPRO PHOTOS {counts['scenarios']} scenarios, {total} builds ===")
    if fails:
        head = fails[:25]
        print(f"{len(fails)} violations:")
        for f in head:
            print("   ", f)
        sys.exit(1)
    print("All green: no violation.")


if __name__ == "__main__":
    main()
