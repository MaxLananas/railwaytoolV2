#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
stress.py — harnais de tests massifs pour le moteur rail (rail_sim.py).
Génère des centaines de scénarios (géométries manuelles + splines aléatoires),
construit les deux designs pour toutes les options, et vérifie les invariants
des règles du script : états valides, murets/coraux/sides attendus, protection
des rails existants, idempotence du design classique, nulle part de sortie du
corridor, sécurité de la rectification verticale.
"""

import random
import sys

import rail_sim as R

violations = []
counts = {"scenarios": 0, "builds": 0}


def fail(case, msg):
    violations.append(f"{case}: {msg}")


# --- Validité des états posés (noms + attributs comme dans le jeu) ---
RAIL_STATES = {
    "wall_ns", "wall_eo", "wall_ne", "wall_nw", "wall_se", "wall_sw",
    "side_north", "side_south", "side_east", "side_west",
    "coral_south", "coral_east", "black_wool",
    "lectern_north", "lectern_east", "pale_moss_carpet", "pale_moss_block",
    "button_north", "button_east", "gravel",
    "leaf_1_north", "leaf_1_south", "leaf_1_east", "leaf_1_west",
    "leaf_2_north", "leaf_2_south", "leaf_2_east", "leaf_2_west",
    "leaf_3_north", "leaf_3_south", "leaf_3_east", "leaf_3_west",
    "leaf_4_north", "leaf_4_south", "leaf_4_east", "leaf_4_west",
} | {f"door_{h}_{f}" for h in ("lower", "upper")
     for f in ("north", "south", "east", "west")}
SOIL = {"deepslate", "cobbled_deepslate", "pale_oak_wood",
        "deepslate_iron_ore", "deepslate_coal_ore", "orange_wool"}
IGNORED = {R.GROUND, "white_wool", "red_wool", "blue_wool", "lime_wool", R.AIR}


def check_states(case, world):
    for pos, st in world.blocks.items():
        if st in IGNORED:
            continue
        if st not in RAIL_STATES and st not in SOIL:
            fail(case, f"état inconnu posé {st} à {pos}")


def check_corridor(case, world, trace):
    xs = [v[0] for v in trace]
    ys = [v[1] for v in trace]
    zs = [v[2] for v in trace]
    # marge basse +6 : le remplissage de support (gravel/sol) descend sous la trace
    box = (min(xs) - 2, max(xs) + 2, min(ys) - 8, max(ys) + 2, min(zs) - 2, max(zs) + 2)
    for (x, y, z), st in world.blocks.items():
        if st in IGNORED or st == R.GROUND:
            continue
        if not (box[0] <= x <= box[1] and box[2] <= y <= box[3] and box[4] <= z <= box[5]):
            fail(case, f"bloc hors corridor {st} à {(x, y, z)}")
            return


def snapshot(world):
    return dict(world.blocks)


def diff_worlds(a, b):
    d = []
    for k in set(a) | set(b):
        if a.get(k) != b.get(k):
            d.append((k, a.get(k, R.AIR), b.get(k, R.AIR)))
    return d


def straight_line(x0, z0, dx, dz, n, y=1, color="white_wool"):
    return [((x0 + dx * i), y, (z0 + dz * i)) for i in range(n)]


def diag_line(x0, z0, dx, dz, n, y=1):
    return [((x0 + dx * i), y, (z0 + dz * i)) for i in range(n)]


def make_world(trace, colors=None, flat_pad=4, height_fn=None):
    xs = [v[0] for v in trace]
    zs = [v[2] for v in trace]
    ys = [v[1] for v in trace]
    w = R.flat_world(min(xs) - flat_pad, max(xs) + flat_pad,
                     min(zs) - flat_pad, max(zs) + flat_pad, height_fn=height_fn)
    for x, y, z in trace:
        base = height_fn(x, z) if height_fn else 0
        for yy in range(base - 3, base + 1):
            w.set(x, yy, z, R.GROUND)
        for yy in range(base + 1, y):
            w.set(x, yy, z, R.GROUND)
        w.set(x, y, z, colors.get((x, y, z), "white_wool") if colors else "white_wool")
    return w, trace


def run_case(name, trace, colors=None, height_fn=None, styles=("classic", "nature"),
             strict_idempotent=False):
    counts["scenarios"] += 1
    for style in styles:
        for theme in (1, 2):
            for fill in (1, 2):
                counts["builds"] += 1
                w, tr = make_world(trace, colors, height_fn=height_fn)
                pre = snapshot(w)
                try:
                    R.build_all(w, tr, R.Options(style=style, theme=theme, fill_mode=fill))
                except Exception as e:
                    fail(f"{name}/{style}/t{theme}/f{fill}", f"EXCEPTION build_all: {e!r}")
                    return
                check_states(f"{name}/{style}", w)
                check_corridor(f"{name}/{style}", w, tr)
                post = snapshot(w)
                try:
                    R.build_all(w, tr, R.Options(style=style, theme=theme, fill_mode=fill))
                except Exception as e:
                    fail(f"{name}/{style}-rebuild", f"EXCEPTION: {e!r}")
                d = diff_worlds(post, snapshot(w))
                rail_states = set(RAIL_STATES) - {"black_wool"}
                destroyed = [x for x in d if x[1] in rail_states and x[2] != x[1]]
                if destroyed:
                    fail(f"{name}/{style}-protect", f"rail détruit au rebuild {destroyed[:3]}")
                if strict_idempotent and style == "classic" and d:
                    fail(f"{name}/classic-idempotence", f"rebuild modifie {d[:3]}")
                _ = pre


# --- Vérifications spécifiques ---

def level_block(world, x, y, z):
    return world.get(x, y, z)


def expect_classic_straight_ns(case, L, turn_start=None, turn_end=None):
    """Ligne NS de longueur L à x=10, z=10..10+L-1, y=1. Vérifie la pose."""
    trace = straight_line(10, 10, 0, 1, L)
    if turn_start == "E":
        trace = [(9, 1, 10)] + trace[1:]
    if turn_end == "E":
        trace = trace[:-1] + [(11, 1, 10 + L - 1)]
    w, tr = make_world(trace)
    R.build_all(w, tr, R.Options(style="classic"))
    res = {}
    for i, (x, y, z) in enumerate(tr):
        res[f"core{i}"] = w.get(x, y + 1, z)
        res[f"oud{i}"] = w.get(x - 1, y + 1, z)
        res[f"est{i}"] = w.get(x + 1, y + 1, z)
    return res


def check_straight_ns_middle(case, L):
    res = expect_classic_straight_ns("tmp", L)
    for i in range(1, L - 1):
        if res[f"core{i}"] != "coral_south":
            fail(f"{case}/L{L}", f"core{i}={res[f'core{i}']} != coral_south")
        if res[f"oud{i}"] != "wall_ns" or res[f"est{i}"] != "wall_ns":
            fail(f"{case}/L{L}", f"murets milieu {i}: {res[f'oud{i}']}/{res[f'est{i}']}")


def main():
    random.seed(1337)

    # 1) Lignes droites toutes longueurs 1..45, NS et EW (wall check en milieu)
    for L in range(1, 46):
        run_case(f"straight-ns-{L}", straight_line(10, 10, 0, 1, L), styles=("classic",), strict_idempotent=True)
        run_case(f"straight-ew-{L}", straight_line(10, 10, 1, 0, L), styles=("classic",), strict_idempotent=True)
        if 3 <= L <= 45:
            check_straight_ns_middle(f"straight-ns-{L}", L)

    # 2) Coins 90° toutes combinaisons de longueurs de bras (2,3,5,8,13,21) × 4 orientations
    arms = (2, 3, 5, 8, 13, 21)
    turns = [((0, 1), (1, 0)), ((0, 1), (-1, 0)), ((0, -1), (1, 0)), ((0, -1), (-1, 0))]
    for a in arms:
        for b in arms:
            for (d1, d2) in turns:
                p0 = (20, 1, 20)
                t1 = [((p0[0] + d1[0] * i), 1, (p0[2] + d1[1] * i)) for i in range(a)]
                t2 = [((t1[-1][0] + d2[0] * i), 1, (t1[-1][2] + d2[1] * i)) for i in range(1, b + 1)]
                run_case(f"corner-{a}x{b}-{d1}-{d2}", t1 + t2, styles=("classic", "nature"), strict_idempotent=True)

    # 3) Diagonales strictes longueur 1..10 raccordées aux 2 bouts (NS/EW × NS/EW), 2 sens
    for dl in range(1, 11):
        for first_t in ("ns", "ew"):
            for last_t in ("ns", "ew"):
                for sense in ((1, -1), (1, 1)):
                    t = straight_line(10, 10, 0, 1, 5) if first_t == "ns" \
                        else straight_line(10, 10, 1, 0, 5)
                    dstart = t[-1]
                    d = diag_line(dstart[0] + sense[0], dstart[2] + sense[1], sense[0], sense[1], dl)
                    last = d[-1]
                    if last_t == "ns":
                        t2 = straight_line(last[0], last[2] + 1, 0, 1, 5)
                    else:
                        t2 = straight_line(last[0] + 1, last[2], 1, 0, 5)
                    run_case(f"diag{dl}-{first_t}-{last_t}-{sense}", t + d + t2,
                             styles=("classic", "nature"), strict_idempotent=True)

    # 4) Vraies diagonales avec vérification fine des coraux/4 murets
    for dl in (2, 3, 4, 5, 6, 7, 8, 9, 10, 11):
        trace = straight_line(10, 10, 0, 1, 6)
        d = diag_line(11, 15, 1, 1, dl)
        trace += d
        trace += straight_line(d[-1][0] + 1, d[-1][2], 1, 0, 6)
        w, tr = make_world(trace)
        R.build_all(w, tr, R.Options(style="classic"))
        for i, (x, y, z) in enumerate(d):
            interior = 0 < i < dl - 1
            states4 = [w.get(x - 1, y + 1, z), w.get(x + 1, y + 1, z),
                       w.get(x, y + 1, z - 1), w.get(x, y + 1, z + 1)]
            c = w.get(x, y + 1, z)
            if c == "black_wool":
                continue
            walls_n = sum(1 for s in states4 if s.startswith("wall"))
            if interior and walls_n not in (2, 4):
                fail(f"true-diag-{dl}", f"diag{i} corail {c} murets {states4}")

    # 5) Escaliers / montées-descentes sur terrain variable
    for steps in (1, 2, 3, 5, 8):
        trace = []
        y = 1
        x, z = 15, 15
        for i in range(steps * 3):
            trace.append((x, y, z))
            if i % 3 == 2:
                y += 1
            z += 1
        run_case(f"stairs-{steps}", trace, styles=("classic", "nature"), strict_idempotent=True)

    # 6) Boucles / fourches / croisements
    square = (straight_line(10, 10, 1, 0, 8) + straight_line(17, 10, 0, 1, 8)
              + straight_line(17, 17, -1, 0, 8)[1:] + straight_line(10, 17, 0, -1, 8)[1:])
    run_case("square-loop", square, strict_idempotent=True)
    fork = (straight_line(10, 5, 0, 1, 10)
            + straight_line(10, 14, 1, 1, 6)[1:] + straight_line(10, 14, -1, 1, 6)[1:])
    run_case("fork", fork)
    cross = straight_line(10, 4, 0, 1, 12) + straight_line(4, 10, 1, 0, 12)
    run_case("crossing-red-blue", cross,
             colors={v: "red_wool" for v in straight_line(10, 4, 0, 1, 12)}
             | {v: "blue_wool" for v in straight_line(4, 10, 1, 0, 12)})

    # 7) Traces colorées arbitraires (overrides) sur géométrie droite
    colors1 = {v: "red_wool" for v in straight_line(10, 10, 0, 1, 6)}
    colors1 |= {v: "blue_wool" for v in straight_line(10, 16, 0, 1, 6)}
    run_case("partial-color", straight_line(10, 10, 0, 1, 12), colors=colors1)
    run_case("force-diag-on-straight", straight_line(10, 10, 0, 1, 12),
             colors={v: "lime_wool" for v in straight_line(10, 10, 0, 1, 12)})

    # 8) Marches aléatoires 26-connexes sans rebroussement (seedé, 400 cas)
    for seed in range(400):
        rnd = random.Random(seed)
        t = [(15, 1, 15)]
        dx, dz = 0, 1
        for _ in range(rnd.randint(4, 40)):
            if rnd.random() < 0.4:
                dx, dz = rnd.choice(((1, 0), (-1, 0), (0, 1), (0, -1), (dx, dz)))
            last = t[-1]
            ny = last[1] + rnd.choice((0, 0, 0, 1, -1))
            if 0 <= ny <= 12:
                t.append((last[0] + dx, ny, last[2] + dz))
            else:
                t.append((last[0] + dx, last[1], last[2] + dz))
        run_case(f"walk-{seed}", t)

    # 9) Splines réelles : points de contrôle aléatoires (300 cas)
    for seed in range(300):
        rnd = random.Random(10_000 + seed)
        n = rnd.randint(2, 5)
        ctrl = [(rnd.randint(4, 28), rnd.randint(1, 8), rnd.randint(4, 36)) for _ in range(n)]
        floats = R.catmull_rom_points(ctrl)
        vox = R.voxelize(floats)
        w, _ = make_world(vox)
        trace = list(vox)
        try:
            trace = R.rectify_vertical(w, trace, R.SPLINE, R.CORNER)
            trace = R.rectify_l(w, trace, R.SPLINE, R.CORNER)
            trace = R.rectify_vertical(w, trace, R.SPLINE, R.CORNER)
            trace = R.dedupe_columns(w, trace, R.SPLINE)
        except Exception as e:
            fail(f"spline-{seed}", f"EXCEPTION rectif: {e!r}")
            continue
        for style in ("classic", "nature"):
            counts["builds"] += 1
            try:
                R.build_all(w, trace, R.Options(style=style))
            except Exception as e:
                fail(f"spline-{seed}/{style}", f"EXCEPTION build: {e!r}")
        check_states(f"spline-{seed}", w)
        check_corridor(f"spline-{seed}", w, trace)

    # 10) Rectif verticale : traces volantes hautes/enterrées profondes
    for h in (2, 5, 14, 19):
        run_case(f"fly-{h}", straight_line(10, 10, 0, 1, 8, y=1 + h))
    underground = straight_line(10, 10, 0, 1, 8, y=1)
    run_case("buried-trace", underground,
             height_fn=lambda x, z: 4, styles=("classic",))

    # 11) Idempotence nature n'est pas requise ; mais un rebuild classique courant
    #     sur un rail existant ne doit rien modifier (protection complète)
    tr = straight_line(10, 10, 0, 1, 12)
    w, _ = make_world(tr)
    R.build_all(w, tr, R.Options(style="classic"))
    s1 = snapshot(w)
    R.build_all(w, tr, R.Options(style="classic", theme=2))
    if diff_worlds(s1, snapshot(w)):
        fail("protect-theme-switch", "le thème 2 réécrit par-dessus le thème 1")

    print(f"\n=== {counts['scenarios']} scénarios, {counts['builds']} builds ===")
    if violations:
        print(f"!!! {len(violations)} VIOLATIONS !!!")
        import re
        from collections import Counter
        grp = Counter()
        ex = {}
        for v in violations:
            key = re.sub(r"[0-9]+", "N", v.split(": ")[0]) + "|" + \
                  re.sub(r"[0-9]+", "N", v.split(": ", 1)[1] if ": " in v else "")
            grp[key] += 1
            ex.setdefault(key, v)
        for key, n in grp.most_common(40):
            print(f"{n:5d}x  {ex[key]}")
        sys.exit(1)
    print("Tout est vert : aucune violation.")


if __name__ == "__main__":
    main()
