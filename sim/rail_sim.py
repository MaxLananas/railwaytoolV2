#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
rail_sim.py — Simulateur du système de rail BTE (réécriture "Railway Tools for Axiom").

Reproduit EXACTEMENT la logique des 4 scripts Lua du tuto BTE France, en version
déterministe (non dépendante de l'ordre de passage), telle qu'implémentée dans le mod :

  1. Tracé     : Catmull-Rom voxelisé 26-connexe (outil spline)
  2. Rectif    : nivelage vertical + épuration des coins en "L" (toutes orientations)
  3. Analyse   : classification N-S / E-W / Diagonale par géométrie pure
                 (+ overrides couleur : rouge=NS, bleu=EW, vert=DIAG)
  4. Build     : design "Classique" (murets/shelf/corail, comme le script Hamburger)
                 ou "Nature" (pupitres/pale moss/gravel, comme le script 4)

Usage :
  python3 rail_sim.py                 -> tous les scénarios
  python3 rail_sim.py s-curve         -> un seul scénario
  python3 rail_sim.py --style nature  -> force le design nature partout
  python3 rail_sim.py --no-color
"""

import sys
import math
import random

# =============================================================================
# 1. MONDE MINIMAL (dict (x,y,z) -> nom d'état de bloc)
# =============================================================================

class World:
    def __init__(self):
        self.blocks = {}

    def get(self, x, y, z):
        return self.blocks.get((x, y, z), "air")

    def set(self, x, y, z, state):
        if state == "air":
            self.blocks.pop((x, y, z), None)
        else:
            self.blocks[(x, y, z)] = state

    def positions(self):
        return self.blocks.keys()


WOOL = {f"{c}_wool" for c in (
    "white orange magenta light_blue yellow lime pink gray light_gray cyan "
    "purple blue brown green red black".split())}

GROUND = "grass_block"
AIR = "air"

def is_wool(state):
    return state in WOOL

# =============================================================================
# 2. TRACÉ : Catmull-Rom voxelisé
# =============================================================================

def catmull_rom_points(control, samples_per_block=4):
    """Retourne les positions flottantes échantillonnées le long de la spline."""
    if len(control) < 2:
        return list(control)
    pts = [(x + 0.5, y + 0.5, z + 0.5) for x, y, z in control]
    ext = [tuple(2 * pts[0][i] - pts[1][i] for i in range(3))] + pts
    ext.append(tuple(2 * pts[-1][i] - pts[-2][i] for i in range(3)))

    def cr(a, b, c, d, t):
        t2, t3 = t * t, t * t * t
        return 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2
                      + (-a + 3 * b - 3 * c + d) * t3)

    out = []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        seg_len = math.dist(p1, p2)
        steps = max(1, int(math.ceil(seg_len * samples_per_block)))
        for s in range(steps):
            t = s / steps
            out.append(tuple(cr(p0[k], p1[k], p2[k], p3[k], t) for k in range(3)))
    out.append(pts[-1])
    return out


def voxelize(float_points):
    """Convertit en voxels consécutifs 26-connexes (interpolation des trous)."""
    voxels = []
    for fx, fy, fz in float_points:
        v = (math.floor(fx), math.floor(fy), math.floor(fz))
        if not voxels:
            voxels.append(v)
            continue
        last = voxels[-1]
        if v == last:
            continue
        dx, dy, dz = v[0] - last[0], v[1] - last[1], v[2] - last[2]
        n = max(abs(dx), abs(dy), abs(dz))
        if n == 1:
            voxels.append(v)
        else:
            # Reconnexion : interpose les voxels intermédiaires.
            for k in range(1, n):
                t = k / n
                voxels.append((math.floor(last[0] + dx * t + 0.5),
                               math.floor(last[1] + dy * t + 0.5),
                               math.floor(last[2] + dz * t + 0.5)))
            voxels.append(v)
    # Déduplique en préservant l'ordre
    seen, ordered = set(), []
    for v in voxels:
        if v not in seen:
            seen.add(v)
            ordered.append(v)
    return ordered

# =============================================================================
# 3. RECTIF : nivelage + épuration des coins en "L"
# =============================================================================

# Les 14 motifs du script Lua (voisin "nord" dz=-1 / voisin latéral dx=+-1,
# avec les tolérances verticales), étendus aux 4 orientations (rotation).
def _l_patterns():
    base = []
    for dy1 in (0, 1, -1):
        for dy2 in (0, 1, -1):
            if (dy1, dy2) == (-1, 1):
                continue  # (0,-1,-1)+(±1,1,0) n'est pas dans le script
            base.append((dy1, dy2))
    # Le script n'a pas non plus (0,1,-1)+(±1,-1,0) ; on suit le tableau exact :
    exact = [(0, 0), (1, 0), (0, 1), (0, -1), (1, 1), (-1, -1), (-1, 0)]
    return exact

L_DY_PAIRS = _l_patterns()

def l_corners_here(world, x, y, z, spline):
    """Vrai si le bloc de spline en (x,y,z) forme un coin en 'L' (4 orientations)."""
    def is_spline(dx, dy, dz):
        return world.get(x + dx, y + dy, z + dz) == spline
    # Orientations : (nord, est), (nord, ouest), (sud, est), (sud, ouest)
    for d_vert in (0, 0, -1), (0, 0, 1):
        for d_hori in (1, 0, 0), (-1, 0, 0):
            for dy1, dy2 in L_DY_PAIRS:
                if is_spline(d_vert[0], dy1, d_vert[2]) and is_spline(d_hori[0], dy2, d_hori[2]):
                    return True
    return False


def diag_segment(world, x, y, z, spline):
    """Vrai si le voxel s'inscrit dans un segment diagonal regulier (spline aux
    deux extremites diagonales opposees, tolerance y) : ce n'est PAS un coin L
    a purger, c'est une diagonale fine que la purge script casserait."""
    for dx, dz in ((1, 1), (1, -1)):
        for dy1 in DY:
            if world.get(x + dx, y + dy1, z + dz) != spline:
                continue
            for dy2 in DY:
                if world.get(x - dx, y + dy2, z - dz) == spline:
                    return True
    return False


def locally_connected_without(world, x, y, z, spline):
    """Les voisins spline de (x,y,z) restent-ils mutuellement connectes (26-connexite,
    dans le cube 3x3x3) si ce voxel disparait ? Empeche la purge L d'ouvrir un trou
    dans une diagonale fine (rail manquant)."""
    neigh = []
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx == 0 and dy == 0 and dz == 0:
                    continue
                if world.get(x + dx, y + dy, z + dz) == spline:
                    neigh.append((x + dx, y + dy, z + dz))
    if len(neigh) <= 1:
        return True
    allowed = set(neigh)
    seen = {neigh[0]}
    stack = [neigh[0]]
    while stack:
        cx, cy, cz = stack.pop()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    p = (cx + dx, cy + dy, cz + dz)
                    if p in allowed and p not in seen:
                        seen.add(p)
                        stack.append(p)
    return len(seen) == len(neigh)


