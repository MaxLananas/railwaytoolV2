#!/usr/bin/env python3
"""Reproduction EXACTE des bugs remontés en jeu (captures utilisateur) :

1. Ligne S->N qui dérive légèrement vers l'ouest + virage sec, terrain plat :
   aucun bloc construit ne doit « voler » (tout bloc posé a un support direct
   sous lui — c'est ça, un truc volant : mur/sol à 2 blocs au-dessus du sol).
2. Thème clair : panneaux de porte basse UNIQUEMENT (jamais de moitié upper).
3. Design nature : à chaque changement de X ou de Z dans un coin (jog), un
   leaf_litter à 3 segments doit apparaître au coin concave (script Rouquinator).

Ce harnais existe parce que les autres batteries laissaient passer ces bugs :
le monde hostile y est différent du vrai terrain plat de l'utilisateur.
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
    """Ligne S->N douce qui glisse vers l'ouest, puis virage sec vers l'ouest.
    y alterne 61.35/61.65 : la voxelisation Catmull-Rom produit des dents
    61/62 exactement comme en jeu sur terrain plat."""
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
        fail(tag, "trace quasi vide")
        return

    # 1) support : aucun bloc posé n'a de l'air directement sous lui
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
        fail(tag, f"{len(floating)} bloc(s) flottant(s): {floating[:3]}")

    # 2) dents : sur terrain plat, la trace rectifiée ne doit plus avoir de
    #    plateau de ±1 (c'est ce qui rendait des fragments visibles en l'air)
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
        fail(tag, f"{teeth} dent(s)/plateau(x) vertical(aux) résiduel(s)")

    # 3) portes : jamais de moitié upper (thème clair)
    uppers = [(p, st) for p, st in world.blocks.items()
              if isinstance(st, str) and st.startswith("door_upper")]
    if uppers:
        fail(tag, f"{len(uppers)} porte(s) complete(s): {uppers[:2]}")

    if style == "nature":
        # 4) coins : à chaque jog (voisin diag + lateral) un leaf_3 a proximité
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
            # voisin diagonal + voisin latéral = dérive en coin : il faut un leaf_3 proche
            found = False
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    st = world.get(x + dx, y + 1, z + dz)
                    if isinstance(st, str) and st.startswith("leaf_3"):
                        found = True
            if not found:
                missings.append((x, y, z))
        if missings:
            fail(tag, f"{len(missings)} jog(s) sans leaf_3: {missings[:4]}")


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
            print("ROUGE", v)
        print(f"{len(violations)} violation(s).")
        sys.exit(1)
    print("Tout est vert : aucune violation.")


if __name__ == "__main__":
    main()
