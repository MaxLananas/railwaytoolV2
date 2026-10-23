#!/usr/bin/env python3
"""Exports the Java/sim PARITY scenes to the shared sim/parity/scenes.txt.

Each scene holds terrain boxes (R [block]), water boxes (Q), isolated blocks
(W: forced wool, stone, rail...), INTEGER control points as in game (C), the
expected sim classification (D/N) and the expected CHANGED cells (E, token
form). The gradle task `parityCheck` replays the REAL Java pipeline (adaptive
sampler 6 -> voxelize -> Grounding x2 -> LCorners -> flattenTeeth ->
dedupeColumns -> TrackModel -> design, digging always on as in the product
default) and requires an IDENTICAL block map, comparing change sets in both
directions: a single leak anywhere in the world is a failure.

The file self-validates right here against the VISIBLE invariants (see
sim/ROBUSTNESS.md): no floating rail (neither on air nor on water), no
vertical duplicate on the track, no residual wool outside the seeded blocks,
one component per trace and a core at every voxel. An intrinsically false
expectation is never written: a scene violating an invariant fails the whole
export (exit 1), and that is a real bug discovered.

Coverage (deterministic, PYTHONHASHSEED-stable): flat/hilly/chaos terrain,
toothed drifts, multi-trace junctions, buried tracks, lakes, forced wools,
stone, tunnels, steep climbs, spirals and the fuzz families.
"""
import os
import sys
import random

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rail_sim as R

random.seed(20240913)

TOK_SOIL = {"deepslate", "cobbled_deepslate", "pale_oak_wood",
            "deepslate_iron_ore", "deepslate_coal_ore"}

# Blocks that accept the trace wool (EXACT mirror of Java
# Grounding.isWoolLayable). Everything else (rail, wool, decor) is protected:
# a trace can never overwrite another line.
# EVERY soil of the design mix (and nature gravel) is listed here: a support
# placed by an earlier trace must never divert the next one, otherwise the
# GEOMETRY would depend on the random mix draw (jun-70 and friends: a
# pale_oak_wood draw made the trace refused or moved).
NATURAL_SOFT = {R.AIR, None, "grass_block", "water", "stone", "dirt",
                "coarse_dirt", "sand", "sandstone", "terracotta", "clay",
                "snow_block", "ice", "mud", "andesite", "granite", "diorite",
                "deepslate", "cobbled_deepslate", "pale_oak_wood",
                "deepslate_iron_ore", "deepslate_coal_ore", "gravel",
                "oak_leaves", "spruce_leaves"}

WOOL_PREFIX = "_wool"
RAIL_CORE_TOKEN = "black_wool"   # black pillar = rail, not residual wool


def tok(st):
    return "soil" if st in TOK_SOIL else st


def make_world(x0, x1, z0, z1, height_fn, depth=12):
    """Explicit grassy terrain, as in the game superflat preset."""
    w = R.World()
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            h = height_fn(x, z)
            for y in range(h - depth + 1, h + 1):
                w.set(x, y, z, R.GROUND)
    return w


def flat(h):
    return lambda x, z: h


def stone_top(top_block, h):
    """Plateau of `top_block` at height h, terrain speckled with rock."""
    return lambda x, z: h, top_block


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


def lake(base, cx, cz, rx, rz, depth):
    """Flat terrain hollowed into an elliptical basin: the lake bed."""
    def h(x, z):
        d = ((x - cx) ** 2) / (rx * rx + 1e-9) + ((z - cz) ** 2) / (rz * rz + 1e-9)
        if d <= 1.0:
            return base - depth
        return base
    return h


def build_trace_seq(world, control, dug):
    """Pipeline identical to the mod (UI defaults: adaptive 6, snap on, purge
    on, smooth/tunnel on)."""
    floats = R.adaptive_sample([(float(x), float(y), float(z))
                                for (x, y, z) in control])
    vox = R.voxelize(floats)
    for v in vox:
        if world.get(*v) in NATURAL_SOFT:
            world.set(v[0], v[1], v[2], R.SPLINE)
    trace = R.rectify_vertical(world, vox, R.SPLINE, R.CORNER, dug=dug)
    # No wool re-lay here: rectification handles the wool itself (exact mod
    # pipeline). Re-laying hid the moves from the "L" purge guards and made
    # parity diverge.
    trace = R.rectify_l(world, trace, R.SPLINE, R.CORNER)
    trace = R.rectify_vertical(world, trace, R.SPLINE, R.CORNER, dug=dug)
    trace = R.flatten_teeth(world, trace, R.SPLINE)
    return trace