def _trace_connected(s):
    """True si l'ensemble de voxels est 26-connexe (un seul ilot)."""
    if not s:
        return False
    it = iter(s)
    seed = next(it)
    seen = {seed}
    stack = [seed]
    while stack:
        x, y, z = stack.pop()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    nb = (x + dx, y + dy, z + dz)
                    if nb in s and nb not in seen:
                        seen.add(nb)
                        stack.append(nb)
    return len(seen) == len(s)


def rectify_l(world, trace, spline, corner):
    """Supprime/remplace les coins en L. Retourne la trace épurée (ordre préservé).
    Triple garde anti-casse : (1) veto diagonale, (2) connexité locale 3^3,
    (3) connexité GLOBALE de la trace — un retrait en chaîne peut passer les
    deux premiers tout en sectionnant la voie (points d'articulation)."""
    removed = set()
    trace_set = set(trace)
    for (x, y, z) in trace:
        if world.get(x, y, z) != spline:
            continue
        if not l_corners_here(world, x, y, z, spline):
            continue
        if diag_segment(world, x, y, z, spline):
            continue
        if not locally_connected_without(world, x, y, z, spline):
            continue
        if not _trace_connected(trace_set - {(x, y, z)}):
            continue  # point d'articulation : le retrait couperait la voie
        isolated = (world.get(x + 1, y, z) == AIR and world.get(x - 1, y, z) == AIR
                    and world.get(x, y, z + 1) == AIR and world.get(x, y, z - 1) == AIR)
        # Un coin L ne devient un marqueur 'corner' (herbe) que POSÉ : s'il
        # flotte au-dessus du vide, il verrouillerait un voxel de laine au-
        # dessus de lui (is_unstable : below non-air => pas de descente) et
        # créerait le « monticule » des captures. En l'air -> AIR.
        below = world.get(x, y - 1, z)
        supported = below not in (AIR, None) and below != "water"
        if isolated or not supported:
            world.set(x, y, z, AIR)
        else:
            world.set(x, y, z, corner)
        removed.add((x, y, z))
        trace_set.discard((x, y, z))
    return [v for v in trace if v not in removed]


def _count_components(s):
    """Nombre de composantes 26-connexes de l'ensemble de voxels."""
    rest = set(s)
    n = 0
    while rest:
        n += 1
        seed = rest.pop()
        stack = [seed]
        seen = {seed}
        while stack:
            x, y, z = stack.pop()
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for dz in (-1, 0, 1):
                        nb = (x + dx, y + dy, z + dz)
                        if nb in rest and nb not in seen:
                            seen.add(nb)
                            stack.append(nb)
        rest -= seen
    return n


def dedupe_columns(world, trace, spline):
    """Aucun empilement vertical : une colonne (x,z) ne doit jamais porter 2
    voxels de laine à des hauteurs différentes. Les dents y±1 de la spline
    + la purge des L pouvaient figer une pile (corner herbe sous le wool
    du dessus) = le « monticule / rail au-dessus » des captures. On garde le
    voxel LE PLUS BAS de la colonne et on dégage les autres (AIR), niveau par
    niveau. Un retrait est accepté s'il ne sectionne pas davantage la voie
    (le nombre de composantes n'augmente pas — les piles parasites sont déjà
    des îlots, leur retrait ne fait que du bien). Seuls les blocs laine purs
    sont touchés : du rail déjà posé n'est jamais altéré."""
    by_col = {}
    for v in dict.fromkeys(trace):
        by_col.setdefault((v[0], v[2]), []).append(v)
    cur = list(dict.fromkeys(trace))
    for vals in by_col.values():
        ys = sorted({v[1] for v in vals})
        if len(ys) < 2:
            continue
        for yy in ys[1:][::-1]:                # retirer du haut vers le bas
            victims = [v for v in vals if v[1] == yy and v in cur]
            if not victims:
                continue
            if any(world.get(v[0], v[1], v[2]) not in (spline, AIR)
                   for v in victims):
                continue                        # rail/terrain existant : on ne touche pas
            trial = [v for v in cur if v not in victims]
            if _count_components(trial) <= _count_components(cur):
                for v in victims:
                    world.set(v[0], v[1], v[2], AIR)
                cur = trial
    return cur


def _flatten_teeth_impl(world, trace, spline):
    """Aplanit les dents de scie verticales : pics unitaires ET plateaux courts
    (<= 3 voxels) décalés d'1 bloc entre deux segments au meme niveau
    (a.y == c.y != run.y, |run.y - a.y| == 1). Chaque voxel du plateau est
    realigné si sa case cible est libre (ni laine ni rail). Leve les
    collisions de colonnes laterales et les fragments de voie « volants »
    visibles en jeu sur terrain plat quand la voxelisation oscille a y±1."""
    if len(trace) < 3:
        return trace
    out = list(trace)
    n = len(out)
    i = 1
    while i < n - 1:
        ay = out[i - 1][1]
        by = out[i][1]
        if by == ay or abs(by - ay) != 1:
            i += 1
            continue
        j = i
        while j < n and out[j][1] == by:
            j += 1
        run_len = j - i
        ends_ok = j < n and out[j][1] == ay
        if ends_ok and run_len <= 3:
            ok = True
            for k in range(i, j):
                x0, _, z0 = out[k]
                t = world.get(x0, ay, z0)
                if t == spline or t in RAIL_FAMILY:
                    ok = False
                    break
            if ok:
                for k in range(i, j):
                    x0, _, z0 = out[k]
                    world.set(x0, by, z0, AIR)
                for k in range(i, j):
                    x0, _, z0 = out[k]
                    world.set(x0, ay, z0, spline)
                    out[k] = (x0, ay, z0)
        i = j
    return out


def flatten_teeth(world, trace, spline):
    """Aplanissement complet : dents de scie, puis dédoublonnage vertical des
    colonnes (anti-monticule). Point d'entrée unique utilisé par tous les
    pipelines avant la construction."""
    return dedupe_columns(world, _flatten_teeth_impl(world, trace, spline), spline)


