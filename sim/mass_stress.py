#!/usr/bin/env python3
"""Mass stress: hundreds of thousands of tiny seed-generated scenarios run in
multiprocessing, with an EXACT count of the invariant checks performed.

A scenario is one short trace (3..25 voxels) tested on 2 styles x 2 themes plus
one rebuild each (protection). Every block comparison inside check_states,
check_corridor, rail_destroyed, check_doors or check_all_typed counts as one
verification.

Usage: python3 mass_stress.py [--scenarios N] [--workers W] [--seed S]
"""
import argparse
import multiprocessing as mp
import os
import random
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rail_sim as R

RAIL_STATES = {
    "wall_ns", "wall_eo", "wall_ne", "wall_nw", "wall_se", "wall_sw",
    "side_north", "side_south", "side_east", "side_west",
    "coral_south", "coral_east", "black_wool",
    "lectern_north", "lectern_east", "pale_moss_carpet", "pale_moss_block",
    "button_north", "button_east", "gravel",
} | {f"leaf_{a}_{f}" for a in "1234" for f in ("north", "south", "east", "west")} \
  | {f"door_{h}_{f}" for h in ("lower", "upper")
     for f in ("north", "south", "east", "west")}
SOIL = {"deepslate", "cobbled_deepslate", "pale_oak_wood",
        "deepslate_iron_ore", "deepslate_coal_ore", "orange_wool"}
IGNORED = {R.GROUND, "white_wool", "red_wool", "blue_wool", "lime_wool",
           R.AIR, R.GROUND}


def run_scenario(seed):
    """Returns (verifications, builds, violations)."""
    rnd = random.Random(seed)
    trace = gen_trace(rnd)
    if len(trace) < 2:
        return 0, 0, []
    xs = [v[0] for v in trace]
    zs = [v[2] for v in trace]
    w = R.World()
    pad = 2
    for x in range(min(xs) - pad, max(xs) + pad + 1):
        for z in range(min(zs) - pad, max(zs) + pad + 1):
            for yy in range(-8, 9):
                w.set(x, yy, z, R.GROUND if yy <= 0 else R.AIR)
    for (x, y, z) in trace:
        for yy in range(-8, y):
            w.set(x, yy, z, R.GROUND)
        w.set(x, y, z, "white_wool")

    verif = 0
    builds = 0
    viol = []
    for style in ("classic", "nature"):
        for theme in (1, 2):
            snap_before = dict(w.blocks)
            try:
                model = R.build_all(w, trace, R.Options(style=style, theme=theme))
                # Flat floating rail: air directly below and solid within 6
                for fp, fst in w.blocks.items():
                    verif += 1
                    if fst not in R.RAIL_FAMILY or fp in snap_before:
                        continue
                    # Invisible rail: a hostile TERRAIN solid above the piece
                    # means buried rail (gone) on rough ground (photo 2).
                    # Marker wools and fill soils are excluded.
                    fpa = (fp[0], fp[1] + 1, fp[2])
                    if fpa in snap_before:
                        above = w.get(*fpa)
                        if above in ("grass_block", "stone", "dirt"):
                            viol.append(f"seed{seed}/{style}/th{theme}: buried rail {fst} {fp}")
                    if w.get(fp[0], fp[1] - 1, fp[2]) not in (R.AIR, None):
                        continue
                    for dg in range(2, 6):
                        verif += 1
                        if w.get(fp[0], fp[1] - dg, fp[2]) not in (R.AIR, None):
                            viol.append(f"seed{seed}/{style}/th{theme}: flottant plat {fst} {fp}")
                            break

            except Exception as e:  # noqa: BLE001
                viol.append(f"seed{seed}/{style}/th{theme}: EXCEPTION {e!r}")
                continue
            builds += 1
            verif += check_invariants(w, trace, model, snap_before, viol,
                                      f"seed{seed}/{style}/th{theme}")
            # Idempotence and rebuild protection
            snap2 = dict(w.blocks)
            try:
                R.build_all(w, trace, R.Options(style=style, theme=theme))
            except Exception as e:  # noqa: BLE001
                viol.append(f"seed{seed}/{style}/th{theme}-re: EXCEPTION {e!r}")
                continue
            builds += 1
            d = rail_destroyed(snap2, dict(w.blocks))
            verif += len(snap2)
            for k, a, b in d:
                if a != "black_wool":
                    viol.append(f"seed{seed}/{style}/th{theme}-re: broken rail {k} {a}->{b}")
    return verif, builds, viol


