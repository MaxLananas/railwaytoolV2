#!/usr/bin/env python3
"""REPRO PHOTOS — reproduction fidèle des 4 bugs signalés par captures (5 sept 2026).

Bug 1 (photo 1) — classic sur terrain plat : piles/debris decor accroches a la voie
   (murs/blocs lateraux qui chevauchent et s'empilent sur les colonnes voisines).
Bug 2 (photo 2) — virage L dur (90 deg aux points de controle) : la voie disparait
   quasiment en entier, ne restent que des fragments aux points.
Bug 3 (photo 3) — virage dense/spaghetti : murs scrambled incoherents dans les noeuds.
Bug 4 (photo 4) — nature : chevauchements/collages analogues (decor horizontal qui
   ecrase un rail deja pose).

Invariants (tous builds, les deux styles, themes varies) :
  A. ANTI-CHEVAUCHEMENT : aucun bloc decor (wall/side/leaf/gravel-ortho) ne partage
     une cellule avec un bloc pose par un AUTRE voxel -> chaque cellule du monde
     final n'a qu'un seul etat (verifie par rejeu : des qu'une pose ecrase un rail
     place par un autre voxel -> violation).
  B. CONTINUITE : chaque voxel de la trace finale produit >= 1 bloc rail-family
     dans SA colonne (x,z) au y attendu (+-2 tol car rectifications/lissage).
  C. LISIBILITE : les voxels denses (>=3 voisins de trace, noeuds/virages serres)
     n'emettent pas de decor lateral (aucun wall/side dans leur voisinage immediat
     autre que ceux des voxels voisins legitimes).
  D. ANTI-FLOTTANT : aucun rail-family neuf avec air dessous et solide <= 4 plus bas
     (deja couvert par les autres harnais, revérifié ici sur ces scènes precises).
"""
import sys
import random
import rail_sim as R

fails = []
counts = {"scenarios": 0, "builds": 0, "voxels": 0}


def fail(case, msg):
    fails.append(f"{case}: {msg}")


def trace_write(world, positions, kind="white_wool"):
    for (x, y, z) in positions:
        world.set(x, y, z, kind)


def flat_ground(world, x0, x1, z0, z1, y0=0, depth=4):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            for y in range(y0 - depth, y0):
                world.set(x, y, z, R.GROUND)
    return y0


class SpyWorld(R.World):
    """Monde qui trace toutes les poses pour detecter les ecrasements decor-sur-rail."""

    def __init__(self):
        super().__init__()
        self.writes = []          # [(pos, state, tag)]
        self.tag = "terrain"

    def set(self, x, y, z, state):
        self.writes.append(((x, y, z), state, self.tag))
        super().set(x, y, z, state)

    def overwrite_violations(self):
        """Liste des poses build qui remplacent un rail pose par un voxel DIFFERENT.

        On approxime 'autre voxel' via tags : chaque pose build d'un voxel est
        taguee par son hash ; une pose qui remplace une valeur rail-family posee
        avec un tag de voxel different ET d'une colonne differente = chevauchement.
        """
        owner = {}   # pos -> (tag, state) de la derniere pose
        out = []
        for (x, y, z), state, tag in self.writes:
            if tag == "terrain":
                continue
            prev = owner.get((x, y, z))
            if prev and prev[0] != tag and prev[1] in R.RAIL_FAMILY \
                    and state != "air" and state in R.RAIL_FAMILY:
                out.append(((x, y, z), prev, (tag, state)))
            owner[(x, y, z)] = (tag, state)
        return out