def is_unstable(world, x, y, z):
    """Reprise exacte de la fonction is_unstable du script de rectification."""
    below_is_air = world.get(x, y - 1, z) == AIR
    bn = world.get(x, y - 1, z - 1) == AIR
    bs = world.get(x, y - 1, z + 1) == AIR
    be = world.get(x + 1, y - 1, z) == AIR
    bo = world.get(x - 1, y - 1, z) == AIR
    if below_is_air and bn and bs and be and bo:
        return True

    n = world.get(x, y, z - 1) == AIR
    s = world.get(x, y, z + 1) == AIR
    e = world.get(x + 1, y, z) == AIR
    o = world.get(x - 1, y, z) == AIR
    if sum((n, s, e, o)) >= 3:
        return True
    if (n and s) or (e and o):
        return True

    ne = world.get(x + 1, y, z - 1) == AIR
    so = world.get(x - 1, y, z + 1) == AIR
    no = world.get(x - 1, y, z - 1) == AIR
    se = world.get(x + 1, y, z + 1) == AIR
    if (ne and so) or (no and se):
        return True
    return sum((ne, so, no, se)) >= 3


def rectify_vertical(world, trace, spline, corner, max_up=15, max_down=20, dug=None):
    """Remonte les laines enterrées, redescend celles en l'air (comme le script 1).
    Amélioration du mod : si rectif pleine hauteur impossible, le bloc au-dessus de
    la laine est une crête de 1-2 blocs non-rail : on la creuse (tunnel) plutôt que
    de faire sauter le rail d'un cran (dug collecte les positions à purger en AIR)."""
    moved_trace = []
    for (x, y, z) in trace:
        if world.get(x, y, z) != spline:
            moved_trace.append((x, y, z))
            continue
        # Remontée : bloc au-dessus non-air -> crête 1-2 creusée, sinon air jusqu'à +15.
        # Cas particulier : case au-dessus = AUTRE laine de la trace (doublon
        # vertical des dents de spline) — jamais de remontée dedans (piles),
        # mais la descente reste autorisée : c'est elle qui dégonfle la pile.
        if world.get(x, y + 1, z) != AIR and world.get(x, y + 1, z) != spline:
            dug_here = False
            if dug is not None:
                to_dig = []
                ok = True
                # le tunnel ne perce qu'UNE crête : 2 blocs grand max par
                # colonne, tous passages confondus — sinon on tailleade le
                # terrain (invariant stupid_stress « colonne creusée > 2 »).
                if sum(1 for (dx2, _, dz2) in dug if dx2 == x and dz2 == z) >= 2:
                    ok = False
                for dy in (1, 2):
                    st = world.get(x, y + dy, z)
                    if st == AIR:
                        break
                    if st in RAIL_FAMILY or st == spline or is_wool(st):
                        ok = False
                        break
                    to_dig.append((x, y + dy, z))
                if ok and to_dig:
                    above = world.get(x, y + len(to_dig) + 1, z)
                    if above != AIR:
                        ok = False
                if ok and to_dig:
                    for pos in to_dig:
                        world.set(pos[0], pos[1], pos[2], AIR)
                        dug.add(pos)
                    dug_here = True
            if dug_here:
                moved_trace.append((x, y, z))
                continue
            for dy in range(1, max_up + 1):
                if world.get(x, y + dy, z) == AIR:
                    landy = y + dy - 1
                    if world.get(x, landy, z) == spline:
                        break  # jamais d'atterrissage SUR une autre laine
                    # corner laissé derrière : seulement posé, sinon une herbe
                    # flottante verrouille la descente d'un voisin (monticule).
                    below = world.get(x, y - 1, z)
                    world.set(x, y, z,
                              corner if below not in (AIR, None) and below != "water"
                              else AIR)
                    world.set(x, landy, z, spline)
                    y = landy
                    moved_trace.append((x, y, z))
                    break
            else:
                moved_trace.append((x, y, z))
            continue
        # Descente
        if is_unstable(world, x, y, z):
            target = y
            for _ in range(max_down):
                nxt = target - 1
                if not is_unstable(world, x, target, z):
                    break
                if world.get(x, nxt, z) == spline:
                    break
                target = nxt
            if target != y:
                world.set(x, y, z, AIR)
                world.set(x, target, z, spline)
                y = target
        moved_trace.append((x, y, z))
    return moved_trace

# =============================================================================
# 4. ANALYSE : classification géométrique N-S / E-W / DIAG (+ overrides couleur)
# =============================================================================

NS, EW, DIAG = "NS", "EW", "DIAG"
DY = (0, 1, -1)  # ordre de tolérance verticale des scripts

COLOR_OVERRIDE = {"red_wool": NS, "blue_wool": EW, "lime_wool": DIAG}

class TrackModel:
    """Modèle d'analyse : trace (laine) + types effectifs."""

    def __init__(self, world, trace_voxels):
        self.world = world
        self.trace = set(trace_voxels)
        self.types = {v: self._classify(v) for v in trace_voxels}

    def is_trace(self, x, y, z):
        # Requête "est-ce un voxel de la trace courante ?" : on lit le SET de
        # trace, PAS le monde live — sinon pendant le build, après qu'un voxel
        # a été remplacé (coral/lectern/grass), ses voisins diagonaux
        # disparaissent et la littérature des coins voisinnants change à la
        # volée (c'est le bug des leaf_2 avec derive X ou Z).
        return (x, y, z) in self.trace

    def type_at(self, x, y, z):
        """Type effectif du voxel (None si pas de trace, ou bloc indice corail)."""
        v = (x, y, z)
        if v in self.types:
            return self.types[v]
        st = self.world.get(x, y, z)
        if st == "coral_south":
            return NS
        if st == "coral_east":
            return EW
        return None

    def type_near(self, x, y, z):
        """Premier type trouvé avec tolérance dy {0,1,-1} (ordre du script)."""
        for dy in DY:
            t = self.type_at(x, y + dy, z)
            if t is not None:
                return t
        return None

    def _neighbors_of(self, x, y, z):
        """Ensemble des directions (dx,dz) où la trace continue (tolérance ±1y)."""
        out = set()
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx == 0 and dz == 0:
                    continue
                for dy in DY:
                    if self.is_trace(x + dx, y + dy, z + dz):
                        out.add((dx, dz))
                        break
        return out

    def _classify(self, voxel):
        x, y, z = voxel
        st = self.world.get(x, y, z)
        if st in COLOR_OVERRIDE:
            return COLOR_OVERRIDE[st]
        # Rail déjà construit : l'indice (corail/pupitre) est posé à y ou y+1
        # après la construction (le voxel de trace est devenu le sol de la colonne).
        for dy in (0, 1):
            above = self.world.get(x, y + dy, z)
            if above == "coral_south" or above == "lectern_north":
                return NS
            if above == "coral_east" or above == "lectern_east":
                return EW
        v = self._neighbors_of(x, y, z)
        n, s = (0, -1) in v, (0, 1) in v
        e, o = (1, 0) in v, (-1, 0) in v
        ne, no = (1, -1) in v, (-1, -1) in v
        se, so = (1, 1) in v, (-1, 1) in v
        if (n and s) or (n and se) or (n and so) or (s and ne) or (s and no):
            return NS
        if (e and o) or (e and no) or (e and so) or (o and ne) or (o and se):
            return EW
        if (ne and so) or (no and se):
            return DIAG
        # Fallbacks (trace non rectifiée / motifs denses)
        if n or s:
            return NS
        if e or o:
            return EW
        if v:
            return DIAG
        return NS  # bloc isolé


