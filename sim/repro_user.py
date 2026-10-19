#!/usr/bin/env python3
"""EXACT reproduction of the bugs reported in game (player screenshots):

1. S->N line drifting slightly west then a sharp turn, on flat ground: no
   built block may float. Every placed block needs a direct support below it,
   which is what a floating block means: a wall or soil two blocks above ground.
2. Light theme: lower door panels ONLY, never an upper half.
3. Nature design: at every X or Z change in a corner (a jog) a 3-segment
   leaf_litter must appear on the concave corner (Rouquinator script).

This harness exists because the other suites let these bugs through: their
hostile world differs from the player's real flat terrain.
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rail_sim as R
import realistic_stress as RS

violations = []


def fail(tag, msg):
    violations.append(f"{tag}: {msg}")


def gen_drift_line():
    """Gentle S->N line sliding west, then a sharp westward turn.
    y alternates 61.35/61.65 so Catmull-Rom voxelisation produces 61/62 teeth
    exactly as the game does on flat ground."""
    y = [61.35, 61.65]
    ctl = []
    pts = [(30, 60), (30, 54), (29.6, 48), (29, 42), (28, 36), (27.2, 30),
           (26, 24), (25, 18), (24.4, 12), (24, 6), (24, 0)]
    for i, (x, z) in enumerate(pts):
        ctl.append((float(x), y[i % 2], float(z)))
    ctl.append((17, y[0], 0.0))
    ctl.append((10, y[1], 0.0))
    return ctl


def run_case(tag, controls, style, theme):
    pre = [(int(round(x)), int(round(y)), int(round(z))) for (x, y, z) in controls]
    world = RS.make_terrain(pre, RS.zlib.crc32(tag.encode()) & 0xFFFF, "flat")
    for (x, y, z) in pre:
        world.set(x, y, z, R.SPLINE)
    trace = RS.build_trace(world, controls, dug=None)
    if len(trace) < 4:
        fail(tag, "nearly empty trace")
        return

    # 1) Support: no placed block has air directly below it.
    built_before = set(world.blocks.keys())
    R.build_all(world, trace, R.Options(style=style, theme=theme))
    y_min = min(v[1] for v in trace)
    y_max = max(v[1] for v in trace)
    floating = []
    for (x, y, z), st in world.blocks.items():
        if st not in R.RAIL_FAMILY:
            continue
        if (x, y, z) in built_before:
            continue
        if world.get(x, y - 1, z) == R.AIR:
            floating.append(((x, y, z), st))
    if floating:
        fail(tag, f"{len(floating)} floating block(s): {floating[:3]}")

    # 2) Teeth: on flat ground the rectified trace must have no +-1 plateau
    #    left, which is what made fragments visible in mid-air.
    teeth = 0
    i = 1
    while i < len(trace) - 1:
        ay = trace[i - 1][1]
        by = trace[i][1]
        if by != ay and abs(by - ay) == 1:
            j = i
            while j < len(trace) and trace[j][1] == by:
                j += 1
            if j < len(trace) and trace[j][1] == ay:
                teeth += 1
            i = j
        else:
            i += 1
    if teeth:
        fail(tag, f"{teeth} residual vertical tooth/plateau(s)")

    # 3) Doors: never an upper half (light theme).
    uppers = [(p, st) for p, st in world.blocks.items()
              if isinstance(st, str) and st.startswith("door_upper")]
    if uppers:
        fail(tag, f"{len(uppers)} full door(s): {uppers[:2]}")

    if style == "nature":
        # 4) Corners: every jog (diagonal plus lateral neighbour) needs a
        #    nearby leaf_3.
        s = set(trace)
        missings = []
        for (x, y, z) in trace:
            nb_ax = [(dx, dz) for dx in (-1, 1) for dz in (0,)
                     if (x + dx, y, z) in s or (x + dx, y - 1, z) in s or (x + dx, y + 1, z) in s]
            nb_ax += [(dx, dz) for dx in (0,) for dz in (-1, 1)
                      if (x, y, z + dz) in s or (x, y - 1, z + dz) in s or (x, y + 1, z + dz) in s]
            nb_dg = [(dx, dz) for dx in (-1, 1) for dz in (-1, 1)
                     if (x + dx, y, z + dz) in s or (x + dx, y - 1, z + dz) in s
                     or (x + dx, y + 1, z + dz) in s]
            if not nb_dg or len(nb_ax) < 1:
                continue
            # Diagonal plus lateral neighbour means a corner drift: a leaf_3
            # must be close by.
            found = False
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    st = world.get(x + dx, y + 1, z + dz)
                    if isinstance(st, str) and st.startswith("leaf_3"):
                        found = True
            if not found:
                missings.append((x, y, z))
        if missings:
            fail(tag, f"{len(missings)} jog(s) without leaf_3: {missings[:4]}")


def main():
    n = 0
    for style, themes in (("classic", (1, 2)), ("nature", (1,))):
        for th in themes:
            ctl = gen_drift_line()
            tag = f"drift/{style}/th{th}"
            run_case(tag, ctl, style, th)
            n += 1
    print(f"=== REPRO {n} builds ===")
    if violations:
        for v in violations[:30]:
            print("FAIL", v)
        print(f"{len(violations)} violation(s).")
        sys.exit(1)
    print("All green: no violation.")


if __name__ == "__main__":
    main()