CORES = {"coral_south", "coral_east", "black_wool",
         "lectern_north", "lectern_east", "pale_moss_block"}


def self_check(name, world, traces, opt, seeded_wool=()):
    """Refuses to write an expectation that violates the visible contracts.

    Invariants (documented in sim/ROBUSTNESS.md):
      I1  a core (rail) at every trace voxel (+-1 vertically)
      I2  one component per trace, never a cut line
      I3  no vertical duplicate (mound or stack)
      I4  no floating rail block (neither air nor water below)
      I5  no residual wool outside the seeded blocks (missing rail elsewhere)
    """
    bad = []
    seeded = set(seeded_wool)
    core_want = ({"lectern_north", "lectern_east", "pale_moss_block"}
                 if opt.style == "nature"
                 else {"coral_south", "coral_east", "black_wool"})
    for trace in traces:
        for v in set(trace):
            st = world.get(*v)
            if st not in core_want and not any(
                    world.get(v[0], v[1] + dy, v[2]) in core_want
                    for dy in (1, -1)):
                bad.append(f"I1 missing core {v} ({st})")
        # Continuity of the BUILT track: each voxel is mapped to its core
        # position, then the cores must form ONE ribbon. Two cores are linked
        # when their columns are neighbours (|dx|<=1 and |dz|<=1, whatever dy:
        # a mountain staircase or a stacked crossing is a continuous line, not
        # a hole) or when they share a column (stacked crossing = same point).
        mapped = set()
        for v in set(trace):
            for dy in (0, 1, -1):
                if world.get(v[0], v[1] + dy, v[2]) in core_want:
                    mapped.add((v[0], v[1] + dy, v[2]))
                    break
        pts = list(mapped)
        parent = {p: p for p in pts}

        def find(p):
            while parent[p] != p:
                parent[p] = parent[parent[p]]
                p = parent[p]
            return p

        for i in range(len(pts)):
            for j2 in range(i + 1, len(pts)):
                a, b = pts[i], pts[j2]
                if max(abs(a[0] - b[0]), abs(a[2] - b[2])) <= 1:
                    ra, rb = find(a), find(b)
                    if ra != rb:
                        parent[ra] = rb
        if len({find(p) for p in pts}) != 1:
            bad.append(f"I2 cut trace ({name})")
        cols = {}
        for v in set(trace):
            cols.setdefault((v[0], v[2]), []).append(v[1])
        for k, ys in cols.items():
            if len(set(ys)) > 1:
                bad.append(f"I3 vertical duplicate {k} {sorted(set(ys))}")
    for (x, y, z), st in world.blocks.items():
        if st in R.RAIL_FAMILY:
            below = world.get(x, y - 1, z)
            if below in (R.AIR, None, "water"):
                bad.append(f"I4 floating {st} {(x, y, z)}")
        if (isinstance(st, str) and st.endswith(WOOL_PREFIX)
                and st != RAIL_CORE_TOKEN and (x, y, z) not in seeded):
            bad.append(f"I5 residual wool {st} {(x, y, z)}")
    return bad