# =============================================================================
# 5. AGENTS (scans directionnels le long de la trace — portage fidèle des scripts)
# =============================================================================

MAX_LINE_SCAN = 20
MAX_DIAG_SCAN = 64  # le script boucle à l'infini ; cap de sécurité

class Agent:
    def __init__(self):
        self.dist = 0
        self.exit_dir = "none"  # "east"/"west"/"south"/"north"
        self.exit_len = 1       # 2 = ça continue après le décalage, 1 = virage net
        self.exit_type = None   # pour les diagonales : NS/EW/None


def scan_straight(model, x, y, z, want_type, step, side1, side1_name, side2, side2_name):
    """Scan axial (ligne NS ou EW). step = direction de marche ; side = test de virage."""
    agent = Agent()
    cur_y = y
    for i in range(1, MAX_LINE_SCAN + 1):
        nx, nz = x + step[0] * i, z + step[1] * i
        found = False
        for dy in DY:
            if model.type_at(nx, cur_y + dy, nz) == want_type:
                agent.dist += 1
                cur_y += dy
                found = True
                break
        if found:
            continue
        for (sdx, sdz), name in ((side1, side1_name), (side2, side2_name)):
            for dy in DY:
                if model.type_at(nx + sdx, cur_y + dy, nz + sdz) == want_type:
                    agent.exit_dir = name
                    vy = cur_y + dy
                    cont = any(model.type_at(nx + sdx + step[0], vy + dy2, nz + sdz + step[1]) == want_type
                               for dy2 in DY)
                    agent.exit_len = 2 if cont else 1
                    return agent
        break
    return agent


OPPOSITE_NS = {"east": "side_west", "west": "side_east"}
OPPOSITE_EW = {"south": "side_north", "north": "side_south"}


def decide_side_ns(agent_nord, agent_sud):
    """Décision 'muret vs side-block' pour une ligne N-S (logique exacte du script 3)."""
    length = agent_nord.dist + agent_sud.dist + 1
    pos = agent_sud.dist + 1  # position 1-based depuis le sud
    if length <= 1:
        return "wall_ns"
    if length == 2:
        if pos == 1:
            return OPPOSITE_NS.get(agent_sud.exit_dir, "wall_ns")
        if agent_nord.exit_len == 1:
            return OPPOSITE_NS.get(agent_nord.exit_dir, "wall_ns")
        return "wall_ns"
    if length <= 40:
        q, r = divmod(length, 3)
        if pos <= q:
            return OPPOSITE_NS.get(agent_sud.exit_dir, "wall_ns")
        if pos > 2 * q + r:
            return OPPOSITE_NS.get(agent_nord.exit_dir, "wall_ns")
    return "wall_ns"


def decide_side_ew(agent_ouest, agent_est):
    length = agent_ouest.dist + agent_est.dist + 1
    pos = agent_est.dist + 1  # position 1-based depuis l'est
    if length <= 1:
        return "wall_eo"
    if length == 2:
        if pos == 1:
            return OPPOSITE_EW.get(agent_est.exit_dir, "wall_eo")
        if agent_ouest.exit_len == 1:
            return OPPOSITE_EW.get(agent_ouest.exit_dir, "wall_eo")
        return "wall_eo"
    if length <= 40:
        q, r = divmod(length, 3)
        if pos <= q:
            return OPPOSITE_EW.get(agent_est.exit_dir, "wall_eo")
        if pos > 2 * q + r:
            return OPPOSITE_EW.get(agent_ouest.exit_dir, "wall_eo")
    return "wall_eo"


def diag_sense(model, x, y, z):
    """'swne' ou 'senw' — même priorités que le script (diag continue > type axial > défaut)."""
    if model.type_near(x + 1, y, z - 1) == DIAG or model.type_near(x - 1, y, z + 1) == DIAG:
        return "swne"
    t_ne = model.type_at(x + 1, y, z - 1)  # y exact
    t_so = model.type_at(x - 1, y, z + 1)
    if t_ne in (NS, EW) or t_so in (NS, EW):
        return "swne"
    return "senw"


def scan_diag(model, x, y, z, sdx, sdz):
    """Scan le long d'une diagonale ; à la fin détecte le type de la connexion."""
    agent = Agent()
    cur_y = y
    for i in range(1, MAX_DIAG_SCAN + 1):
        nx, nz = x + sdx * i, z + sdz * i
        found = False
        for dy in DY:
            if model.type_at(nx, cur_y + dy, nz) == DIAG:
                agent.dist += 1
                cur_y += dy
                found = True
                break
        if found:
            continue
        ext = None
        for dy in DY:
            t = model.type_at(nx, cur_y + dy, nz)
            if t == NS:
                ext = NS
                break
            if t == EW:
                ext = EW
                break
        agent.exit_type = ext
        break
    return agent


def diag_design(model, x, y, z):
    """Décide du rendu d'un bloc DIAG. Retourne (coral, liste[(dx,dz,state)])."""
    sense = diag_sense(model, x, y, z)
    swne = sense == "swne"
    # Vecteurs de marche vers le "nord" (z-) et le "sud" (z+) de la diagonale
    if swne:
        n = scan_diag(model, x, y, z, +1, -1)
        s = scan_diag(model, x, y, z, -1, +1)
    else:
        n = scan_diag(model, x, y, z, -1, -1)
        s = scan_diag(model, x, y, z, +1, +1)
    w1, w2 = ("wall_nw", "wall_se") if swne else ("wall_sw", "wall_ne")

    four = [(-1, 0, w1), (1, 0, w2), (0, -1, w1), (0, 1, w2)]
    false_diag = (n.exit_type is not None and n.exit_type == s.exit_type)

    if false_diag:
        coral = "coral_south" if n.exit_type == NS else "coral_east"
        return coral, four

    length = n.dist + s.dist + 1
    mid = length // 2
    rel = s.dist + 1
    if length == 1:
        coral, transition = "coral_south", True
    else:
        ext = s.exit_type if rel <= mid else n.exit_type
        coral = {NS: "coral_south", EW: "coral_east"}.get(ext)
        transition = (rel == mid) or (rel == mid + 1)

    if coral is None:
        # indetermine (exutoire des deux cotes nul) : fragment de transition NS
        # plutot que le black_wool signal — le rail reste continu en jeu.
        return "coral_south", four
    if transition:
        return coral, four
    if coral == "coral_south":
        return coral, [(-1, 0, w1), (1, 0, w2)]
    return coral, [(0, -1, w1), (0, 1, w2)]

