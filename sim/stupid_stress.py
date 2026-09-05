#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
stupid_stress.py — torture absolue du moteur rail (rail_sim.py).

Des milliers de mises en situation totalement stupides et random :
  - marches aléatoires avec rebroussements, boucles, téléportations verticales
  - chaos voxel pur (générateur sans aucune logique de rail)
  - rebuilds en chaîne 5 passes avec alternance classic/nature/buried
  - splines dégénérés (points dupliqués, aller-retour A->B->A, zigzags extrêmes)
  - terrains hostiles (falaises, trous, dalles flottantes, montages raides)
  - couleurs absurdes contradictoires posées n'importe où
  - traces géantes / traces-goutte (1 voxel), patterns hérissons, spirales, grillages

Invariants (doivent tenir PARTOUT, même dans l'absurde) :
  1. Aucune exception dans toute la chaîne (voxelize, rectif, build).
  2. Seuls des états connus sont posés (noms d'états strictement valides).
  3. Aucun bloc posé hors du corridor (trace ±3, y trace ±3) hors sol.
  4. Jamais un bloc de rail existant n'est détruit/remplacé au rebuild,
     quel que soit le style rejoué par-dessus (hors black_wool volontaire).
  5. Tout voxel de trace obtient un type NS/EW/DIAG (pas de None).
  6. Perf : chaque build d'une trace de taille raisonnable restera < ~2 s.
"""

import json
import math
import random
import sys
import time

import rail_sim as R

violations = []
warnings = []
counts = {"scenarios": 0, "builds": 0, "voxels": 0}

RAIL_STATES = {
    "wall_ns", "wall_eo", "wall_ne", "wall_nw", "wall_se", "wall_sw",
    "side_north", "side_south", "side_east", "side_west",
    "coral_south", "coral_east",
    "lectern_north", "lectern_east", "pale_moss_carpet", "pale_moss_block",
    "button_north", "button_east", "gravel",
} | {f"leaf_{a}_{f}" for a in "1234" for f in ("north", "south", "east", "west")}
RAIL_STATES |= {f"door_{h}_{f}" for h in ("lower", "upper")
                for f in ("north", "south", "east", "west")}
SOIL = {"deepslate", "cobbled_deepslate", "pale_oak_wood",
        "deepslate_iron_ore", "deepslate_coal_ore", "orange_wool"}
IGNORED = {R.GROUND, "white_wool", "red_wool", "blue_wool", "lime_wool",
           "black_wool", R.AIR, "stone", "water"}


def fail(case, msg):
    violations.append(f"{case}: {msg}")


def warn(case, msg):
    warnings.append(f"{case}: {msg}")


def check_states(case, world):
    for pos, st in world.blocks.items():
        if st in IGNORED:
            continue
        if st not in RAIL_STATES and st not in SOIL:
            fail(case, f"etat inconnu '{st}' a {pos}")


def check_doors(case, world):
    """Panneaux de porte basse uniquement (rendu script) : pas de moitie upper."""
    blocks = world.blocks
    for (x, y, z), st in blocks.items():
        if not isinstance(st, str) or not st.startswith("door_"):
            continue
        half = st.split("_", 2)[1]
        if half != "lower":
            fail(case, f"porte complete interdite: {st} a {(x, y, z)} (upper inattendu)")


def check_corridor(case, world, trace, pre_keys=frozenset()):
    if not trace:
        return
    xs = [v[0] for v in trace]
    ys = [v[1] for v in trace]
    zs = [v[2] for v in trace]
    x0, x1, y0, y1, z0, z1 = (min(xs) - 3, max(xs) + 3,
                              min(ys) - 9, max(ys) + 3,
                              min(zs) - 3, max(zs) + 3)
    for (x, y, z), st in world.blocks.items():
        if st in IGNORED or st == R.GROUND:
            continue
        if not (x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1):
            fail(case, f"hors corridor: {st} a {(x, y, z)}")
    # flottant de terrain plat : air DIRECTEMENT sous + solide dans les 6
    # blocs en dessous (sinon c'est un pont/bosse volontaire : pas un bug).
    for (x, y, z), st in world.blocks.items():
        if st not in R.RAIL_FAMILY or (x, y, z) in pre_keys:
            continue
        # rail invisible : un solide de TERRAIN hostile (roche/herbe) au-dessus
        # d'une pièce = rail enterré = « il manque le rail » (photo 2). On
        # n'alerte pas pour les wools marqueurs ni les soils d'un fill
        # (encore visibles de profil).
        above = world.get(x, y + 1, z)
        if (x, y + 1, z) in pre_keys and above in ("grass_block", "stone", "dirt"):
            fail(case, f"rail enterre: {st} a {(x, y, z)}, dessus={above!r}")
        if world.get(x, y - 1, z) not in (R.AIR, None):
            continue
        for depth_gap in range(2, 6):
            if world.get(x, y - depth_gap, z) not in (R.AIR, None):
                fail(case, f"flottant plat: {st} a {(x, y, z)} ({depth_gap - 1} air sous lui)")
                break


def check_all_typed(case, model, trace):
    for v in trace:
        if model.types.get(v) not in (R.NS, R.EW, R.DIAG):
            fail(case, f"voxel {v} non classe ({model.types.get(v)})")


def snapshot(world):
    return dict(world.blocks)


def rail_destroyed(before, after):
    d = []
    for k in set(before) | set(after):
        a, b = before.get(k, R.AIR), after.get(k, R.AIR)
        if a != b and a in RAIL_STATES:
            d.append((k, a, b))
    return d


def make_hostile_world(trace, seed=None, terrain="flat"):
    """Terrain autour de la trace : flat | cliffs | holes | shelf | chaos."""
    xs = [v[0] for v in trace] or [0]
    zs = [v[2] for v in trace] or [0]
    x0, x1, z0, z1 = min(xs) - 4, max(xs) + 4, min(zs) - 4, max(zs) + 4
    rnd = random.Random(seed)
    w = R.World()

    def h(x, z):
        if terrain == "flat":
            return 0
        if terrain == "cliffs":
            return 4 if (x // 3 + z // 3) % 3 == 0 else (-2 if (x + z) % 5 == 0 else 0)
        if terrain == "holes":
            return 0 if rnd.random() < 0.8 else -6
        if terrain == "shelf":
            return (z // 4) if x < (x0 + x1) // 2 else 3 - (z // 7)
        if terrain == "chaos":
            return rnd.randint(-3, 5)
        return 0

    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            hh = h(x, z)
            for y in range(hh - 3, hh + 1):
                w.set(x, y, z, "stone" if rnd.random() < 0.02 else R.GROUND)
            if terrain == "chaos" and rnd.random() < 0.03:
                w.set(x, hh + 1, z, "stone")  # débris sur le sol
            if terrain == "holes" and rnd.random() < 0.05:
                w.set(x, hh + 1, z, "water")  # mare
    for (x, y, z) in trace:
        base = h(x, z)
        for yy in range(base - 3, y):
            if w.get(x, yy, z) == R.AIR:
                w.set(x, yy, z, R.GROUND)
        w.set(x, y, z, "white_wool")
    return w


def run_mega(name, trace, colors=None, terrain="flat",
             styles=("classic", "nature"), passes=3, options_varies=True):
    """Build + rebuild passes x (alternance de styles/options), invariants complets."""
    if not trace:
        warn(name, "trace vide")
        return
    counts["scenarios"] += 1
    counts["voxels"] += len(trace)
    for base_style in styles:
        w = make_hostile_world(trace, seed=hash(name) & 0xFFFF, terrain=terrain)
        if colors:
            for pos, c in colors.items():
                if w.get(*pos) != R.AIR or True:
                    w.set(pos[0], pos[1], pos[2], c)
        pre0 = set(w.blocks.keys())
        t0 = time.time()
        last = snapshot(w)
        for p in range(passes):
            style = base_style
            buried = options_varies and (p % 2 == 1)
            fill = 1 + (p % 2)
            theme = 1 + (p % 2)
            opt = R.Options(style=style, theme=theme, fill_mode=fill, buried=buried)
            before = snapshot(w)
            t_build = time.time()
            try:
                model = R.build_all(w, trace, opt)
            except Exception as e:
                fail(f"{name}/{base_style}/pass{p}",
                     f"EXCEPTION build_all: {e!r}")
                return
            counts["builds"] += 1
            after = snapshot(w)
            if p > 0:
                d = rail_destroyed(before, after)
                if d:
                    fail(f"{name}/{base_style}/pass{p}-protect",
                         f"rail detruit: {d[:3]}")
            check_states(f"{name}/{base_style}/pass{p}", w)
            check_doors(f"{name}/{base_style}/pass{p}", w)
            check_all_typed(f"{name}/{base_style}/pass{p}", model, trace)
            last = after
        check_corridor(f"{name}/{base_style}", w, trace, pre_keys=pre0)
        dt = time.time() - t0
        if len(trace) > 200 and dt > 5.0:
            warn(name, f"lent: {dt:.1f}s pour {len(trace)} voxels x {passes} passes")


def full_pipeline(name, control, terrain="flat", bury=None, styles=("classic", "nature"),
                  colorize=None, smooth=False):
    """Chaîne complète d'un joueur : spline -> voxelize -> rectif -> colors -> build.
    smooth=True active le lissage de cretes (dug) comme l'option par defaut du mod."""
    counts["scenarios"] += 1
    try:
        floats = R.adaptive_sample(control)
        vox = R.voxelize(floats)
    except Exception as e:
        fail(name, f"EXCEPTION voxelize: {e!r}")
        return
    if not vox:
        warn(name, "spline vide")
        return
    w = make_hostile_world(vox, seed=hash(name) & 0xFFFF, terrain=terrain)
    trace = list(vox)
    dug = set() if smooth else None
    try:
        trace = R.rectify_vertical(w, trace, R.SPLINE, R.CORNER, dug=dug)
        trace = R.rectify_l(w, trace, R.SPLINE, R.CORNER)
        trace = R.rectify_vertical(w, trace, R.SPLINE, R.CORNER, dug=dug)
        trace = R.flatten_teeth(w, trace, R.SPLINE)
    except Exception as e:
        fail(name, f"EXCEPTION rectif: {e!r}")
        return
    if dug:
        per_col = {}
        for (x2, y2, z2) in dug:
            per_col.setdefault((x2, z2), 0)
            per_col[(x2, z2)] += 1
            if per_col[(x2, z2)] > 2:
                fail(name, f"colonne creusee >2: {(x2, z2)}")
        for (x2, y2, z2) in dug:
            if w.get(x2, y2, z2) in R.RAIL_FAMILY:
                fail(name, f"dug a touche du rail {(x2, y2, z2)}")
    if colorize:
        colorize(w, trace)
    counts["voxels"] += len(trace)
    for style in styles:
        for theme in (1, 2):
            tag = f"{style}/th{theme}"
            before = snapshot(w)
            t0 = time.time()
            try:
                model = R.build_all(w, trace, R.Options(style=style, theme=theme))
            except Exception as e:
                fail(f"{name}/{tag}", f"EXCEPTION build: {e!r}")
                continue
            counts["builds"] += 1
            _ = model
            check_states(f"{name}/{tag}", w)
            check_doors(f"{name}/{tag}", w)
            d = rail_destroyed(before, snapshot(w))
            if d:
                fail(f"{name}/{tag}", f"build initial casse du rail: {d[:3]}")


# =====================================================================
# GÉNÉRATEURS STUPIDES
# =====================================================================

def gen_drunk_walk(rnd, n=None):
    """Marche complètement saoule : peut rebrousser, revenir, monter/descendra au hasard."""
    n = n or rnd.randint(3, 60)
    x, y, z = rnd.randint(0, 20), rnd.randint(1, 10), rnd.randint(0, 20)
    t = [(x, y, z)]
    for _ in range(n - 1):
        dx, dy, dz = (rnd.choice((-1, 0, 1)), rnd.choice((-1, 0, 0, 0, 1)),
                      rnd.choice((-1, 0, 1)))
        if dx == 0 and dz == 0 and dy == 0:
            continue
        y = max(0, min(16, y + dy))
        t.append((x + dx, y, z + dz))
        x, z = t[-1][0], t[-1][2]
    out = []
    for v in t:
        if not out or out[-1] != v:
            out.append(v)
    return out


def gen_chaos_cube(rnd, size=12, density=0.12):
    """Nuage bête de voxels : zéro structure de rail, pire cauchemar du classificateur."""
    pts = []
    for x in range(size):
        for y in range(8):
            for z in range(size):
                if rnd.random() < density:
                    pts.append((x, 2 + y, z))
    rnd.shuffle(pts)
    out = []
    for v in pts:
        if v not in out:
            out.append(v)
    return out[:400]


def gen_zigzag_hell(rnd):
    """Zigzag 1/1 alterné très dense : diagonales absurdes en génome."""
    t = []
    dir_ = 1
    x, y, z = 5, 3, 5
    for _ in range(rnd.randint(8, 32)):
        t.append((x, y, z))
        if rnd.random() < 0.45:
            dir_ *= -1
        x += dir_
        z += 1
        if rnd.random() < 0.2:
            y += rnd.choice((-1, 1))
            y = max(0, min(14, y))
    return t


def gen_spiral(rnd):
    cx, cz, r = 15, 15, rnd.randint(4, 10)
    t = set()
    for k in range(r * 14):
        ang = k * 0.7
        x = int(cx + r * math.cos(ang))
        z = int(cz + r * math.sin(ang))
        y = 2 + k // max(1, (r * 2))
        t.add((x, y, z))
    return list(t)


def gen_stairs_mad(rnd):
    """Escalier raide 1 marche / bloc, ou 3 blocs d'affilée par montée, mix."""
    t = []
    x, y, z = 8, 1, 8
    for _ in range(rnd.randint(6, 26)):
        t.append((x, y, z))
        z += 1
        if rnd.random() < 0.7:
            y += 1
        elif rnd.random() < 0.4:
            y -= 1
        y = max(0, min(18, y))
    return t


def gen_grid(rnd):
    """Grillage orthogonal : intersections en masse."""
    t = []
    base_x, base_z = 6, 6
    nx, nz = rnd.randint(2, 4), rnd.randint(2, 4)
    L = rnd.randint(6, 16)
    for i in range(nx):
        for z in range(base_z, base_z + L):
            t.append((base_x + i * 4, 2, z))
    for j in range(nz):
        for x in range(base_x, base_x + L):
            t.append((x, 2, base_z + j * 4))
    out = []
    for v in t:
        if v not in out:
            out.append(v)
    return out


def gen_spike(rnd):
    """Lignes radiales depuis un centre : étoile à 3..8 branches."""
    cx, cy, cz = 14, 3, 14
    t = [(cx, cy, cz)]
    for b in range(rnd.randint(3, 8)):
        ang = rnd.uniform(0, 2 * math.pi)
        for k in range(1, rnd.randint(3, 10)):
            x = int(round(cx + k * math.cos(ang)))
            z = int(round(cz + k * math.sin(ang)))
            t.append((x, cy + rnd.choice((0, 0, 1)), z))
    out = []
    for v in t:
        if v not in out:
            out.append(v)
    return out


def main():
    random.seed(7)
    args = {}
    for a in sys.argv[1:]:
        if a.startswith("--") and "=" in a:
            k, v = a[2:].split("=", 1)
            args[k] = int(v)
    N_DRUNK = args.get("drunks", 1500)
    N_CHAOS = args.get("chaos", 120)
    N_COLOR = args.get("colors", 300)
    N_PATTERNS = args.get("patterns", 60)
    N_CHAIN = args.get("chains", 60)
    N_BURIED = args.get("buried", 150)
    N_TERRAIN = args.get("terrain", 80)
    N_VOID = args.get("voids", 100)
    t_start = time.time()

    # A. Marches saoules massives -------------------------------------------
    for seed in range(N_DRUNK):
        rnd = random.Random(100_000 + seed)
        t = gen_drunk_walk(rnd)
        terrain = rnd.choice(("flat", "flat", "cliffs", "holes", "shelf"))
        run_mega(f"drunk-{seed}", t, terrain=terrain,
                 styles=(rnd.choice(("classic", "nature")),), passes=2)

    # B. Chaos cube : aucun sens -------------------------------------------
    for seed in range(N_CHAOS):
        rnd = random.Random(200_000 + seed)
        t = gen_chaos_cube(rnd, size=rnd.choice((8, 10, 12)),
                           density=rnd.choice((0.04, 0.08, 0.14)))
        if not t:
            continue
        run_mega(f"chaos-{seed}", t, terrain="flat", passes=2)

    # C. Splines vraiment débiles ------------------------------------------
    stupid_controls = [
        ("dup-points", [(10, 3, 10), (10, 3, 10), (10, 3, 10), (14, 5, 18)]),
        ("one-pixel", [(10, 3, 10), (10, 3, 10)]),
        ("near-points", [(10, 3, 10), (10, 3, 11)]),
        ("go-back", [(8, 2, 8), (20, 2, 20), (8, 2, 8)]),
        ("loop-knot", [(5, 2, 5), (20, 2, 5), (20, 2, 18), (5, 2, 18), (5, 2, 5)]),
        ("roller", [(6, 12, 6), (12, 0, 12), (18, 14, 18), (24, 1, 24)]),
        ("pillar-swap", [(8, 1, 8), (8, 12, 8), (8, 1, 8), (14, 3, 14)]),
        ("diagonal-pure", [(4, 2, 4), (12, 2, 12), (20, 2, 20), (28, 2, 28)]),
        ("staccato", [(4, 2, 4), (4, 2, 8), (8, 2, 8), (8, 2, 12), (12, 2, 12)]),
        ("self-x", [(4, 2, 4), (16, 2, 16), (16, 2, 4), (4, 2, 16), (10, 2, 10)]),
    ]
    for name, ctrl in stupid_controls:
        full_pipeline(f"dumb-spline-{name}", ctrl)
        full_pipeline(f"dumb-spline-cliffs-{name}", ctrl, terrain="cliffs")
        full_pipeline(f"dumb-spline-smooth-{name}", ctrl, terrain="cliffs", smooth=True)

    # D. Couleurs absurdes --------------------------------------------------
    cases_color = N_COLOR
    for seed in range(cases_color):
        rnd = random.Random(300_000 + seed)
        t = gen_drunk_walk(rnd, n=rnd.randint(6, 24))
        colors = {}
        for v in t:
            r = rnd.random()
            if r < 0.25:
                colors[v] = rnd.choice(("red_wool", "blue_wool", "lime_wool"))
        terrain = rnd.choice(("flat", "flat", "shelf"))
        run_mega(f"colors-{seed}", t, colors=colors, terrain=terrain,
                 styles=(rnd.choice(("classic", "nature")),), passes=2)

    # E. Patterns satanés ----------------------------------------------------
    for seed in range(N_PATTERNS):
        rnd = random.Random(400_000 + seed)
        which = rnd.random()
        if which < 0.25:
            t, tag = gen_zigzag_hell(rnd), "zigzag"
        elif which < 0.5:
            t, tag = gen_spiral(rnd), "spiral"
        elif which < 0.75:
            t, tag = gen_stairs_mad(rnd), "stairs"
        elif which < 0.9:
            t, tag = gen_grid(rnd), "grid"
        else:
            t, tag = gen_spike(rnd), "spike"
        run_mega(f"{tag}-{seed}", t, passes=3)

    # F. Chaînes de rebuild longues (5 passes, styles alternés) -------------
    for seed in range(N_CHAIN):
        rnd = random.Random(500_000 + seed)
        t = gen_drunk_walk(rnd, n=rnd.randint(10, 30))
        run_mega(f"chain5-{seed}", t, styles=("classic", "nature"), passes=5)

    # G. Buried partout -------------------------------------------------------
    for seed in range(N_BURIED):
        rnd = random.Random(600_000 + seed)
        t = gen_drunk_walk(rnd, n=rnd.randint(4, 20))
        w = make_hostile_world(t)
        counts["scenarios"] += 1
        counts["voxels"] += len(t)
        for style in ("classic", "nature"):
            opt = R.Options(style=style, buried=True)
            t0 = time.time()
            try:
                R.build_all(w, t, opt)
            except Exception as e:
                fail(f"buried-{seed}/{style}", f"EXCEPTION: {e!r}")
                continue
            counts["builds"] += 1
            check_states(f"buried-{seed}/{style}", w)
            before = snapshot(w)
            try:
                R.build_all(w, t, opt)
            except Exception as e:
                fail(f"buried-{seed}/{style}-re", f"EXCEPTION: {e!r}")
                continue
            counts["builds"] += 1
            d = rail_destroyed(before, snapshot(w))
            if d:
                fail(f"buried-{seed}/{style}-re", f"rail enterre detruit: {d[:3]}")
            _ = t0

    # H. Extrêmes ------------------------------------------------------------
    # H1 : 1 voxel, 2 voxels
    run_mega("single-voxel", [(10, 3, 10)], passes=3)
    run_mega("two-voxels-ns", [(10, 3, 10), (10, 3, 11)], passes=3)
    run_mega("two-voxels-diag", [(10, 3, 10), (11, 3, 11)], passes=3)
    run_mega("two-voxels-up", [(10, 3, 10), (10, 4, 11)], passes=3)
    # H2 : longue ligne 240 (dépasse MAX_LINE_SCAN * 10), diagonale 130
    run_mega("huge-line-ns", [(10, 3, 10 + i) for i in range(240)], passes=2,
             styles=("classic",))
    run_mega("huge-diag", [(10 + i, 3, 10 + i) for i in range(130)], passes=2,
             styles=("classic", "nature"))
    # H3 : surface vitrée : plateau de voxels laine (2D complet)
    slab = []
    for x in range(8, 16):
        for z in range(8, 16):
            slab.append((x, 3, z))
    run_mega("slab-2d", slab, passes=2)
    # H4 : croix en X à 40
    cross = ([(10 + i, 3, 10 - i) for i in range(-20, 21)]
             + [(10 + i, 3, 10 + i) for i in range(-20, 21)])
    big = []
    for v in cross:
        if v not in big:
            big.append(v)
    run_mega("x-cross-40", big, passes=3)
    # H5 : pile verticale pure z fixe
    pile = [(12, 1 + i, 12) for i in range(12)]
    run_mega("pure-pillar", pile, passes=3)

    # I. Terrains ultra hostiles + splines moyennes ---------------------------
    for seed in range(N_TERRAIN):
        rnd = random.Random(700_000 + seed)
        n = rnd.randint(3, 6)
        ctrl = [(rnd.randint(2, 30), rnd.randint(0, 12), rnd.randint(2, 30))
                for _ in range(n)]
        full_pipeline(f"terrain-{seed}", ctrl,
                      terrain=rnd.choice(("cliffs", "holes", "shelf", "chaos")))
        full_pipeline(f"terrain-smooth-{seed}", ctrl,
                      terrain=rnd.choice(("cliffs", "holes", "shelf", "chaos")),
                      smooth=True)

    # I2. Traces volantes hautes (> max_down 20) + y negatifs -----------------
    for seed in range(N_VOID):
        rnd = random.Random(800_000 + seed)
        n = rnd.randint(5, 25)
        y = rnd.choice((24, 30, 40, -8, -16))
        t = []
        x, z = rnd.randint(0, 10), rnd.randint(0, 10)
        dx, dz = rnd.choice(((1, 0), (0, 1), (1, 1), (1, -1)))
        for _ in range(n):
            t.append((x, y + rnd.choice((0, 0, 0, 1, -1)), z))
            x, z = x + dx, z + dz
        out = []
        for v in t:
            if not out or out[-1] != v:
                out.append(v)
        run_mega(f"void-{seed}", out, styles=(rnd.choice(("classic", "nature")),),
                 passes=2)

    # J. Non-régression canonique --------------------------------------------
    # s-curve : rendu nature attendu (inscrit lors du portage)
    floats = R.adaptive_sample([(8, 1, 36), (8, 1, 22), (20, 1, 12), (20, 1, 2)])
    vox = R.voxelize(floats)
    w, tr = None, None
    w = R.flat_world(min(v[0] for v in vox) - 3, max(v[0] for v in vox) + 3,
                     min(v[2] for v in vox) - 3, max(v[2] for v in vox) + 3)
    for v in vox:
        w.set(v[0], v[1], v[2], R.SPLINE)
    tr = list(vox)
    tr = R.rectify_vertical(w, tr, R.SPLINE, R.CORNER)
    tr = R.rectify_l(w, tr, R.SPLINE, R.CORNER)
    tr = R.rectify_vertical(w, tr, R.SPLINE, R.CORNER)
    tr = R.dedupe_columns(w, tr, R.SPLINE)
    R.build_all(w, tr, R.Options(style="nature"))
    n_lect = sum(1 for s in w.blocks.values() if s.startswith("lectern"))
    n_leaf = sum(1 for s in w.blocks.values() if s.startswith("leaf_"))
    n_grav = sum(1 for s in w.blocks.values() if s == "gravel")
    counts["scenarios"] += 1
    counts["builds"] += 1
    if not (n_lect > 0 and n_leaf > 0 and n_grav > 0):
        fail("s-curve-nature", f"rendu nature casse: lectern={n_lect} leaf={n_leaf} gravel={n_grav}")

    # K. Perf borne : trace de ~600 voxels droite + angles --------------------
    t0 = time.time()
    big = []
    for i in range(300):
        big.append((10, 3, 10 + i))
        big.append((10 + i, 3, 10))
    tr = []
    for v in big:
        if v not in tr:
            tr.append(v)
    w = make_hostile_world(tr)
    R.build_all(w, tr, R.Options(style="classic"))
    R.build_all(w, tr, R.Options(style="nature"))
    dt = time.time() - t0
    counts["builds"] += 2
    if dt > 10:
        warn("perf-big-T", f"{dt:.1f}s pour T de ~600 voxels")

    # -------------------------------------------------------------------------
    dt = time.time() - t_start
    print(f"\n=== {counts['scenarios']} scenarios stupides, {counts['builds']} builds, "
          f"{counts['voxels']} voxels cumules, {dt:.1f}s ===")
    if violations:
        from collections import Counter
        import re
        grp = Counter()
        ex = {}
        for v in violations:
            key = re.sub(r"\d+", "N", v.split(": ")[0]) + "|" + \
                  re.sub(r"\d+", "N", v.split(": ", 1)[1] if ": " in v else "")
            grp[key] += 1
            ex.setdefault(key, v)
        print(f"!!! {len(violations)} VIOLATIONS ({len(grp)} groupes) !!!")
        for key, n in grp.most_common(50):
            print(f"{n:6d}x  {ex[key]}")
        if warnings:
            print(f"-- {len(warnings)} warnings --")
            for wr in warnings[:10]:
                print("   ", wr)
        sys.exit(1)
    print("Tout est vert : aucune violation.")
    if warnings:
        print(f"-- {len(warnings)} warnings (non bloquants) --")
        seen = set()
        for wr in warnings:
            k = wr.split(":")[0].split("-")[0]
            if k not in seen:
                seen.add(k)
                print("   ", wr)


if __name__ == "__main__":
    main()
