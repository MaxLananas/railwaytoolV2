#!/usr/bin/env python3
"""Scan des piliers de support : aucun bloc parasite autour.

Pour chaque scène (corpus parité + vallées dédiées), le flux complet est
rejoué (terrain -> trace -> design -> support). Ensuite :

1. Détection de tous les PILIERS : empilement vertical de sol (comblement)
   posé par le build, coiffé d'un bloc de rail et posé sur du terrain.
2. Autour de chaque pilier (boîte 5x5 sur toute sa hauteur +/-2) on liste
   tout bloc "parasite" : bloc posé qui n'est ni le pilier, ni le ruban de
   rail au sommet, et qui FLOTTE (air directement dessous) ou est collé au
   flanc du pilier à mi-hauteur (verrue de décor en plein vide).
3. Pour chaque pilier affecté, diagramme complet en coupe verticale
   (tranche x/y à z=pilier et tranche z/y à x=pilier) avec tous les blocs.

Sortie rouge (exit 1) au moindre parasite : un pilier propre n'a rien autour
que du rail à son sommet et du sol à sa base.
"""
import sys
import os
import importlib.util

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rail_sim as R

_spec = importlib.util.spec_from_file_location(
    "parity_export", os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                  "parity_export.py"))
pe = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pe)

SOIL_CORES = {"deepslate", "cobbled_deepslate", "pale_oak_wood",
              "deepslate_iron_ore", "deepslate_coal_ore", "gravel"}
TERRAIN = {"grass_block", R.GROUND, None, R.AIR, "water"}

violations = []
stats = {"pillars": 0, "scenes": 0, "clean": 0}


def soil(st):
    return st in SOIL_CORES


def find_pillars(world, pre_keys):
    """Colonnes de sol posees par le build coiffees de rail, basees au sol."""
    pillars = []
    cols = {}
    for (x, y, z), st in world.blocks.items():
        if (x, y, z) in pre_keys:
            continue
        if soil(st):
            cols.setdefault((x, z), []).append(y)
    for (x, z), ys in cols.items():
        ys = sorted(set(ys))
        # segments verticaux contigus
        seg = [ys[0]]
        for y in ys[1:]:
            if y == seg[-1] + 1:
                seg.append(y)
            else:
                pillars.append((x, z, seg))
                seg = [y]
        pillars.append((x, z, seg))
    out = []
    for (x, z, seg) in pillars:
        top, base = seg[-1], seg[0]
        above = world.get(x, top + 1, z)
        below = world.get(x, base - 1, z)
        if above not in R.RAIL_FAMILY:
            continue   # simple remblai sans rail au-dessus : pas un pilier
        if len(seg) < 2:
            continue   # base normale de colonne (1 sol sous le rail), voulue
        if below in (R.AIR, None):
            continue   # verifie ailleurs (flottant)
        out.append((x, z, base, top))
    return out


def scan_pillar(world, x, z, base, top):
    """Retourne (parasites, cellules_boîte) autour du pilier x,z base..top."""
    parasites = []
    for dx in (-2, -1, 0, 1, 2):
        for dz in (-2, -1, 0, 1, 2):
            if dx == 0 and dz == 0:
                continue
            for y in range(base - 1, top + 4):
                st = world.get(x + dx, y, z + dz)
                if st in TERRAIN:
                    continue
                # ruban de rail au sommet : niveau top+1..top+3 hors pilier,
                # OK seulement s'il s'appuie sur la suite de la voie
                below = world.get(x + dx, y - 1, z + dz)
                floating = below in (R.AIR, None)
                flank = (y <= top)  # à hauteur du pilier = verrue en plein vide
                if floating and (flank or st not in R.RAIL_FAMILY):
                    parasites.append((x + dx, y, z + dz, st))
    return parasites