# =============================================================================
# 6. CONSTRUCTION
# =============================================================================

PROTECTED = {"wall_ns", "wall_eo", "wall_ne", "wall_nw", "wall_se", "wall_sw",
             "side_east", "side_west", "side_north", "side_south",
             "coral_south", "coral_east",
             "lectern_north", "lectern_east", "pale_moss_carpet", "pale_moss_block",
             "button_north", "button_east",
             "door_lower", "door_upper", "gravel"} | {f"door_{h}_{f}" for h in ("lower", "upper")
                                            for f in ("north", "south", "east", "west")} | {f"leaf_{a}_{f}"
                                            for a in (1, 2, 3, 4)
                                            for f in ("north", "south", "east", "west")}

DOOR_FACING = {"side_north": "door_north", "side_south": "door_south",
               "side_east": "door_east", "side_west": "door_west"}

random.seed(42)
SOIL_MIX = [("deepslate", 45), ("cobbled_deepslate", 40), ("pale_oak_wood", 10),
            ("deepslate_iron_ore", 4), ("deepslate_coal_ore", 2)]
_total = sum(p for _, p in SOIL_MIX)
SOIL_MIX = [(b, p / _total) for b, p in SOIL_MIX]

class Options:
    def __init__(self, style="classic", theme=1, fill_mode=2, buried=False):
        self.style = style      # "classic" | "nature"
        self.theme = theme      # 1 = sombre, 2 = clair
        self.fill_mode = fill_mode  # 1 = uni (orange_wool), 2 = aléatoire
        self.base_dy = -1 if buried else 0

def pick_soil(opt):
    if opt.fill_mode == 1:
        return "orange_wool"
    n = random.random()
    acc = 0.0
    for block, p in SOIL_MIX:
        acc += p
        if n <= acc:
            return block
    return SOIL_MIX[-1][0]


def build_column(world, opt, x, y, z, center):
    """Pose sol/bloc/air comme le script (ne réécrit jamais un rail existant).
    Protection renforcée sur les 3 niveaux de la colonne : aucun bloc de rail
    existant n'est jamais enfoncé (rebuild idempotent).
    Thème clair : le side-block est un PANNEAU de porte basse (moitié lower,
    y+1 seulement), fidèle au script — pas de porte complète à 2 blocs."""
    start_y = y + opt.base_dy
    for yy in (start_y, start_y + 1, start_y + 2):
        if world.get(x, yy, z) in PROTECTED:
            return
    if opt.theme == 2 and center in DOOR_FACING:
        f = DOOR_FACING[center].split("_", 1)[1]
        world.set(x, start_y + 2, z, AIR)
        world.set(x, start_y + 1, z, f"door_lower_{f}")
    else:
        world.set(x, start_y + 2, z, AIR)
        world.set(x, start_y + 1, z, center)
    world.set(x, start_y, z, pick_soil(opt))


LEAF_FACING = {(1, -1): "east", (-1, -1): "south", (1, 1): "north", (-1, 1): "west"}

RAIL_FAMILY = {"wall_ns", "wall_eo", "wall_ne", "wall_nw", "wall_se", "wall_sw",
               "side_east", "side_west", "side_north", "side_south",
               "coral_south", "coral_east", "gravel",
               "lectern_north", "lectern_east", "pale_moss_carpet", "pale_moss_block",
               "button_north", "button_east",
               "door_lower", "door_upper",
               } | {f"door_{h}_{f}" for h in ("lower", "upper")
                    for f in ("north", "south", "east", "west")} | {f"leaf_{a}_{f}" for a in (1, 2, 3, 4)
                                              for f in ("north", "south", "east", "west")}

# Positions de blocs de rail présents AVANT le build courant (protection dédiée nature).
rail_before = set()


def nature_decor_set(world, x, y, z, state):
    """Pose de décor visible : si la cellule dessus est un solide hostile
    (pas du rail/air/eau), le décor serait enterré -> suppression (le cas
    « il manque le rail » de la photo 2 — litière/ortho sous herbe)."""
    above = world.get(x, y + 1, z)
    if above not in (AIR, None) and above not in RAIL_FAMILY and above != "water":
        return
    nature_set(world, x, y, z, state)


def nature_set(world, x, y, z, state):
    """Comme world.set mais n'écrase jamais un bloc de rail :
    - rail pré-existant au build (protection classique) ;
    - rail posé PENDANT ce build par un autre voxel — le core/la litière/les
      murs gagnent ; seul le gravier (lit passif) peut être amélioré ensuite.
    C'est le fix anti-chevauchement des capsules : un décor n'enfonce plus
    jamais le rail d'un voisin (traverses, nœuds, micro-drifts)."""
    cur = world.get(x, y, z)
    if (x, y, z) in rail_before and cur in RAIL_FAMILY:
        return
    if cur == state:
        return
    if cur in RAIL_FAMILY and cur != "gravel":
        return
    world.set(x, y, z, state)

def ordered_neighbors(model, x, y, z):
    """Noms de direction des voisins de trace, dans l'ordre de collecte du script."""
    out = []
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            if dx == 0 and dz == 0:
                continue
            for dy in DY:
                if model.is_trace(x + dx, y + dy, z + dz):
                    name = ""
                    if dz == -1:
                        name = "N"
                    elif dz == 1:
                        name = "S"
                    if dx == 1:
                        name += "E"
                    elif dx == -1:
                        name += "O"
                    out.append(name)
                    break
    return out


def pm(d1, d2, a, b):
    return (d1 == a and d2 == b) or (d1 == b and d2 == a)