class Scene:
    def __init__(self, sid, controls_list, box, height_fn, style, theme,
                 buried=False, seeds=(), water=(), top_block=None):
        self.sid = sid
        self.controls = controls_list
        self.box = box
        self.h = height_fn
        self.style = style
        self.theme = theme
        self.buried = buried
        self.seeds = list(seeds)          # [(x, y, z, block)]
        self.water = list(water)          # [(x0,x1,y0,y1,z0,z1)]
        self.top_block = top_block        # e.g. "stone": surface layer

    def fill_initial(self):
        x0, x1, z0, z1 = self.box
        world = make_world(x0, x1, z0, z1, self.h)
        if self.top_block:
            for x in range(x0, x1 + 1):
                for z in range(z0, z1 + 1):
                    world.set(x, self.h(x, z), z, self.top_block)
        for (xa, xb, ya, yb, za, zb) in self.water:
            for x in range(xa, xb + 1):
                for y in range(ya, yb + 1):
                    for z in range(za, zb + 1):
                        world.set(x, y, z, "water")
        for (x, y, z, b) in self.seeds:
            world.set(x, y, z, b)
        return world

    def run(self):
        world = self.fill_initial()
        initial = dict(world.blocks)
        opt = R.Options(style=self.style, theme=self.theme, buried=self.buried)
        traces = []
        models = []
        for ctrl in self.controls:
            dug = set()
            tr = build_trace_seq(world, ctrl, dug)
            traces.append(tr)
            models.append(R.build_all(world, tr, opt))
        seeded_wool = [(x, y, z) for (x, y, z, b) in self.seeds
                       if b.endswith(WOOL_PREFIX)]
        bad = self_check(self.sid, world, traces, opt, seeded_wool)
        return world, initial, traces, bad, models


def style_set(suf_list):
    return [("classic", 1, suf_list[0]), ("classic", 2, suf_list[1]),
            ("nature", 1, suf_list[2])]


