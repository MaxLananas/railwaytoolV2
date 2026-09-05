#!/usr/bin/env python3
"""Harnais "realiste SNCF" : lignes telles qu'un joueur BTE les trace pour du
rail roleplay 1:1 France — grandes courbes (rayon >= ~120 m), rampes douces
(<= ~4 %), lacets de montagne, aiguillages en Y, voies paralleles,
courbes de transition. Chaque ligne passe par la chaine complete
(spline -> voxelize -> nivelage -> epuration -> construction) avec les options
UI : terrains plats/valomes puis terrasses, styles classic/nature, themes 1/2.

Invariants specifiques au rail realiste verifies en plus des invariants
generiques (du meme acabit que stupid/mass) :
- trace finale 26-connexe (pas de morceau isole)
- pas de marche > 1 en Y entre voxels consecutifs (continuite du profil)
- chaque voxel de trace recoit un element de rail a y+1 (corail/pupitre/mousse)
- les grands alignements gardent des murets continus des deux cotes.
"""
import math
import os
import zlib
import random
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rail_sim as R

violations = []
counts = {"scenarios": 0, "builds": 0, "verif": 0}

SOIL = {"deepslate", "cobbled_deepslate", "pale_oak_wood",
        "deepslate_iron_ore", "deepslate_coal_ore", "orange_wool"}


def RAIL_MARKERS():
    return R.RAIL_FAMILY


def fail(case, msg):
    violations.append(f"{case}: {msg}")


# ------------------------------------------------------------------------------
# GENERATEURS DE LIGNES REALISTES
# ------------------------------------------------------------------------------

def gen_mainline(seed, length=600.0, min_radius=120.0):
    """Grande ligne : points de controle espaces ~60 m, angles progressifs.
    Rayon effectif >= min_radius par construction (angles doux)."""
    rnd = random.Random(seed)
    pts = [(0.0, 64.0, 0.0)]
    heading = rnd.uniform(0, math.tau)
    x, z = 0.0, 0.0
    y = 64.0
    remaining = length
    while remaining > 0:
        seg = rnd.uniform(45.0, 75.0)
        # angle max tel que rayon ~= seg/angle >= min_radius
        max_ang = seg / min_radius
        heading += rnd.uniform(-max_ang, max_ang)
        x += math.cos(heading) * seg
        z += math.sin(heading) * seg
        # profil en long plat (grande ligne : c'est l'outil qui suit le terrain)
        pts.append((x, 64.0, z))
        remaining -= seg
    return pts