LEAF_NS = [
    (("N", "S"), (2, "north", 2, "south")),
    (("N", "SE"), (3, "south", 2, "south")),
    (("N", "SO"), (2, "north", 3, "east")),
    (("S", "NE"), (3, "west", 2, "south")),
    (("S", "NO"), (2, "north", 3, "north")),
    (("NE", "SO"), (3, "west", 3, "east")),
    (("NO", "SE"), (3, "south", 3, "north")),
]
LEAF_EW = [
    (("O", "E"), (2, "west", 2, "east")),
    (("E", "NO"), (3, "south", 2, "east")),
    (("O", "NE"), (3, "east", 2, "east")),
    (("O", "SE"), (2, "west", 3, "north")),
    (("E", "SO"), (2, "west", 3, "west")),
    (("NE", "SO"), (3, "east", 3, "west")),
    (("NO", "SE"), (3, "south", 3, "north")),
]

_CARD_NS = ({"N", "NE", "NO"}, {"S", "SE", "SO"})
_CARD_HO = ({"E", "NE", "SE"}, {"O", "NO", "SO"})


def leaf_pair(table, d1, d2):
    """Valeurs (a1, f1, a2, f2) pour une paire de voisins (script Rouquinator exact).
    Pour les paires jamais couvertes par le script (virages durs, traces dégénérées) :
    extension cohérente — litière à 3 segments orientée vers le creux du virage."""
    for pair, val in table:
        if pm(d1, d2, *pair):
            return val
    pair = {d1, d2}
    n_dom = bool(pair & _CARD_NS[0])
    s_dom = bool(pair & _CARD_NS[1])
    f_1 = "south" if (n_dom and not s_dom) else "north"
    e_dom = bool(pair & _CARD_HO[0])
    o_dom = bool(pair & _CARD_HO[1])
    if e_dom and not o_dom:
        f_2 = "east"
    elif o_dom and not e_dom:
        f_2 = "west"
    else:
        f_2 = f_1
    return (3, f_1, 3, f_2)


def build_nature_block(model, world, opt, x, y, z, t):
    """Place le rail 'Nature' pour un voxel NS ou EW (script 4 porté)."""
    if t == NS:
        moss_offsets = [(0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)]
        facing = "north"
        table = LEAF_NS
        ortho = [(1, 0), (-1, 0)]          # gravier à l'est/ouest
        cross_quads = [(1, -1), (-1, -1), (1, 1), (-1, 1)]
        leaf_pos = [(1, 0), (-1, 0)]
    else:
        moss_offsets = [(1, 0), (-1, 0), (1, 1), (-1, 1), (1, -1), (-1, -1)]
        facing = "east"
        table = LEAF_EW
        ortho = [(0, 1), (0, -1)]
        cross_quads = []
        leaf_pos = [(0, -1), (0, 1)]

    is_moss = any(model.type_at(x + dx, y - 1, z + dz) == t for dx, dz in moss_offsets)
    if not is_moss:
        nature_set(world, x, y, z, f"lectern_{facing}")
        nature_decor_set(world, x, y + 1, z, "pale_moss_carpet")
    else:
        nature_set(world, x, y, z, "pale_moss_block")
        nature_decor_set(world, x, y + 1, z, f"button_{facing}")

    for dx, dz in ortho:
        nature_decor_set(world, x + dx, y, z + dz, "gravel")

    # Intersections rouge/bleu (design nature, lignes NS uniquement)
    if t == NS:
        for dx, dz in cross_quads:
            for dy in (0, 1, -1):
                if model.type_at(x + dx, y + dy, z + dz) == EW:
                    nature_decor_set(world, x, y + dy, z + dz, "gravel")
                    nature_decor_set(world, x, y + dy + 1, z + dz, f"leaf_3_{LEAF_FACING[(dx, dz)]}")

    nb = ordered_neighbors(model, x, y, z)
    d1 = nb[0] if len(nb) > 0 else ""
    d2 = nb[1] if len(nb) > 1 else ""
    if len(nb) >= 2:
        # Dérive en coin (le tronçon glisse d'1 en X ou Z) : la paire droite
        # (N,S)/(E,O) est un passage tout droit qui masque le voisin diagonal —
        # on le substitue pour rendre la litière à 3 segments du script.
        diag_nb = [d for d in nb if d in ("NE", "NO", "SE", "SO")]
        if diag_nb and ({d1, d2} == {"N", "S"} or {d1, d2} == {"E", "O"}):
            d2 = diag_nb[0]
        a1, f1, a2, f2 = leaf_pair(table, d1, d2)
    else:
        a1, f1, a2, f2 = (2, "north", 2, "south") if t == NS else (2, "west", 2, "east")
    (dx1, dz1), (dx2, dz2) = leaf_pos
    nature_decor_set(world, x + dx1, y + 1, z + dz1, f"leaf_{a1}_{f1}")
    nature_decor_set(world, x + dx2, y + 1, z + dz2, f"leaf_{a2}_{f2}")


def orphan_diag_conversion(model, x, y, z):
    """Un voxel DIAG sans aucun voxel de TRACE en diagonale (fragment perpendiculaire
    isolé, ex trace qui 'saute' d'une ligne) rend un vilain 4-murets corail de
    l'autre sens. On le convertit vers le type axial dominant (défaut NS).
    Critère géométrique (trace brute, pas les types) : stable au rebuild — les
    indices corail du 1er build ne peuvent pas reclassifier un voisin et créer
    du churn (idempotence préservée)."""
    nb = model._neighbors_of(x, y, z)
    for dx, dz in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        if (dx, dz) in nb:
            return None
    has_ns = model.type_near(x, y, z - 1) == NS or model.type_near(x, y, z + 1) == NS
    has_ew = model.type_near(x + 1, y, z) == EW or model.type_near(x - 1, y, z) == EW
    if has_ew and not has_ns:
        return EW
    return NS


def support_fill(world, opt, pre_keys, depth_max=4):
    """Comble sous les blocs de rail qui flottent (descente bloquee, dents
    residuelles, derive de controle) : aucun bloc pose par ce build ne garde
    de l'air directement sous lui — y compris un bloc survive d'une
    passe precedente dont le support a ete recreuse depuis. Le script pose
    toujours la voie sur le sol — c'est le meme contrat, jusqu'a depth_max blocs de remplissage
    (petits residuels de 1-4 blocs ; une ligne volontairement haute est un
    pont legitime, pas une dent)."""
    placed = [(pos, st) for pos, st in world.blocks.items()
              if st in RAIL_FAMILY]
    for (x, y, z), st in placed:
        # Mesure d'abord le vrai trou sous le bloc : s'il est plus profond que
        # depth_max, c'est un pont legitime — un remplissage tronque laisserait
        # lui-meme 1-4 blocs d'air et referait le bug. Sinon on comble tout.
        gap = 0
        while world.get(x, y - 1 - gap, z) in (AIR, None) and gap < 64:
            gap += 1
        if gap == 0 or gap > depth_max:
            continue
        soil = "gravel" if opt.style == "nature" else pick_soil(opt)
        for yy in range(y - 1, y - 1 - gap, -1):
            world.set(x, yy, z, soil)


