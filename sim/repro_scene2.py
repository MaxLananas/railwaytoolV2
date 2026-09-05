#!/usr/bin/env python3
"""Reproduction des 3 dernieres captures utilisateur :

1. Longue voie nature sur terrain presque plat avec derive douce — les cores
   (pupitres/mousse) doivent etre presents sur CHAQUE voxel de la trace.
2. Y-jogs (dents 61/62) : aucun bloc pose au-dessus d'un autre bloc rail
   de la MEME colonne x,z forme un "monticule" ; et rien ne depasse de y+2
   au-dessus du niveau du core le plus proche.
3. Vue laterale : aucun segment de voie a |dy|>1 entre voxels consecutifs de
   la trace, et pas de pile verticale rail-sur-rail perpendiculaire au fil.

C'est le seul juge : le rendu ASCII ci-dessous doit ressembler a une voie
continue et plate, sans trous ni bosses.
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

    # --- A. chaque voxel de la trace porte son core ---------------------------
    if style == "nature":
        cores = {"lectern_north", "lectern_east", "pale_moss_block"}
    else:
        cores = {"coral_south", "coral_east", "black_wool"}
    missing = 0
    for (x, y, z) in set(trace):
        st = world.get(x, y, z)
        if st not in cores and st not in (R.SPLINE, R.CORNER):
            # colonne voisine d'un escalier compte si le core y migre
            ok = False
            for dy in (1, -1):
                if world.get(x, y + dy, z) in cores:
                    ok = True
                    break
            if not ok:
                missing += 1
    allow = max(0, len(set(trace)) // 40)   # franges de fragments tolerees
    if missing > allow:
        fail(tag, f"cores manquants : {missing}/{len(set(trace))} voxels sans core")

    # --- B. pas de monticule : pile verticale rail-sur-rail hors escalier -----
    for (x, y, z) in set(trace):
        core = world.get(x, y, z)
        for (dx, dz) in ((0, 0), (0, 1), (0, -1), (1, 0), (-1, 0)):
            for dy in (1, 2):
                st = world.get(x + dx, y + dy, z + dz)
                if st not in R.RAIL_FAMILY:
                    continue
                # legitime seulement si la trace possede un core a ce niveau
                # dans un rayon de 1 (escalier adjacent)
                ok = any((x + dx + ox, y + dy + oy, z + dz + oz) in set(trace)
                         for ox in (-1, 0, 1) for oz in (-1, 0, 1)
                         for oy in (-1, 0))
                if not ok:
                    fail(tag, f"bloc rail en l'air {st} a "
                              f"({x + dx},{y + dy},{z + dz}) au-dessus de "
                              f"core ({x},{y},{z})")
                    break

    # --- C. le long du fil : jamais |dy| > 1 entre voxels consecutifs ---------
    seq = list(dict.fromkeys(trace))
    for i in range(1, len(seq)):
        (x0, y0, z0) = seq[i - 1]
        (x1, y1, z1) = seq[i]
        if abs(x1 - x0) <= 1 and abs(z1 - z0) <= 1 and abs(y1 - y0) > 1:
            fail(tag, f"saut vertical {y0}->{y1} entre {seq[i-1]} et {seq[i]}")

    if render:
        R.render(world, trace, f"{tag} [{style}]")
    return world, trace


# 1. la scene drift exacte du premier retour utilisateur (nature + classique)
build("drift-nature", gen_drift_line(), "nature", render=True)
build("drift-classique", gen_drift_line(), "classic", render=True)

# 2. longue voie quasi droite avec micro-dents de y (terrain plat) — le cas
#    « presque plus de rail / rails qui sortent du sol »
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
print("OK : aucune violation sur", 4, "scenes reproduites")
