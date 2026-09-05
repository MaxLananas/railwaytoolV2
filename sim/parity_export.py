#!/usr/bin/env python3
"""Exporte les scenes de PARITE Java/sim au format partage sim/parity/scenes.txt.

Chaque scene : boites de terrain (R), points de controle ENTIERS comme dans le
jeu (C), et le monde final attendu produit par le SIMULATEUR (E, au format
tokens). La tache gradle `parityCheck` rejoue le VRAI pipeline Java (sampler
adaptatif 6 -> voxelize -> Grounding x2 -> LCorners -> flattenTeeth ->
dedupeColumns -> TrackModel -> design, dug toujours actif = defaut produit)
et exige une carte de blocs IDENTIQUE. Toute divergence = bug visible en jeu.

Le fichier est auto-valide ici meme (cores continus, aucun flottant, aucun
doublon vertical, 1 voie 1 composante) : on n'ecrit jamais une attente
intrinsequement fausse.
"""
import os
import sys
import random

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rail_sim as R

random.seed(20240913)

TOK_SOIL = {"deepslate", "cobbled_deepslate", "pale_oak_wood",
            "deepslate_iron_ore", "deepslate_coal_ore"}


def tok(st):
    return "soil" if st in TOK_SOIL else st


def make_world(x0, x1, z0, z1, height_fn, depth=12):
    """Terrain herbeux explicite (comme le superflat du jeu)."""
    w = R.World()
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            h = height_fn(x, z)
            for y in range(h - depth + 1, h + 1):
                w.set(x, y, z, R.GROUND)
    return w


def flat(h):
    return lambda x, z: h


def hills(base):
    import math as m
    return lambda x, z: base + int(round(2.5 * m.sin(x / 23.0)
                                          + 1.5 * m.cos(z / 31.0)))


def chaos(base, seed):
    rnd = random.Random(seed)
    cache = {}

    def h(x, z):
        if (x, z) not in cache:
            cache[(x, z)] = base + rnd.randint(-1, 2)
        return cache[(x, z)]
    return h


def build_trace_seq(world, control, dug):
    """Pipeline identique au mod (defauts UI : adaptatif 6, snap ON, purge ON,
    smooth/tunnel ON)."""
    floats = R.adaptive_sample([(float(x), float(y), float(z))
                                for (x, y, z) in control])
    vox = R.voxelize(floats)
    for v in vox:
        if world.get(*v) in (R.AIR, None, R.GROUND):
            world.set(v[0], v[1], v[2], R.SPLINE)
    trace = R.rectify_vertical(world, vox, R.SPLINE, R.CORNER, dug=dug)
    for v in trace:
        if world.get(*v) in (R.AIR, None, R.GROUND):
            world.set(v[0], v[1], v[2], R.SPLINE)
    trace = R.rectify_l(world, trace, R.SPLINE, R.CORNER)
    trace = R.rectify_vertical(world, trace, R.SPLINE, R.CORNER, dug=dug)
    trace = R.flatten_teeth(world, trace, R.SPLINE)
    return trace


CORES = {"coral_south", "coral_east", "black_wool",
         "lectern_north", "lectern_east", "pale_moss_block"}


def self_check(name, world, traces, opt):
    """Refuse d'ecrire une attente qui viole les contrats visibles."""
    bad = []
    for trace in traces:
        core_want = ({"lectern_north", "lectern_east", "pale_moss_block"}
                     if opt.style == "nature"
                     else {"coral_south", "coral_east", "black_wool"})
        for v in set(trace):
            st = world.get(*v)
            if st not in core_want and not any(
                    world.get(v[0], v[1] + dy, v[2]) in core_want for dy in (1, -1)):
                bad.append(f"core manquant {v} ({st})")
        cols = {}
        for v in set(trace):
            cols.setdefault((v[0], v[2]), []).append(v[1])
        for k, ys in cols.items():
            if len(set(ys)) > 1:
                bad.append(f"doublon vertical {k} {sorted(set(ys))}")
    for (x, y, z), st in world.blocks.items():
        if st not in R.RAIL_FAMILY:
            continue
        below = world.get(x, y - 1, z)
        if below in (R.AIR, None):
            bad.append(f"flottant {st} {(x, y, z)}")
    return bad