def build_all(world, trace, opt):
    global rail_before
    rail_before = {pos for pos, st in world.blocks.items() if st in RAIL_FAMILY}
    pre_keys = set(world.blocks.keys())
    model = TrackModel(world, trace)

    # Design classic en DEUX passes : tous les cores (corails) d'abord, puis
    # tous les décors latéraux (murets/panneaux). Sinon, le côté d'un voisin
    # s'écrit dans la case du core AVANT lui et la garde « colonne intacte »
    # fait perdre le corail = « presque plus de rail » sur les lignes à
    # dérive (jogs latéraux rapprochés). Passes séparées : le core gagne
    # toujours sa case ; le décor, émis ensuite, s'efface devant tout rail
    # déjà présent (build_column protégé par cellule).
    classic_centers = []
    classic_sides = []

    for v in [v for v in trace if model.types.get(v) == DIAG]:
        conv = orphan_diag_conversion(model, *v)
        if conv is not None:
            model.types[v] = conv
    for (x, y, z) in [v for v in trace if model.types.get(v) == DIAG]:
        if opt.style == "classic":
            coral, sides = diag_design(model, x, y, z)
            if coral is None:
                classic_centers.append((x, y, z, "black_wool"))
                continue
            classic_centers.append((x, y, z, coral))
            for dx, dz, w in sides:
                classic_sides.append((x + dx, y, z + dz, w))
        else:
            # Design nature : diagonale convertie selon son extrémité (amélioration)
            c, _ = diag_design(model, x, y, z)
            t = NS if c != "coral_east" else EW
            build_nature_block(model, world, opt, x, y, z, t)
    for (x, y, z) in [v for v in trace if model.types.get(v) == NS]:
        if opt.style == "classic":
            n = scan_straight(model, x, y, z, NS, (0, -1), (1, 0), "east", (-1, 0), "west")
            s = scan_straight(model, x, y, z, NS, (0, +1), (1, 0), "east", (-1, 0), "west")
            side = decide_side_ns(n, s)
            classic_centers.append((x, y, z, "coral_south"))
            classic_sides.append((x - 1, y, z, side))
            classic_sides.append((x + 1, y, z, side))
        else:
            build_nature_block(model, world, opt, x, y, z, NS)
    for (x, y, z) in [v for v in trace if model.types.get(v) == EW]:
        if opt.style == "classic":
            o = scan_straight(model, x, y, z, EW, (-1, 0), (0, +1), "south", (0, -1), "north")
            e = scan_straight(model, x, y, z, EW, (+1, 0), (0, +1), "south", (0, -1), "north")
            side = decide_side_ew(o, e)
            classic_centers.append((x, y, z, "coral_east"))
            classic_sides.append((x, y, z - 1, side))
            classic_sides.append((x, y, z + 1, side))
        else:
            build_nature_block(model, world, opt, x, y, z, EW)

    for (x, y, z, c) in classic_centers:
        build_column(world, opt, x, y, z, c)
    for (x, y, z, w) in classic_sides:
        build_column(world, opt, x, y, z, w)
    support_fill(world, opt, pre_keys)
    return model

# =============================================================================
# 7. RENDU ASCII
# =============================================================================

USE_COLOR = "--no-color" not in sys.argv

def ansi(code):
    return lambda s: f"\033[{code}m{s}\033[0m" if USE_COLOR else s

BLUE, MAG, YEL, GRN, GRY, RED, ORA, WHT = (ansi(c) for c in
    ("34", "35;1", "33", "32", "90", "31;1", "38;5;208", "37;1"))

GLYPHS = {
    "wall_ns": BLUE("│"), "wall_eo": BLUE("─"),
    "wall_ne": BLUE("└"), "wall_nw": BLUE("┘"), "wall_se": BLUE("┌"), "wall_sw": BLUE("┐"),
    "side_north": YEL("n"), "side_south": YEL("s"), "side_east": YEL("e"), "side_west": YEL("w"),
    "coral_south": MAG("S"), "coral_east": MAG("E"),
    "black_wool": RED("X"),
    "white_wool": WHT("W"), "red_wool": WHT("R"), "blue_wool": WHT("B"), "lime_wool": WHT("V"),
    "orange_wool": ORA("o"),
    "deepslate": GRY("░"), "cobbled_deepslate": GRY("▒"), "pale_oak_wood": GRY("▓"),
    "deepslate_iron_ore": GRY("i"), "deepslate_coal_ore": GRY("c"),
    "gravel": ORA("g"),
    "lectern_north": ORA("N"), "lectern_east": ORA("E"),
    "pale_moss_block": GRN("M"), "pale_moss_carpet": GRN("~"),
    "button_north": GRN("+"), "button_east": GRN("+"),
    "grass_block": " ",
}
for a in "1234":
    for f in ("north", "south", "east", "west"):
        GLYPHS[f"leaf_{a}_{f}"] = GRN("'" if a == "2" else '"')


def topmost_glyph(world, x, z, min_y=-64, max_y=64):
    for y in range(max_y, min_y - 1, -1):
        st = world.get(x, y, z)
        if st != AIR:
            return GLYPHS.get(st, "?")
    return " "


def layer_glyph(world, x, y, z):
    st = world.get(x, y, z)
    if st == AIR or st == GROUND:
        return " "
    return GLYPHS.get(st, "?")


def render(world, trace_voxels, title, subtitle=""):
    xs = [p[0] for p in world.positions()]
    zs = [p[2] for p in world.positions()]
    if not xs:
        return
    x0, x1, z0, z1 = min(xs) - 1, max(xs) + 1, min(zs) - 1, max(zs) + 1

    # Couches contenant du rail (hors terrain) présentes dans la bbox
    layers = {}
    for (x, y, z), st in world.blocks.items():
        if st not in (GROUND, AIR):
            layers.setdefault(y, []).append((x, z))
    layers = {y: pts for y, pts in layers.items()
              if any(x0 <= x <= x1 and z0 <= z <= z1 for x, z in pts)}

    print(f"\n=== {title} ===")
    if subtitle:
        print(subtitle)

    if "--layers" in sys.argv or style_nature_requested():
        for y in sorted(layers):
            print(f"-- couche y={y} --")
            for z in range(z0, z1 + 1):
                print("".join(layer_glyph(world, x, y, z) for x in range(x0, x1 + 1)))
    else:
        print("(vue du dessus ; ajouter --layers pour le détail par couche)")
        for z in range(z0, z1 + 1):
            print("".join(topmost_glyph(world, x, z) for x in range(x0, x1 + 1)))


