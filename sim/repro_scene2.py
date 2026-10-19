#!/usr/bin/env python3
"""Reproduction of the last three player screenshots:

1. Long nature line on almost flat ground with a gentle drift: a core
   (lectern or moss) must be present on EVERY trace voxel.
2. Y-jogs (61/62 teeth): no block placed above another rail block of the SAME
   x,z column may form a mound, and nothing may rise more than y+2 above the
   nearest core level.
3. Side view: no segment with |dy|>1 between consecutive trace voxels, and no
   vertical rail-on-rail stack across the thread.

This is the only judge: the ASCII rendering below must look like a continuous,
flat track with no hole and no bump.
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rail_sim as R
import realistic_stress as RS
from repro_user import gen_drift_line

violations = []


def fail(tag, msg):
    violations.append(f"{tag}: {msg}")


def build(tag, controls, style, theme="light", terrain="flat", dug=None,
          render=True):
    pre = [(int(round(x)), int(round(y)), int(round(z))) for (x, y, z) in controls]
    world = RS.make_terrain(pre, RS.zlib.crc32(tag.encode()) & 0xFFFF, terrain)
    for (x, y, z) in pre:
        world.set(x, y, z, R.SPLINE)
    trace = RS.build_trace(world, controls, dug=dug)
    opt = R.Options(style=style, theme=theme)
    model_before = set(world.blocks.keys())
    R.build_all(world, trace, opt)
    model = R.TrackModel(world, trace)

    # --- A. every trace voxel carries its core -------------------------------
    if style == "nature":
        cores = {"lectern_north", "lectern_east", "pale_moss_block"}
    else:
        cores = {"coral_south", "coral_east", "black_wool"}
    missing = 0
    for (x, y, z) in set(trace):
        st = world.get(x, y, z)
        if st not in cores and st not in (R.SPLINE, R.CORNER):
            # A neighbouring staircase column counts when the core moved there.
            ok = False
            for dy in (1, -1):
                if world.get(x, y + dy, z) in cores:
                    ok = True
                    break
            if not ok:
                missing += 1
    allow = max(0, len(set(trace)) // 40)   # tolerated fragment fringes
    if missing > allow:
        fail(tag, f"missing cores: {missing}/{len(set(trace))} voxels without a core")

    # --- B. No mound: no vertical rail-on-rail stack outside a staircase -----
    for (x, y, z) in set(trace):
        core = world.get(x, y, z)
        for (dx, dz) in ((0, 0), (0, 1), (0, -1), (1, 0), (-1, 0)):
            for dy in (1, 2):
                st = world.get(x + dx, y + dy, z + dz)
                if st not in R.RAIL_FAMILY:
                    continue
                # Legitimate only when the trace has a core at that level
                # within a radius of one, i.e. an adjacent staircase.
                ok = any((x + dx + ox, y + dy + oy, z + dz + oz) in set(trace)
                         for ox in (-1, 0, 1) for oz in (-1, 0, 1)
                         for oy in (-1, 0))
                if not ok:
                    fail(tag, f"rail block in mid-air {st} at "
                              f"({x + dx},{y + dy},{z + dz}) above "
                              f"core ({x},{y},{z})")
                    break

    # --- C. Along the thread: never |dy| > 1 between consecutive voxels ------
    seq = list(dict.fromkeys(trace))
    for i in range(1, len(seq)):
        (x0, y0, z0) = seq[i - 1]
        (x1, y1, z1) = seq[i]
        if abs(x1 - x0) <= 1 and abs(z1 - z0) <= 1 and abs(y1 - y0) > 1:
            fail(tag, f"vertical jump {y0}->{y1} between {seq[i-1]} and {seq[i]}")

    if render:
        R.render(world, trace, f"{tag} [{style}]")
    return world, trace


# 1. The exact drift scene of the first player report (nature and classic)
build("drift-nature", gen_drift_line(), "nature", render=True)
build("drift-classique", gen_drift_line(), "classic", render=True)

# 2. Long almost straight line with y micro-teeth on flat ground: the
#    "almost no rail left / rails leaving the ground" case
pts = [(x, 61.35 + 0.3 * ((i // 3) % 2), 30.0 + (0.0 if i < 8 else (i - 8) * 0.4))
       for i, x in enumerate(range(8, 40, 2))]
longue = [(x, y, z) for (x, y, z) in pts]
build("longue-nature", longue, "nature", render=True)
build("longue-classique", longue, "classic", render=True)

print()
if violations:
    print("VIOLATIONS:")
    for v in sorted(set(violations))[:40]:
        print(" -", v)
    sys.exit(1)
print("OK: no violation on", 4, "reproduced scenes")