def check_invariants(w, trace, model, before, viol, tag):
    v = 0
    for pos, st in w.blocks.items():
        if st in IGNORED or st == R.GROUND:
            continue
        v += 1
        if st not in RAIL_STATES and st not in SOIL:
            viol.append(f"{tag}: unknown state '{st}' at {pos}")
    # Corridor: nothing outside the widened trace bounding box.
    xs = [t[0] for t in trace]
    ys = [t[1] for t in trace]
    zs = [t[2] for t in trace]
    x0, x1 = min(xs) - 3, max(xs) + 3
    y0, y1 = min(ys) - 9, max(ys) + 3  # lower margin: supports up to 6 deep
    z0, z1 = min(zs) - 3, max(zs) + 3
    for (x, y, z), st in w.blocks.items():
        if st in IGNORED or st == R.GROUND or st in SOIL:
            continue
        v += 1
        if not (x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1):
            viol.append(f"{tag}: outside the corridor {st} {(x, y, z)}")
    # Lower door panels only (script rendering): never an upper half.
    for (x, y, z), st in w.blocks.items():
        if isinstance(st, str) and st.startswith("door_"):
            v += 1
            half = st.split("_", 2)[1]
            if half != "lower":
                viol.append(f"{tag}: full door not allowed {st} {(x, y, z)}")
    # Types assigned
    for t in trace:
        v += 1
        if model.types.get(t) not in (R.NS, R.EW, R.DIAG):
            viol.append(f"{tag}: unclassified voxel {t}")
    # Pre-existing rail preserved
    d = rail_destroyed(before, dict(w.blocks))
    v += len(before)
    for k, a, b in d:
        if a != "black_wool":
            viol.append(f"{tag}: rail destroyed {k} {a}->{b}")
    return v


RAIL_CORES = {"coral_south", "coral_east", "black_wool",
              "lectern_north", "lectern_east", "pale_moss_block"}


def rail_destroyed(before, after):
    """A core never changes. A decor may be claimed by the CORE of another
    line at a dense junction, but never by soil, air or another decor."""
    out = []
    keys = set(before) | set(after)
    for k in keys:
        a = before.get(k, R.AIR)
        b = after.get(k, R.AIR)
        if a == b or a not in RAIL_STATES:
            continue
        if a not in RAIL_CORES and b in RAIL_CORES:
            continue
        out.append((k, a, b))
    return out


DIRS8 = [(0, 1), (0, -1), (1, 0), (-1, 0), (1, 1), (1, -1), (-1, 1), (-1, -1)]