def style_nature_requested():
    return "--style" in sys.argv and "nature" in sys.argv


LEGEND = """
Légende (design Classique) :
  S/E  corail dead_bubble_coral_wall_fan facing south/east (centre de rail N-S ou E-O)
  │ ─  muret axial (mud_brick_wall / andesite_wall, connexions tall)
  └ ┘ ┌ ┐  murets d'angle (diagonales et faux-diagonales)
  n/s/e/w  side-block orienté (spruce_shelf thème sombre / iron_door thème clair)
  ░▒▓ic  remplissage du sol  ·  X = indéterminé (black_wool, cas dégénéré)
Légende (design Nature) :
  N/E  pupitre (lectern)  ·  M pale_moss_block  ·  + bouton  ·  g gravel  ·  '~ leaf_litter
"""

# =============================================================================
# 8. SCÉNARIOS
# =============================================================================

SPLINE = "white_wool"
CORNER = "grass_block"


def flat_world(x0, x1, z0, z1, height_fn=None, depth=4):
    """Terrain : surface grass (height_fn(x,z) ou 0) + 'depth' couches dessous."""
    w = World()
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            h = height_fn(x, z) if height_fn else 0
            for y in range(h - depth, h + 1):
                w.set(x, y, z, GROUND)
    return w


def scenario(name, control, opt, pad=3, colorize=None, subtitle="", height_fn=None):
    """Trace la spline issue des points de contrôle, rectifie, construit, affiche."""
    floats = catmull_rom_points(control)
    vox = voxelize(floats)
    xs = [v[0] for v in vox]
    zs = [v[2] for v in vox]
    w = flat_world(min(xs) - pad, max(xs) + pad, min(zs) - pad, max(zs) + pad,
                   height_fn=height_fn)
    for x, y, z in vox:
        w.set(x, y, z, SPLINE)
    # Rectification (nivelage, épuration des L, re-nivelage)
    trace = list(vox)
    trace = rectify_vertical(w, trace, SPLINE, CORNER)
    trace = rectify_l(w, trace, SPLINE, CORNER)
    trace = rectify_vertical(w, trace, SPLINE, CORNER)
    if colorize:
        colorize(w, trace)
    build_all(w, trace, opt)
    render(w, trace, name, subtitle)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    style = "nature" if "--style" in sys.argv and "nature" in sys.argv else "classic"
    only = set(args)

    def want(name):
        return not only or name in only

    if want("straight-ns"):
        scenario("Ligne droite N-S (13 blocs)", [(8, 1, 20), (8, 1, 8)],
                 Options(style=style), subtitle="Corail S au centre, murets │ de chaque côté.")

    if want("straight-ew"):
        scenario("Ligne droite E-O (13 blocs)", [(5, 1, 8), (17, 1, 8)],
                 Options(style=style))

    if want("halfturn"):
        # NS, L-court puis virage à l'est : side_west attendu aux bouts si L=2
        scenario("Virage N-S -> E (courbe douce)",
                 [(10, 1, 24), (10, 1, 8), (26, 1, 4)],
                 Options(style=style),
                 subtitle="Sides jaunes aux extrémités des lignes où la trace vire.")

    if want("s-curve"):
        scenario("Grande courbe en S (N-S -> E -> N-S)",
                 [(8, 1, 36), (8, 1, 22), (20, 1, 12), (20, 1, 2)],
                 Options(style=style),
                 subtitle="Le test roi : lignes, tiers de murets/sides, diagonales mixtes.")

    if want("diag-true"):
        # Tracé manuel canonique connexe : 6 NS, 5 diagonale SW-NE stricte, 6 EW.
        w = flat_world(0, 24, 0, 24)
        trace = [(10, 1, z) for z in range(14, 20)]           # segment N-S (au sud)
        trace += [(10 + i, 1, 14 - i) for i in range(1, 6)]   # diagonale SW->NE stricte
        trace += [(x, 1, 8) for x in range(16, 22)]           # segment E-O (au nord)
        for x, y, z in trace:
            w.set(x, y, z, SPLINE)
        build_all(w, trace, Options(style=style))
        render(w, trace, "Vraie diagonale (N-S / diag stricte / E-O)",
               "Corail S moitié sud, corail E moitié nord, transition (4 murets) au milieu.")

    if want("diag-false"):
        # Tracé manuel : 5 NS, 4 diagonale stricte, 5 NS -> fausse diagonale.
        w = flat_world(0, 16, 0, 22)
        trace = [(8, 1, z) for z in range(13, 18)]
        trace += [(8 + i, 1, 13 - i) for i in range(1, 5)]
        trace += [(12, 1, z) for z in range(4, 9)][::-1]
        for x, y, z in trace:
            w.set(x, y, z, SPLINE)
        build_all(w, trace, Options(style=style))
        render(w, trace, "Fausse diagonale (N-S de chaque côté)",
               "Corail S sur toute la diagonale + 4 murets d'angle par bloc.")

    if want("corner-90"):
        scenario("Virage à 90° net", [(6, 1, 16), (6, 1, 6), (16, 1, 6)],
                 Options(style=style))

    if want("slope"):
        # Terrain en escalier + spline tracée au-dessus -> le rectif plaque le rail au sol.
        def hill(x, z):
            if z >= 18: return 0
            if z >= 12: return 2
            if z >= 6:  return 4
            return 4
        scenario("Ligne N-S en pente (terrain en escalier, +0 puis +2 puis +4)",
                 [(8, 5, 22), (8, 5, 12), (8, 7, 6), (8, 8, 2)],
                 Options(style=style), height_fn=hill,
                 subtitle="En design Nature : pale_moss_block + bouton sur les montées/descentes.")

    if want("crossing"):
        if style != "nature":
            print("(scénario 'crossing' = design nature : relancer avec --style nature)")
        w = flat_world(0, 14, 0, 14)
        for z in range(1, 14):
            w.set(7, 1, z, "red_wool")
        for x in range(1, 14):
            w.set(x, 1, 7, "blue_wool")
        trace = [(7, 1, z) for z in range(1, 14)] + [(x, 1, 7) for x in range(1, 14)]
        build_all(w, trace, Options(style="nature"))
        render(w, trace, "Croisement N-S × E-O (design nature)",
               "Gravel + leaf_litter à l'intersection.")

    print(LEGEND)


if __name__ == "__main__":
    main()