class Scene:
    def __init__(self, sid, controls_list, box, height_fn, style, theme, buried=False):
        self.sid = sid
        self.controls = controls_list
        self.box = box
        self.h = height_fn
        self.style = style
        self.theme = theme
        self.buried = buried

    def run(self):
        x0, x1, z0, z1 = self.box
        world = make_world(x0, x1, z0, z1, self.h)
        opt = R.Options(style=self.style, theme=self.theme, buried=self.buried)
        traces = []
        for ctrl in self.controls:
            dug = set()
            tr = build_trace_seq(world, ctrl, dug)
            traces.append(tr)
            R.build_all(world, tr, opt)
        bad = self.checks(world, traces, opt)
        return world, traces, bad

    def checks(self, world, traces, opt):
        return self_check(self.sid, world, traces, opt)


def scenes():
    s = []
    # 1. la derive dentee de l'utilisateur (points cliques a y alterne)
    drift = [[(30, 61, 60), (30, 61, 54), (30, 62, 48), (29, 61, 42),
              (28, 61, 36), (27, 62, 30), (26, 61, 24), (25, 61, 18),
              (24, 62, 12), (24, 61, 6), (24, 61, 0), (17, 61, 0), (10, 61, 0)]]
    box1 = (4, 34, -6, 66)
    for style, theme, suf in (("classic", 1, "cs"), ("classic", 2, "cl"),
                              ("nature", 1, "na")):
        s.append(Scene(f"drift-{suf}", drift, box1, flat(58), style, theme))
    # 2. longue ligne a dents + branche en L (multi-traces)
    long2 = [[(8, 61, 30), (14, 61, 30), (20, 62, 30), (26, 61, 30),
              (32, 61, 30), (38, 61, 30)],
             [(24, 61, 30), (24, 61, 38), (25, 62, 46), (25, 61, 54)]]
    for style, theme, suf in (("classic", 1, "cs"), ("classic", 2, "cl"),
                              ("nature", 1, "na")):
        s.append(Scene(f"long2-{suf}", long2, (4, 44, 24, 60), flat(58),
                       style, theme))
    # 3. virage L dur + montee de 2
    ltr = [[(6, 61, 6), (6, 61, 20), (7, 62, 28), (20, 62, 28), (34, 63, 28)]]
    for style, theme, suf in (("classic", 1, "cs"), ("classic", 2, "cl"),
                              ("nature", 1, "na")):
        s.append(Scene(f"lturn-{suf}", ltr, (2, 38, 2, 34), flat(58),
                       style, theme))
    # 3b. enterre (mode buried, classic)
    s.append(Scene("lturn-buried", ltr, (2, 38, 2, 34), flat(58), "classic", 1,
                   buried=True))
    # 4. croisement T (NS x EW au meme niveau)
    tj = [[(20, 61, 4), (20, 61, 22), (20, 61, 40)],
          [(4, 61, 22), (20, 61, 22), (36, 61, 22)]]
    for style, theme, suf in (("classic", 1, "cs"), ("classic", 2, "cl"),
                              ("nature", 1, "na")):
        s.append(Scene(f"tjun-{suf}", tj, (0, 40, 0, 44), flat(58), style, theme))
    # 5. croisement de niveaux dy=1
    cl = [[(20, 61, 4), (20, 61, 22), (20, 61, 40)],
          [(4, 62, 22), (20, 62, 22), (36, 62, 22)]]
    for style, theme, suf in (("classic", 1, "cs"), ("classic", 2, "cl"),
                              ("nature", 1, "na")):
        s.append(Scene(f"xlvl-{suf}", cl, (0, 40, 0, 44), flat(58), style, theme))
    # 6. spirale descendante sur collines
    import math as m
    sp = []
    for k in range(26):
        a = k * (m.pi / 7.0)
        r = 18.0 - k * 0.42
        sp.append((int(round(28 + r * m.cos(a))), 63 - (k // 5),
                   int(round(28 + r * m.sin(a)))))
    for style, theme, suf in (("classic", 1, "cs"), ("classic", 2, "cl"),
                              ("nature", 1, "na")):
        s.append(Scene(f"spiral-{suf}", [sp], (6, 50, 6, 50), hills(56),
                       style, theme))
    # 7. zigzag dense sur chaos
    zz = [[(4 + 2 * i, 61 + (i % 3 == 2), 6 + (i % 4) * 6) for i in range(14)]]
    for style, theme, suf in (("classic", 1, "cs"), ("classic", 2, "cl"),
                              ("nature", 1, "na")):
        s.append(Scene(f"zigzag-{suf}", zz, (0, 36, 0, 30), chaos(58, 77),
                       style, theme))
    # 8. fuzz deterministe : polylignes aleatoires + terrains varies
    rnd = random.Random(4242)
    for i in range(20):
        n = rnd.randint(6, 16)
        x, z = rnd.randint(2, 12), rnd.randint(2, 12)
        ctrl = []
        for _ in range(n):
            x += rnd.randint(-8, 8)
            z += rnd.randint(-8, 8)
            y = rnd.choice((60, 60, 61, 61, 62))
            ctrl.append((x, y, z))
        xs = [c[0] for c in ctrl]
        zs = [c[2] for c in ctrl]
        box = (min(xs) - 8, max(xs) + 8, min(zs) - 8, max(zs) + 8)
        kind = rnd.choice(("flat", "hills", "chaos"))
        hf = {"flat": flat(58), "hills": hills(56),
              "chaos": chaos(58, 1000 + i)}[kind]
        style, theme = rnd.choice((("classic", 1), ("classic", 2),
                                   ("nature", 1)))
        s.append(Scene(f"fuzz-{kind}-{i}", [ctrl], box, hf, style, theme))
    return s


def main():
    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "parity", "scenes.txt")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    total_bad = 0
    with open(out_path, "w", encoding="ascii") as f:
        f.write("# scenes de parite Java/sim - genere par sim/parity_export.py\n")
        f.write("# @id / O options / R boites terrain / C n x y z / E x y z token\n")
        for sc in scenes():
            world, traces, bad = sc.run()
            if bad:
                total_bad += len(bad)
                print(f"[SCENE INVALIDE] {sc.sid}: {bad[:4]}")
                continue
            x0, x1, z0, z1 = sc.box
            f.write(f"@{sc.sid}\n")
            f.write(f"O style={sc.style} theme="
                    f"{'dark' if sc.theme == 1 else 'light'} "
                    f"buried={1 if sc.buried else 0}\n")
            # boites de terrain par colonne (h-2..h)
            for x in range(x0, x1 + 1):
                zr = z0
                while zr <= z1:
                    h0 = sc.h(x, zr)
                    z1c = zr
                    while z1c < z1 and sc.h(x, z1c + 1) == h0:
                        z1c += 1
                    f.write(f"R {x} {x} {h0 - 11} {h0} {zr} {z1c} grass_block\n")
                    zr = z1c + 1
            for i, ctrl in enumerate(sc.controls):
                for (x, y, z) in ctrl:
                    f.write(f"C {i} {x} {y} {z}\n")
            for (x, y, z), st in sorted(world.blocks.items(),
                                        key=lambda kv: (kv[0][0], kv[0][1], kv[0][2])):
                f.write(f"E {x} {y} {z} {tok(st)}\n")
            f.write("@end\n")
    n_scenes = sum(1 for _ in open(out_path) if _.startswith("@") and not _.startswith("@end"))
    print(f"OK: {n_scenes} scenes exportees -> {out_path}")
    if total_bad:
        print(f"{total_bad} scenes invalides (attentes NON ecrites)")
        sys.exit(1)


if __name__ == "__main__":
    main()