def gen_trace(rnd):
    mode = rnd.randrange(9)
    x, y, z = 0, 1, 0
    if mode == 0:  # straight line in 8 directions, possible slope
        dx, dz = DIRS8[rnd.randrange(8)]
        dy = rnd.choice((0, 0, 0, 1, -1))
        n = rnd.randint(3, 18)
        return [(x + dx * i, y + (dy * i if rnd.random() < 0.3 else 0),
                 z + dz * i) for i in range(n)]
    if mode == 1:  # L corner
        a = rnd.randint(2, 10)
        b = rnd.randint(2, 10)
        d1 = DIRS8[rnd.randrange(4)]
        d2 = DIRS8[rnd.randrange(4)]
        t = [(d1[0] * i, 1, d1[1] * i) for i in range(a)]
        bx, _, bz = t[-1]
        t += [(bx + d2[0] * i, 1, bz + d2[1] * i) for i in range(1, b)]
        return t
    if mode == 2:  # zigzag
        t = [(0, 1, 0)]
        x = z = 0
        for _ in range(rnd.randint(2, 6)):
            d = DIRS8[rnd.randrange(8)]
            seg = rnd.randint(2, 5)
            lx, lz = x, z
            for i in range(1, seg + 1):
                nx, nz = lx + d[0] * i, lz + d[1] * i
                if (nx, 1, nz) in set(t):
                    break
                t.append((nx, 1, nz))
            x, z = t[-1][0], t[-1][2]
        return t
    if mode == 3:  # climbing spiral, full turn
        t = []
        x, y, z = 0, 1, 0
        di = 0
        seen = set()
        for _ in range(rnd.randint(8, 30)):
            d = DIRS8[di % 8]
            x, z = x + d[0], z + d[1]
            if rnd.random() < 0.25:
                y += rnd.choice((1, -1, 0))
            v = (x, y, z)
            if v in seen:
                break
            seen.add(v)
            t.append(v)
            if rnd.random() < 0.45:
                di += 1
        return t if len(t) >= 2 else [(0, 1, 0), (0, 1, 1)]
    if mode == 4:  # staircase
        dx, dz = DIRS8[rnd.randrange(4)]
        n = rnd.randint(3, 12)
        step = rnd.choice((1, 1, -1))
        return [(x + dx * i, y + step * i, z + dz * i) for i in range(n)]
    if mode == 5:  # two-level crossing
        n = rnd.randint(4, 10)
        t1 = [(i - 5, 1, 0) for i in range(n)]
        t2 = [(0, 4, i - 5) for i in range(n)]
        return t1 + t2
    if mode == 6:  # self-avoiding random walk, possibly wild
        t = [(0, 1, 0)]
        seen = {(0, 1, 0)}
        x = z = 0
        for _ in range(rnd.randint(5, 25)):
            d = DIRS8[rnd.randrange(8)]
            cand = (x + d[0], 1 + rnd.choice((0, 0, 0, 1, -1)), z + d[1])
            if cand in seen:
                continue
            seen.add(cand)
            t.append(cand)
            x, z = cand[0], cand[2]
        return t
    if mode == 7:  # separate island, two pieces
        a = [(i, 1, 0) for i in range(rnd.randint(2, 6))]
        b = [(20 + i, 1, 20) for i in range(rnd.randint(2, 6))]
        return a + b
    # 8: full rectangle, closed loop
    w_ = rnd.randint(3, 8)
    h_ = rnd.randint(3, 8)
    t = []
    for i in range(w_):
        t.append((i, 1, 0))
    for i in range(1, h_):
        t.append((w_ - 1, 1, i))
    for i in range(1, w_):
        t.append((w_ - 1 - i, 1, h_ - 1))
    for i in range(1, h_ - 1):
        t.append((0, 1, h_ - 1 - i))
    return t


def worker(args):
    seed0, count = args
    tot_v = 0
    tot_b = 0
    viols = []
    for i in range(count):
        v, b, viol = run_scenario(seed0 + i)
        tot_v += v
        tot_b += b
        if viol:
            viols.extend(viol[:3])
            if len(viols) > 200:
                viols = viols[:200]
    return tot_v, tot_b, viols


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenarios", type=int, default=200000)
    ap.add_argument("--workers", type=int, default=min(8, os.cpu_count() or 4))
    ap.add_argument("--seed", type=int, default=1234)
    args = ap.parse_args()

    per = args.scenarios // args.workers
    chunks = [(args.seed + w * per, per) for w in range(args.workers)]
    t0 = time.time()
    verif = 0
    builds = 0
    viols = []
    with mp.Pool(args.workers) as pool:
        for v, b, vl in pool.imap_unordered(worker, chunks):
            verif += v
            builds += b
            viols.extend(vl)
    dt = time.time() - t0
    scen = args.scenarios
    print(f"\n=== MASS {scen:,} scenarios | {builds:,} builds | "
          f"{verif:,} verifications | {dt:.1f}s "
          f"({verif / max(dt, 0.01):,.0f} verif/s) ===")
    if viols:
        print(f"!!! {len(viols)} violations (first 20 shown) !!!")
        for x in viols[:20]:
            print("   ", x)
        sys.exit(1)
    print("All green: no violation.")


if __name__ == "__main__":
    main()