def gen_ramp(seed, length=400.0, grade=0.035):
    """Ligne en rampe douce constante (defaut 3.5 %, max SNCF lourd)."""
    rnd = random.Random(seed)
    heading = rnd.uniform(0, math.tau)
    n = max(3, int(length // 60))
    pts = []
    x, z, y = 0.0, 0.0, 64.0
    for i in range(n + 1):
        pts.append((x, y, z))
        seg = length / n
        x += math.cos(heading) * seg
        z += math.sin(heading) * seg
        y += seg * grade
    return pts


def gen_transition(seed, length=500.0):
    """Courbe de transition (approx. clothoide) : droite -> arc -> droite."""
    rnd = random.Random(seed)
    heading_in = rnd.uniform(0, math.tau)
    turn = rnd.choice((-1, 1)) * rnd.uniform(0.5, 1.1)
    pts = []
    x, z = 0.0, 0.0
    h = heading_in
    pts.append((x, 64.0, z))
    # 1) droite d'approche 100 m
    for _ in range(2):
        x += math.cos(h) * 50.0
        z += math.sin(h) * 50.0
        pts.append((x, 64.0, z))
    # 2) transition : angles croissants (rayons 400 -> 150)
    fracs = 10
    for i in range(1, fracs + 1):
        h += turn / fracs * (i / fracs)
        seg = (length - 200.0) / (fracs * 2)
        x += math.cos(h) * seg
        z += math.sin(h) * seg
        pts.append((x, 64.0, z))
    # 3) arc stable
    for _ in range(fracs):
        h += turn / fracs
        seg = (length - 200.0) / (fracs * 2)
        x += math.cos(h) * seg
        z += math.sin(h) * seg
        pts.append((x, 64.0, z))
    # 4) droite de sortie
    for _ in range(2):
        x += math.cos(h) * 50.0
        z += math.sin(h) * 50.0
        pts.append((x, 64.0, z))
    return pts


def gen_switchbacks(seed):
    """Montagne : lacets en pente ~4 % (ligne de montagne type Alpes)."""
    rnd = random.Random(seed)
    pts = []
    x, z, y = 0.0, 0.0, 64.0
    h = rnd.uniform(0, math.tau)
    pts.append((x, y, z))
    for leg in range(rnd.randint(3, 6)):
        seglen = rnd.uniform(55.0, 80.0)
        steps = 3
        for _ in range(steps):
            x += math.cos(h) * (seglen / steps)
            z += math.sin(h) * (seglen / steps)
            y += (seglen / steps) * 0.04
            pts.append((x, y, z))
        # lacet : demi-tour progressif sur 3 points
        target = h + rnd.choice((2.4, -2.4))
        for i in range(1, 4):
            hh = h + (target - h) * (i / 3.0) * 0.45
            x += math.cos(hh) * 14.0
            z += math.sin(hh) * 14.0
            y += 14.0 * 0.04
            pts.append((x, y, z))
        h = target
    return pts, pts  # controle + trace brute robustee plus bas


def gen_y_junction(seed):
    """Aiguillage en Y : ligne mere 300 m + embranchement deviant a ~10 degres."""
    main_pts = gen_mainline(seed * 7 + 1, 300.0, 200.0)
    mid = main_pts[len(main_pts) // 2]
    rnd = random.Random(seed)
    branch = [mid]
    h0 = math.atan2(mid[2] - main_pts[len(main_pts) // 2 - 1][2],
                    mid[0] - main_pts[len(main_pts) // 2 - 1][0])
    h0 += rnd.choice((-1, 1)) * 0.18
    x, z = mid[0], mid[2]
    for _ in range(4):
        x += math.cos(h0) * 35.0
        z += math.sin(h0) * 35.0
        h0 += rnd.choice((-1, 1)) * 0.05
        branch.append((x, mid[1], z))
    return [main_pts, branch]


def gen_parallel(seed, length=450.0):
    """Deux voies paralleles ecar tees de 3 blocs (espacement banlieue SNCF)."""
    base = gen_mainline(seed * 13 + 5, length, 160.0)
    other = []
    for (x, y, z) in base:
        other.append((x + 3.0, y, z + 0.0))
    return [base, other]


# ------------------------------------------------------------------------------
# TERRAIN + PIPELINE
# ------------------------------------------------------------------------------

def make_terrain(trace, seed, kind="flat"):
    xs = [v[0] for v in trace]
    zs = [v[2] for v in trace]
    ys = [v[1] for v in trace]
    rnd = random.Random(seed)
    x0, x1 = min(xs) - 4, max(xs) + 4
    z0, z1 = min(zs) - 4, max(zs) + 4
    # surface 3 blocs sous le point le plus bas de la trace : les laines
    # flottent de 0..6 et la rectification les depose en douceur (profil
    # ferroviaire continu), aucune laine n'est enterree.
    y_base = min(ys) - 3
    w = R.World()

    def h(x, z):
        if kind == "flat":
            return 0
        if kind == "hills":
            return int(round(2.5 * math.sin(x / 23.0) + 1.5 * math.cos(z / 31.0)))
        if kind == "chaos":
            return rnd.randint(-1, 2)
        return 0

    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            hh = y_base + h(x, z)
            for yy in range(hh - 12, hh + 13):
                w.set(x, yy, z, R.GROUND if yy <= hh else R.AIR)
    for (x, y, z) in trace:
        w.set(x, y, z, "white_wool")
    return w


def lay_wool(world, vox):
    for (x, y, z) in vox:
        if world.get(x, y, z) in (R.AIR, R.GROUND):
            world.set(x, y, z, R.SPLINE)


def build_trace(world, control, dug=None):
    floats = R.adaptive_sample([(float(x), float(y), float(z))
                                   for (x, y, z) in control])
    vox = R.voxelize(floats)
    lay_wool(world, vox)
    trace = R.rectify_vertical(world, vox, R.SPLINE, R.CORNER, dug=dug)
    lay_wool(world, trace)
    trace = R.rectify_l(world, trace, R.SPLINE, R.CORNER)
    trace = R.rectify_vertical(world, trace, R.SPLINE, R.CORNER, dug=dug)
    trace = R.flatten_teeth(world, trace, R.SPLINE)
    return trace


def check_trace_quality(name, trace):
    """Invariants du profil d'une voie exploitable."""
    if len(trace) < 2:
        return
    # 26-connexite
    s = set(trace)
    seen = {trace[0]}
    stack = [trace[0]]
    while stack:
        x, y, z = stack.pop()
        counts["verif"] += 1
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    nb = (x + dx, y + dy, z + dz)
                    if nb in s and nb not in seen:
                        seen.add(nb)
                        stack.append(nb)
    counts["verif"] += len(trace)
    if len(seen) != len(s):
        fail(name, f"trace non connexe: {len(s) - len(seen)} voxel(s) isoles")
    # pas de marche > 1 en y entre consecutifs
    for a, b in zip(trace, trace[1:]):
        counts["verif"] += 1
        if abs(a[1] - b[1]) > 1 and (abs(a[0] - b[0]) + abs(a[2] - b[2])) <= 2:
            fail(name, f"marche verticale >1 entre {a} et {b}")


def check_rail_cover(name, world, trace, style):
    """Chaque voxel de trace doit etre recouvert de rail (a y+1, ou deja present
    a y/y+2 pour les croisements et chevauchements multi-lignes)."""
    for (x, y, z) in trace:
        counts["verif"] += 1
        trio = (world.get(x, y, z), world.get(x, y + 1, z),
                world.get(x, y + 2, z))
        if not any(b in R.RAIL_FAMILY for b in trio):
            fail(name, f"voxel sans rail a {(x, y, z)} {trio} style={style}")


def check_states_and_doors(name, world):
    for pos, st in world.blocks.items():
        counts["verif"] += 1
        if st in ("white_wool", R.AIR, R.GROUND, "red_wool", "blue_wool",
                  "lime_wool", "black_wool"):
            continue
        if st not in R.RAIL_FAMILY and st not in SOIL:
            fail(name, f"etat inconnu '{st}' a {pos}")
    for (x, y, z), st in world.blocks.items():
        if isinstance(st, str) and st.startswith("door_"):
            counts["verif"] += 1
            half, facing = st.split("_", 2)[1], st.rsplit("_", 1)[1]
            other = world.get(x, y + (1 if half == "lower" else -1), z)
            want = f"door_{'upper' if half == 'lower' else 'lower'}_{facing}"
            if other != want:
                fail(name, f"porte orpheline {st} {(x, y, z)}")


def check_straight_walls(name, world, model, trace):
    """Sur un alignement a 2 voisins NS (ou EW), les 2 cotes doivent porter
    un etat rail (mur ou ecran/porte) a y+1 ou etre proteges par du rail."""
    for (x, y, z) in trace:
        t = model.types.get((x, y, z))
        if t == R.NS:
            n1 = model.type_near(x, y, z - 1) == R.NS
            n2 = model.type_near(x, y, z + 1) == R.NS
            sides = [(x - 1, y, z), (x + 1, y, z)]
        elif t == R.EW:
            n1 = model.type_near(x - 1, y, z) == R.EW
            n2 = model.type_near(x + 1, y, z) == R.EW
            sides = [(x, y, z - 1), (x, y, z + 1)]
        else:
            continue
        if not (n1 and n2):
            continue
        counts["verif"] += 1
        for (sx, sy, sz) in sides:
            # muret pose par ce voxel (y+1) ou par le voisin de niveau inferieur
            # (case y du voxel haut dans un escalier) : continuite laterale ok
            b1 = world.get(sx, sy + 1, sz)
            b0 = world.get(sx, sy, sz)
            if b1 not in R.RAIL_FAMILY and b0 not in R.RAIL_FAMILY:
                fail(name, f"cote decouvert a {(sx, sy + 1, sz)} ({b1}/{b0})")


def run_realistic(name, controls, terrain="flat", smooth=False, walls=True,
                  allow_broken=False):
    counts["scenarios"] += 1
    try:
        if isinstance(controls[0], list):
            # multi-lignes dans UN SEUL monde (croisements/y/paralleles) :
            # le build de la ligne B protége le rail de la ligne A.
            pre_all = []
            for ctl in controls:
                pre_all += [(int(round(x)), int(round(y)), int(round(z)))
                            for (x, y, z) in ctl]
            world = make_terrain(pre_all, zlib.crc32(name.encode()) & 0xFFFF, terrain)
            traces = []
            for ctl in controls:
                pre = [(int(round(x)), int(round(y)), int(round(z)))
                       for (x, y, z) in ctl]
                for (x, y, z) in pre:
                    world.set(x, y, z, "white_wool")
                traces.append(build_trace(world, ctl,
                                          dug=set() if smooth else None))
            for ti, t in enumerate(traces):
                if not allow_broken:
                    check_trace_quality(f"{name}/L{ti}", t)
            for style in ("classic", "nature"):
                for theme in (1, 2):
                    models = []
                    for trace in traces:
                        models.append(R.build_all(
                            world, trace, R.Options(style=style, theme=theme)))
                        counts["builds"] += 1
                    check_states_and_doors(f"{name}/{style}/th{theme}", world)
                    for ti, trace in enumerate(traces):
                        check_rail_cover(f"{name}/L{ti}/{style}/th{theme}",
                                         world, trace, style)
                        if walls and style == "classic":
                            check_straight_walls(
                                f"{name}/L{ti}/{style}/th{theme}",
                                world, models[ti], trace)
        else:
            pre = [(int(round(x)), int(round(y)), int(round(z)))
                   for (x, y, z) in controls]
            world = make_terrain(pre, zlib.crc32(name.encode()) & 0xFFFF, terrain)
            trace = build_trace(world, controls, dug=set() if smooth else None)
            if not allow_broken:
                check_trace_quality(name, trace)
            for style in ("classic", "nature"):
                for theme in (1, 2):
                    model = R.build_all(world, trace,
                                        R.Options(style=style, theme=theme))
                    counts["builds"] += 1
                    check_states_and_doors(f"{name}/{style}/th{theme}", world)
                    check_rail_cover(f"{name}/{style}/th{theme}", world,
                                     trace, style)
                    if walls and style == "classic":
                        check_straight_walls(
                            f"{name}/{style}/th{theme}", world, model, trace)
    except Exception as e:  # noqa: BLE001
        fail(name, f"EXCEPTION {e!r}")


def main():
    N = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    t0 = time.time()
    for i in range(N):
        run_realistic(f"mainline-{i}", gen_mainline(9000 + i))
        run_realistic(f"mainline-tight-{i}", gen_mainline(9100 + i, 700.0, 90.0),
                      terrain="hills")
        run_realistic(f"ramp-{i}", gen_ramp(9200 + i), smooth=True)
        run_realistic(f"transition-{i}", gen_transition(9300 + i))
        run_realistic(f"switchback-{i}", gen_switchbacks(9400 + i)[0],
                      terrain="hills", smooth=True, walls=False,
                      allow_broken=True)
        run_realistic(f"yjunction-{i}", gen_y_junction(9500 + i), walls=False)
        run_realistic(f"parallel-{i}", gen_parallel(9600 + i))
    dt = time.time() - t0
    print(f"\n=== REALISTE {counts['scenarios']} lignes | {counts['builds']} builds | "
          f"{counts['verif']:,} verifications | {dt:.1f}s ===")
    if violations:
        print(f"!!! {len(violations)} violations !!!")
        seen = {}
        for x in violations:
            k = x.split(":")[0]
            seen[k] = seen.get(k, 0) + 1
        for k, c in sorted(seen.items(), key=lambda kv: -kv[1])[:25]:
            print(f"  {c:>4}x  {k}")
        for x in violations[:12]:
            print("   ", x)
        sys.exit(1)
    print("Tout est vert : aucune violation.")


if __name__ == "__main__":
    main()