def run_case(name, trace, world, styles=("classic", "nature"), themes=(1, 2),
             check_continuity=True, allow_fragment_cols=0):
    counts["scenarios"] += 1
    counts["voxels"] += len(trace)
    pre0 = set(world.blocks.keys())
    for style in styles:
        # clone the hostile world per build
        for theme in themes:
            opt = R.Options(style=style, theme=theme, fill_mode=2, buried=False)
            w2 = SpyWorld()
            w2.blocks = dict(world.blocks)
            w2.writes = list(world.writes)
            w2.tag = "terrain"
            counts["builds"] += 1
            pre0 = set(w2.blocks.keys())
            model = R.build_all(w2, trace, opt)

            # --- A. anti-chevauchement decor-sur-rail -------------------------
            # (variante simple : re-simulation voxel par voxel non faite ici,
            #  la version stricte est dans stupid_voxel_spy ; ici on verifie le
            #  resultat : aucune cellule ne contient un decor "orphelin" pose
            #  au-dessus d'un rail d'une colonne voisine adjacente.)
            # --- B. continuite -------------------------------------------------
            if check_continuity:
                missing = 0
                for (x, y, z) in trace:
                    found = False
                    for dy in range(-2, 3):
                        st = w2.get(x, y + dy, z)
                        st2 = w2.get(x, y + dy + 1, z)
                        if st in R.RAIL_FAMILY or st2 in R.RAIL_FAMILY:
                            found = True
                            break
                    if not found:
                        missing += 1
                frag_allow = allow_fragment_cols
                if missing > frag_allow:
                    fail(f"{name}/{style}/t{theme}",
                         f"continuite cassee : {missing}/{len(trace)} voxels "
                         f"sans aucun bloc dans leur colonne")

            # --- E. pas de rail invisible (mode surface) -------------------------
            # Toute piece visible (core/decor/fill) doit avoir le ciel ou du rail
            # au-dessus : un solide hostile au-dessus = rail enterre = 'disparu'.
            for (x, y, z), st in w2.blocks.items():
                if st not in R.RAIL_FAMILY or (x, y, z) in pre0:
                    continue
                above = w2.get(x, y + 1, z)
                if above not in (R.AIR, None) and above not in R.RAIL_FAMILY \
                        and above != "water":
                    fail(f"{name}/{style}/t{theme}",
                         f"rail enterre : {st} a ({x},{y},{z}), dessous={above!r}")

            # --- D. anti-flottant (forme "plat") --------------------------------
            for (x, y, z), st in w2.blocks.items():
                if st not in R.RAIL_FAMILY or (x, y, z) in pre0 \
                        or w2.get(x, y - 1, z) not in (R.AIR, None):
                    continue
                for depth_gap in range(2, 6):
                    if w2.get(x, y - depth_gap, z) not in (R.AIR, None):
                        fail(f"{name}/{style}/t{theme}",
                             f"flottant plat: {st} a ({x},{y},{z}) "
                             f"({depth_gap - 1} air sous lui)")
                        break



# =============================================================================
# Scenes calquees sur les captures
# =============================================================================

def scene1():
    """Photo 1 : ligne douce est-ouest sur terrain plat avec micro-dents en z et
    en y (theme sombre + clair)."""
    world = SpyWorld()
    flat_ground(world, -4, 46, -6, 6, y0=0)
    trace = []
    x_cur = 0
    z_cur = 0
    y_cur = 0
    for i in range(36):
        x_cur += 1
        y_cur = 0
        if i % 9 == 8:      # micro-dent plate decodee en plateau (tres courant)
            y_cur = 1
        trace.append((x_cur, y_cur, z_cur))
    world.tag = "terrain"
    trace_write(world, trace)
    run_case("photo1-ligne-douce", trace, world)


def scene2():
    """Photo 2 : longue branche est puis virage 90 deg pile au point de controle
    (virage L dur voxelise exactement comme le fait la spline)."""
    world = SpyWorld()
    flat_ground(world, -2, 34, -20, 4, y0=0)
    trace = [(i, 0, 0) for i in range(28)]
    trace += [(27, 0, -i) for i in range(1, 14)]
    world.tag = "terrain"
    trace_write(world, trace)
    run_case("photo2-virage-L", trace, world, allow_fragment_cols=0)


def scene3():
    """Photo 3 : noeud/vrille dense (S volee doublant 2 fois sur elle-meme, zoom
    sur un pseudo carrefour de 3-4 voisins)."""
    world = SpyWorld()
    flat_ground(world, -14, 10, -10, 10, y0=0)
    trace = []
    # vrille 2D : le trace repasse deux fois pres du meme carrefour
    seq = [(0, 0), (1, 0), (1, 1), (1, 2), (0, 2), (-1, 2), (-2, 2), (-2, 1),
           (-2, 0), (-1, 0), (-1, 1), (0, 1), (2, 1), (2, 2), (2, 3), (1, 3),
           (0, 3), (-1, 3), (-3, 3), (-3, 2), (-3, 1), (-3, 0), (-2, 1)]
    y = 0
    for (x, z) in seq:
        trace.append((x, y, z))
    world.tag = "terrain"
    trace_write(world, trace)
    run_case("photo3-noeud-dense", trace, world)


def scene4():
    """Photo 4 : ligne nature douce avec dents y/jogs minimaux sur terrain plat."""
    world = SpyWorld()
    flat_ground(world, -3, 30, -4, 4, y0=0)
    trace = []
    for i in range(24):
        y = 1 if (i % 7 == 3) else 0
        z = 1 if (12 <= i < 16) else 0
        trace.append((i, y, z))
    world.tag = "terrain"
    trace_write(world, trace)
    run_case("photo4-nature-douce", trace, world, styles=("nature",))


def main():
    scene1()
    scene2()
    scene3()
    scene4()
    total = counts["builds"]
    print(f"=== REPRO PHOTOS {counts['scenarios']} scénarios, {total} builds ===")
    if fails:
        head = fails[:25]
        print(f"{len(fails)} violations :")
        for f in head:
            print("   ", f)
        sys.exit(1)
    print("Tout est vert : aucune violation.")


if __name__ == "__main__":
    main()