def diagram(world, x, z, base, top, parasites):
    """Coupe verticale x/y (à z) et z/y (à x) autour du pilier."""
    y0, y1 = base - 1, top + 3
    mark = {(px, py, pz) for (px, py, pz, _st) in parasites}

    def glyph(cx, cy, cz):
        st = world.get(cx, cy, cz)
        g = "." if st in (R.AIR, None) else R.GLYPHS.get(st, "s" if soil(st) else "?")
        if st == "grass_block":
            g = ","
        if (cx, cy, cz) in mark:
            return R.RED(g if g.strip() else "!")
        return g

    lines = [f"    pilier ({x},{z}) base=y{base} sommet=y{top}"]
    lines.append("    tranche x/y (z=%d)  x = %d..%d" % (z, x - 3, x + 3))
    lines.append("       " + "".join(str((x + dx) % 10) for dx in range(-3, 4)))
    for y in range(y1, y0 - 1, -1):
        row = "".join(glyph(x + dx, y, z) for dx in range(-3, 4))
        if row.strip("."):
            lines.append(f"  y={y:>3} {row}")
    lines.append("    tranche z/y (x=%d)  z = %d..%d" % (x, z - 3, z + 3))
    lines.append("       " + "".join(str((z + dz) % 10) for dz in range(-3, 4)))
    for y in range(y1, y0 - 1, -1):
        row = "".join(glyph(x, y, z + dz) for dz in range(-3, 4))
        if row.strip("."):
            lines.append(f"  y={y:>3} {row}")
    if parasites:
        lines.append("    parasites: " + ", ".join(
            f"{st}@({px},{py},{pz})" for (px, py, pz, st) in parasites[:12]))
    return "\n".join(lines)


report = []


def process(sid, world, pre_keys):
    stats["scenes"] += 1
    pillars = find_pillars(world, pre_keys)
    stats["pillars"] += len(pillars)
    dirty = 0
    for (x, z, base, top) in pillars:
        parasites = scan_pillar(world, x, z, base, top)
        # Diagramme complet dans le rapport, parasite ou non — l'utilisateur
        # veut pouvoir inspecter chaque pilier visuellement.
        report.append(
            f"[{sid}] pilier ({x},{z}) y{base}..y{top} : "
            + (f"{len(parasites)} PARASITE(S)" if parasites else "propre")
            + "\n" + diagram(world, x, z, base, top, parasites))
        if parasites:
            dirty += 1
            violations.append(
                f"[{sid}] pilier ({x},{z}) y{base}..y{top} : "
                f"{len(parasites)} parasite(s)\n"
                + diagram(world, x, z, base, top, parasites))
    if not dirty:
        stats["clean"] += 1


def scenes_vallees():
    """Quelques creux/vallons dédiés pour forcer de vrais piliers."""
    import realistic_stress as RS

    def valley(h0=62, depth=4, width=2):
        def h(x, z):
            return h0 - depth if (x + z) % 7 < width else h0
        return h

    out = []
    line = [[(6, 63, 20), (16, 63, 20), (26, 63, 20), (34, 63, 20)]]
    for style, suf in (("classic", "cs"), ("nature", "na")):
        sc = pe.Scene(f"vallee-{suf}", line, (2, 40, 14, 26), valley(),
                      style, 1)
        out.append(sc)
    return out


def main():
    scenes = pe.scenes() + scenes_vallees()
    for sc in scenes:
        world, traces, bad, models = sc.run()
        if bad:
            violations.append(f"[{sc.sid}] self-check invalide: {bad[:2]}")
            continue
        pre_keys = set()  # le run ne rend pas les pre-clés : tout le monde
        # pré-existant est terrain (grass/GROUND) => filtré par TERRAIN.
        process(sc.sid, world, pre_keys)
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "pillar_report.txt")
    with open(out, "w") as f:
        f.write("\n\n".join(report))
    print(f"=== PILLAR SCAN : {stats['scenes']} scenes | "
          f"{stats['pillars']} piliers | {stats['clean']} scenes propres ===")
    print(f"diagrammes complets de chaque pilier -> {out}")
    if violations:
        show = int(os.environ.get("PILLAR_DIAG", "12"))
        print(f"{len(violations)} pilier(s) avec parasites "
              f"(diagrammes complets, max {show} affiches) :")
        for v in violations[:show]:
            print(v)
        sys.exit(1)
    print("OK : aucun bloc parasite autour des piliers.")


if __name__ == "__main__":
    main()
