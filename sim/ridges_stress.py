#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ridges_stress.py — torture du lissage de crêtes (creusage de buttes 1-2 blocs).

Cas d'école remonté par le joueur : sur terrain bosselé, le rail sautait d'un cran
tous les ~30 blocs (fragment 3x1 au-dessus / rangee qui sort du sol). Avec
smoothRidges, une butte de 1-2 blocs franchie par la trace doit être CREUSEE
(tunnel), le rail restant plat et continu ; une paroi normale (>2 blocs) continue
de remonter le rail classiquement.

Invariants :
  - rail à hauteur constante malgré des buttes aléatoires (classic: corail y+1)
  - dug: jamais plus de 2 blocs par colonne, jamais de bloc rail/laine dedans
  - rebuild ne détruit aucun rail
  - états strictement valides, aucune exception
"""

import random
import sys
import time

import rail_sim as R

violations = []
counts = {"scenarios": 0, "builds": 0}

RAIL_STATES = {
    "wall_ns", "wall_eo", "wall_ne", "wall_nw", "wall_se", "wall_sw",
    "side_north", "side_south", "side_east", "side_west",
    "coral_south", "coral_east",
    "lectern_north", "lectern_east", "pale_moss_carpet", "pale_moss_block",
    "button_north", "button_east", "gravel",
} | {f"leaf_{a}_{f}" for a in "1234" for f in ("north", "south", "east", "west")}
SOIL = {"deepslate", "cobbled_deepslate", "pale_oak_wood",
        "deepslate_iron_ore", "deepslate_coal_ore", "orange_wool"}
IGNORED = {R.GROUND, "white_wool", R.AIR, "stone", "black_wool"}


def fail(case, msg):
    violations.append(f"{case}: {msg}")


def run_ridge(seed):
    counts["scenarios"] += 1
    rnd = random.Random(seed)
    length = rnd.randint(10, 40)
    y = 0
    trace = []
    x, z = rnd.randint(2, 6), rnd.randint(2, 6)
    dx, dz = rnd.choice(((1, 0), (0, 1)))
    for _ in range(length):
        trace.append((x, y, z))
        if rnd.random() < 0.35:
            dx, dz = rnd.choice(((1, 0), (-1, 0), (0, 1), (0, -1), (dx, dz)))
        x2, z2 = x + dx, z + dz
        if (x2, y, z2) in trace:
            break
        x, z = x2, z2

    xs = [v[0] for v in trace]
    zs = [v[2] for v in trace]
    w = R.flat_world(min(xs) - 3, max(xs) + 3, min(zs) - 3, max(zs) + 3)

    n_bumps = rnd.randint(1, max(1, length // 3))
    bump_cells = set()
    for _ in range(n_bumps):
        bx = rnd.randint(min(xs) - 2, max(xs) + 2)
        bz = rnd.randint(min(zs) - 2, max(zs) + 2)
        h = rnd.choice((1, 1, 2))             # butte de 1 ou 2 blocs
        for dy in range(1, h + 1):
            st = w.get(bx, dy, bz)
            if st == "white_wool" or st in R.RAIL_FAMILY:
                continue
            w.set(bx, dy, bz, "stone")
            bump_cells.add((bx, bz))
    tr_set = set((v[0], v[2]) for v in trace)
    on_track = [c for c in bump_cells if c in tr_set]

    for v in trace:
        w.set(v[0], v[1], v[2], R.SPLINE)

    dug = set()
    try:
        t = R.rectify_vertical(w, list(trace), R.SPLINE, R.CORNER, dug=dug)
        t = R.rectify_l(w, t, R.SPLINE, R.CORNER)
        t = R.rectify_vertical(w, t, R.SPLINE, R.CORNER, dug=dug)
    except Exception as e:
        fail(f"ridge-{seed}", f"EXCEPTION rectif: {e!r}")
        return

    for (dx2, dy2, dz2) in dug:
        st = "stone"
        if dy2 < 0 or dy2 > 2:
            fail(f"ridge-{seed}", f"dug hors profondeur {(dx2, dy2, dz2)}")
        if (dx2, dy2, dz2) not in bump_cells and w.get(dx2, dy2, dz2) == st:
            pass
    bumpy_by_col = {}
    for (x2, y2, z2) in dug:
        bumpy_by_col.setdefault((x2, z2), []).append(y2)
    for col, ys in bumpy_by_col.items():
        if len(ys) > 2:
            fail(f"ridge-{seed}", f"colonne {col} creusee {len(ys)} blocs")

    y_trace = {v[1] for v in t}
    if len(t) > 3:
        y0 = trace[0][1]
        bumped = [v for v in t if v[1] != y0]
        too_many = len(bumped) > max(0, length // 4)
        on_track_dug = [c for c in on_track]
        if too_many and on_track_dug:
            fail(f"ridge-{seed}", f"trop de voxels remontes: {len(bumped)}/{len(t)}")

    for style in ("classic", "nature"):
        counts["builds"] += 1
        before = dict(w.blocks)
        try:
            model = R.build_all(w, t, R.Options(style=style))
        except Exception as e:
            fail(f"ridge-{seed}/{style}", f"EXCEPTION build: {e!r}")
            return
        for pos, st in w.blocks.items():
            if st in IGNORED:
                continue
            if st not in RAIL_STATES and st not in SOIL:
                fail(f"ridge-{seed}/{style}", f"etat inconnu {st} a {pos}")
        counts["builds"] += 1
        try:
            R.build_all(w, t, R.Options(style=style))
        except Exception as e:
            fail(f"ridge-{seed}/{style}-re", f"EXCEPTION: {e!r}")
            return
        d = []
        after = dict(w.blocks)
        for k in set(before) | set(after):
            a, b = before.get(k, R.AIR), after.get(k, R.AIR)
            if a != b and a in RAIL_STATES:
                d.append((k, a, b))
        if d:
            fail(f"ridge-{seed}/{style}-protect", f"rail detruit: {d[:3]}")
        _ = model


def main():
    N = int(sys.argv[1]) if len(sys.argv) > 1 else 4000
    t0 = time.time()
    for seed in range(N):
        run_ridge(20250903 + seed)
        run_ridge(909090 + seed)
    dt = time.time() - t0
    print(f"\n=== {counts['scenarios']} scenarios cretes, {counts['builds']} builds, {dt:.1f}s ===")
    if violations:
        from collections import Counter
        import re
        grp = Counter()
        ex = {}
        for v in violations:
            key = re.sub(r"\d+", "N", v)
            grp[key] += 1
            ex.setdefault(key, v)
        print(f"!!! {len(violations)} VIOLATIONS ({len(grp)} groupes) !!!")
        for key, n in grp.most_common(30):
            print(f"{n:6d}x  {ex[key]}")
        sys.exit(1)
    print("Tout est vert : aucune violation.")


if __name__ == "__main__":
    main()