def scenes():
    s = []
    SUFS = ("cs", "cl", "na")

    # ============ 1-8: historical corpus (22 scenes, real regressions) =========
    drift = [[(30, 61, 60), (30, 61, 54), (30, 62, 48), (29, 61, 42),
              (28, 61, 36), (27, 62, 30), (26, 61, 24), (25, 61, 18),
              (24, 62, 12), (24, 61, 6), (24, 61, 0), (17, 61, 0), (10, 61, 0)]]
    box1 = (4, 34, -6, 66)
    for style, theme, suf in style_set(SUFS):
        s.append(Scene(f"drift-{suf}", drift, box1, flat(58), style, theme))
    long2 = [[(8, 61, 30), (14, 61, 30), (20, 62, 30), (26, 61, 30),
              (32, 61, 30), (38, 61, 30)],
             [(24, 61, 30), (24, 61, 38), (25, 62, 46), (25, 61, 54)]]
    for style, theme, suf in style_set(SUFS):
        s.append(Scene(f"long2-{suf}", long2, (4, 44, 24, 60), flat(58),
                       style, theme))
    ltr = [[(6, 61, 6), (6, 61, 20), (7, 62, 28), (20, 62, 28), (34, 63, 28)]]
    for style, theme, suf in style_set(SUFS):
        s.append(Scene(f"lturn-{suf}", ltr, (2, 38, 2, 34), flat(58),
                       style, theme))
    s.append(Scene("lturn-buried", ltr, (2, 38, 2, 34), flat(58), "classic", 1,
                   buried=True))
    tj = [[(20, 61, 4), (20, 61, 22), (20, 61, 40)],
          [(4, 61, 22), (20, 61, 22), (36, 61, 22)]]
    for style, theme, suf in style_set(SUFS):
        s.append(Scene(f"tjun-{suf}", tj, (0, 40, 0, 44), flat(58), style, theme))
    cl = [[(20, 61, 4), (20, 61, 22), (20, 61, 40)],
          [(4, 62, 22), (20, 62, 22), (36, 62, 22)]]
    for style, theme, suf in style_set(SUFS):
        s.append(Scene(f"xlvl-{suf}", cl, (0, 40, 0, 44), flat(58), style, theme))
    import math as m
    sp = []
    for k in range(26):
        a = k * (m.pi / 7.0)
        r = 18.0 - k * 0.42
        sp.append((int(round(28 + r * m.cos(a))), 63 - (k // 5),
                   int(round(28 + r * m.sin(a)))))
    for style, theme, suf in style_set(SUFS):
        s.append(Scene(f"spiral-{suf}", [sp], (6, 50, 6, 50), hills(56),
                       style, theme))
    zz = [[(4 + 2 * i, 61 + (i % 3 == 2), 6 + (i % 4) * 6) for i in range(14)]]
    for style, theme, suf in style_set(SUFS):
        s.append(Scene(f"zigzag-{suf}", zz, (0, 36, 0, 30), chaos(58, 77),
                       style, theme))

    # ============ 9. polyline fuzz (180): flat, hills, chaos ===============
    rnd = random.Random(4242)
    for i in range(180):
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

    # ============ 10. dense multi-trace junctions (90) =====================
    rnd = random.Random(9001)
    for i in range(90):
        base_y = rnd.choice((60, 61))
        cx = rnd.randint(18, 26)
        cz = rnd.randint(18, 26)
        trs = []
        ntr = rnd.choice((2, 2, 3))
        for t in range(ntr):
            ang = t * (3.14159 / ntr) + rnd.uniform(-0.2, 0.2)
            # Keep the historical draw sequence: scene ids and geometry are
            # part of the regression corpus.
            for _ in range(rnd.randint(3, 5)):
                rnd.randint(4, 7)
            # trace crossing the centre
            L = rnd.randint(10, 16)
            y0 = base_y + rnd.choice((0, 0, 1))
            y1 = base_y + rnd.choice((0, 0, 1))
            pts = [(int(round(cx - L * m.cos(ang))), y0,
                    int(round(cz - L * m.sin(ang)))),
                   (cx, base_y, cz),
                   (int(round(cx + L * m.cos(ang))), y1,
                    int(round(cz + L * m.sin(ang))))]
            trs.append(pts)
        box = (cx - 24, cx + 24, cz - 24, cz + 24)
        hf = rnd.choice((flat(56), hills(55)))
        style, theme = rnd.choice((("classic", 1), ("classic", 2),
                                   ("nature", 1)))
        s.append(Scene(f"jun-{i}", trs, box, hf, style, theme))

    # ============ 11. enterre (40) ========================================
    rnd = random.Random(777)
    for i in range(40):
        n = rnd.randint(4, 9)
        x, z = rnd.randint(4, 10), rnd.randint(4, 10)
        ctrl = []
        for _ in range(n):
            x += rnd.randint(-7, 7)
            z += rnd.randint(-7, 7)
            ctrl.append((x, rnd.choice((59, 60, 60, 61)), z))
        xs = [c[0] for c in ctrl]
        zs = [c[2] for c in ctrl]
        box = (min(xs) - 7, max(xs) + 7, min(zs) - 7, max(zs) + 7)
        style, theme = rnd.choice((("classic", 1), ("classic", 2)))
        s.append(Scene(f"buried-{i}", [ctrl], box, flat(58), style, theme,
                       buried=True))

    # ============ 12. lakes (80): line crossing a pond =====================
    rnd = random.Random(31337)
    for i in range(80):
        base = 58
        wdep = rnd.randint(1, 3)
        cx = rnd.randint(14, 22)
        cz = rnd.randint(14, 22)
        rx = rnd.randint(3, 7)
        rz = rnd.randint(3, 7)
        wl = base - 1          # water surface below ground level
        hf = lake(base, cx, cz, rx, rz, wdep)
        # two traces cross the lake in different directions
        a = rnd.uniform(0, 6.28)
        L = rnd.randint(14, 20)
        t1 = [(int(round(cx - L * m.cos(a))), base + 3,
               int(round(cz - L * m.sin(a)))),
              (cx, base + 3, cz),
              (int(round(cx + L * m.cos(a))), base + 3,
               int(round(cz + L * m.sin(a))))]
        trs = [t1]
        if i % 3 == 0:       # one third: a second trace crossing above the lake
            a2 = a + 1.5708
            t2 = [(int(round(cx - 10 * m.cos(a2))), base + 3,
                   int(round(cz - 10 * m.sin(a2)))),
                  (cx, base + 3, cz),
                  (int(round(cx + 10 * m.cos(a2))), base + 3,
                   int(round(cz + 10 * m.sin(a2))))]
            trs.append(t2)
        box = (cx - 22, cx + 22, cz - 22, cz + 22)
        water = [(cx - rx, cx + rx, base - wdep + 1, wl,
                  cz - rz, cz + rz)]
        style, theme = rnd.choice((("classic", 1), ("classic", 2),
                                   ("nature", 1)))
        s.append(Scene(f"lake-{i}", trs, box, hf, style, theme, water=water))

    # ============ 13. forced wools on the path (80) ========================
    rnd = random.Random(5150)
    WOOLS = ("red_wool", "blue_wool", "lime_wool", "orange_wool",
             "pink_wool", "white_wool")
    for i in range(80):
        x0c, z0c = rnd.randint(6, 12), rnd.randint(6, 12)
        x1c, z1c = x0c + rnd.randint(10, 22), z0c + rnd.randint(4, 14)
        ctrl = [(x0c, 61, z0c), ((x0c + x1c) // 2, 61 + rnd.choice((0, 1)),
                                 (z0c + z1c) // 2), (x1c, 61, z1c)]
        # fields of coloured wool seeded around and across the crossing
        seeds = []
        for k in range(rnd.randint(6, 14)):
            wx = rnd.randint(min(x0c, x1c) - 4, max(x0c, x1c) + 4)
            wz = rnd.randint(min(z0c, z1c) - 4, max(z0c, z1c) + 4)
            wy = rnd.choice((60, 61, 61, 62))
            seeds.append((wx, wy, wz, rnd.choice(WOOLS)))
        box = (min(x0c, x1c) - 8, max(x0c, x1c) + 8,
               min(z0c, z1c) - 8, max(z0c, z1c) + 8)
        style, theme = rnd.choice((("classic", 1), ("classic", 2),
                                   ("nature", 1)))
        s.append(Scene(f"wool-{i}", [ctrl], box, flat(58), style, theme,
                       seeds=seeds))

    # ============ 14. rocky terrain and stone (50) =========================
    rnd = random.Random(616)
    for i in range(50):
        n = rnd.randint(5, 10)
        x, z = rnd.randint(4, 10), rnd.randint(4, 10)
        ctrl = []
        for _ in range(n):
            x += rnd.randint(-6, 6)
            z += rnd.randint(-6, 6)
            ctrl.append((x, rnd.choice((60, 61, 62)), z))
        xs = [c[0] for c in ctrl]
        zs = [c[2] for c in ctrl]
        box = (min(xs) - 7, max(xs) + 7, min(zs) - 7, max(zs) + 7)
        seeds = []
        for k in range(rnd.randint(4, 10)):
            sx = rnd.randint(box[0] + 2, box[1] - 2)
            sz = rnd.randint(box[2] + 2, box[3] - 2)
            seeds.append((sx, rnd.choice((59, 60, 61)), sz,
                          rnd.choice(("stone", "andesite", "gravel"))))
            # gravel is a support palette block: protected, the line goes around
        style, theme = rnd.choice((("classic", 1), ("classic", 2),
                                   ("nature", 1)))
        s.append(Scene(f"rock-{i}", [ctrl], box, flat(58), style, theme,
                       seeds=seeds))

    # ============ 15. steep climbs and drops (80) ==========================
    rnd = random.Random(2345)
    for i in range(80):
        x, z = rnd.randint(6, 12), rnd.randint(6, 12)
        y = 60
        ctrl = [(x, y, z)]
        for k in range(rnd.randint(4, 10)):
            x += rnd.randint(-5, 5)
            z += rnd.randint(-5, 5)
            y = max(56, min(70, y + rnd.choice((-1, -1, 0, 1, 1, 2))))
            ctrl.append((x, y, z))
        xs = [c[0] for c in ctrl]
        zs = [c[2] for c in ctrl]
        box = (min(xs) - 8, max(xs) + 8, min(zs) - 8, max(zs) + 8)
        hf = rnd.choice((flat(58), hills(56), chaos(58, 3000 + i)))
        style, theme = rnd.choice((("classic", 1), ("classic", 2),
                                   ("nature", 1)))
        s.append(Scene(f"mount-{i}", [ctrl], box, hf, style, theme))

    # ============ 16. tunnels under the hills (50) =========================
    rnd = random.Random(4451)
    for i in range(50):
        n = rnd.randint(4, 8)
        x, z = rnd.randint(6, 12), rnd.randint(6, 12)
        ctrl = []
        for _ in range(n):
            x += rnd.randint(-7, 7)
            z += rnd.randint(-7, 7)
            ctrl.append((x, rnd.choice((60, 60, 61)), z))   # below the crest
        xs = [c[0] for c in ctrl]
        zs = [c[2] for c in ctrl]
        box = (min(xs) - 8, max(xs) + 8, min(zs) - 8, max(zs) + 8)
        style, theme = rnd.choice((("classic", 1), ("classic", 2),
                                   ("nature", 1)))
        s.append(Scene(f"tun-{i}", [ctrl], box, hills(64), style, theme))

    # ============ 17. random toothed drifts (80) ===========================
    rnd = random.Random(881)
    for i in range(80):
        x, z = rnd.randint(6, 10), rnd.randint(6, 10)
        dirx = rnd.choice((-1, 0, 1))
        dirz = rnd.choice((-1, 1)) if dirx == 0 else rnd.choice((-1, 0, 1))
        ctrl = []
        for k in range(rnd.randint(8, 16)):
            x += dirx * rnd.randint(1, 4)
            z += dirz * rnd.randint(1, 4)
            ctrl.append((x, rnd.choice((60, 61)), z))   # y oscillating by one
        xs = [c[0] for c in ctrl]
        zs = [c[2] for c in ctrl]
        box = (min(xs) - 7, max(xs) + 7, min(zs) - 7, max(zs) + 7)
        style, theme = rnd.choice((("classic", 1), ("classic", 2),
                                   ("nature", 1)))
        s.append(Scene(f"teeth-{i}", [ctrl], box, flat(58), style, theme))

    return s


def main():
    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "parity", "scenes.txt")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    total_bad = 0
    written = 0
    with open(out_path, "w", encoding="ascii") as f:
        f.write("# Java/sim parity scenes - generated by sim/parity_export.py\n")
        f.write("# @id / O options / R terrain boxes [block] / Q water boxes / "
                "W x y z block / C n x y z\n# D/N sim classification / "
                "E x y z token (changed cells only)\n")
        for sc in scenes():
            world, initial, traces, bad, models = sc.run()
            if bad:
                total_bad += 1
                print(f"[INVALID SCENE] {sc.sid}: {bad[:4]}")
                continue
            x0, x1, z0, z1 = sc.box
            f.write(f"@{sc.sid}\n")
            f.write(f"O style={sc.style} theme="
                    f"{'dark' if sc.theme == 1 else 'light'} "
                    f"buried={1 if sc.buried else 0}\n")
            # Terrain boxes per column (h-11..h)
            for x in range(x0, x1 + 1):
                zr = z0
                while zr <= z1:
                    h0 = sc.h(x, zr)
                    z1c = zr
                    while z1c < z1 and sc.h(x, z1c + 1) == h0:
                        z1c += 1
                    f.write(f"R {x} {x} {h0 - 11} {h0} {zr} {z1c} grass_block\n")
                    zr = z1c + 1
                    if sc.top_block:
                        f.write(f"R {x} {x} {h0} {h0} {zr} {z1c} "
                                f"{sc.top_block}\n")
            for (xa, xb, ya, yb, za, zb) in sc.water:
                f.write(f"Q {xa} {xb} {ya} {yb} {za} {zb}\n")
            for (x, y, z, b) in sc.seeds:
                f.write(f"W {x} {y} {z} {b}\n")
            for i, ctrl in enumerate(sc.controls):
                for (x, y, z) in ctrl:
                    f.write(f"C {i} {x} {y} {z}\n")
            # Sim types and ordered neighbourhood after the build: the
            # classification reference for the Java harness
            for tr, mo in zip(traces, models):
                for (x, y, z) in sorted(set(tr)):
                    t = mo.types.get((x, y, z)) or "?"
                    nb = ",".join(R.ordered_neighbors(mo, x, y, z)) or "-"
                    f.write(f"D {x} {y} {z} {t}\nN {x} {y} {z} {nb}\n")
            # ONLY the cells changed from the initial state; the harness
            # compares change sets
            keys = set(world.blocks) | set(initial)
            changed = []
            for k in keys:
                t_new = tok(world.blocks.get(k)) if world.blocks.get(k) is not None else "air"
                t_old = tok(initial.get(k)) if initial.get(k) is not None else "air"
                if t_new != t_old:
                    changed.append((k, t_new))
            for (x, y, z), t in sorted(changed):
                f.write(f"E {x} {y} {z} {t}\n")
            f.write("@end\n")
            written += 1
    print(f"OK: {written} scenes exported -> {out_path}")
    if total_bad:
        print(f"{total_bad} invalid scenes (expectations NOT written)")
        sys.exit(1)


if __name__ == "__main__":
    main()
