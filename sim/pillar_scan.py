#!/usr/bin/env python3
"""Support pillar scan: no parasitic block around a pillar.

Every scene (parity corpus plus dedicated valleys) replays the full flow
(terrain -> trace -> design -> support), then:

1. Detects every PILLAR: a vertical stack of fill soil placed by the build,
   topped by a rail block and resting on terrain.
2. Around each pillar (a 5x5 box over its whole height +-2) it lists every
   "parasite": a placed block that is neither the pillar nor the rail ribbon on
   top and that either FLOATS (air directly below) or sticks to the pillar
   flank at mid height, a decor wart in mid-air.
3. For each affected pillar it prints a full vertical cross-section
   (x/y slice at the pillar z and z/y slice at the pillar x) with all blocks.

Exits with status 1 on the slightest parasite: a clean pillar has nothing
around it but rail on top and ground at its base.
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
    """Build-placed soil columns topped by rail and resting on the ground."""
    pillars = []
    cols = {}
    for (x, y, z), st in world.blocks.items():
        if (x, y, z) in pre_keys:
            continue
        if soil(st):
            cols.setdefault((x, z), []).append(y)
    for (x, z), ys in cols.items():
        ys = sorted(set(ys))
        # Contiguous vertical segments.
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
            continue   # plain backfill with no rail on top: not a pillar
        if len(seg) < 2:
            continue   # normal column base (one soil under the rail), intended
        if below in (R.AIR, None):
            continue   # checked elsewhere (floating)
        out.append((x, z, base, top))
    return out


def scan_pillar(world, x, z, base, top):
    """Returns (parasites, box cells) around the pillar at x,z from base to top."""
    parasites = []
    for dx in (-2, -1, 0, 1, 2):
        for dz in (-2, -1, 0, 1, 2):
            if dx == 0 and dz == 0:
                continue
            for y in range(base - 1, top + 4):
                st = world.get(x + dx, y, z + dz)
                if st in TERRAIN:
                    continue
                # Rail ribbon on top (levels top+1..top+3, outside the pillar)
                # is fine only when it rests on the rest of the line.
                below = world.get(x + dx, y - 1, z + dz)
                floating = below in (R.AIR, None)
                flank = (y <= top)  # at pillar height: a wart in mid-air
                if floating and (flank or st not in R.RAIL_FAMILY):
                    parasites.append((x + dx, y, z + dz, st))
    return parasites


def diagram(world, x, z, base, top, parasites):
    """Vertical x/y slice at z and z/y slice at x around the pillar."""
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

    lines = [f"    pillar ({x},{z}) base=y{base} top=y{top}"]
    lines.append("    slice x/y (z=%d)  x = %d..%d" % (z, x - 3, x + 3))
    lines.append("       " + "".join(str((x + dx) % 10) for dx in range(-3, 4)))
    for y in range(y1, y0 - 1, -1):
        row = "".join(glyph(x + dx, y, z) for dx in range(-3, 4))
        if row.strip("."):
            lines.append(f"  y={y:>3} {row}")
    lines.append("    slice z/y (x=%d)  z = %d..%d" % (x, z - 3, z + 3))
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


def process(sid, world, pre_keys, seeds=()):
    stats["scenes"] += 1
    pillars = find_pillars(world, pre_keys)
    stats["pillars"] += len(pillars)
    dirty = 0
    seeded = {(sx, sy, sz) for (sx, sy, sz, _b) in seeds}
    for (x, z, base, top) in pillars:
        parasites = [p for p in scan_pillar(world, x, z, base, top)
                     if (p[0], p[1], p[2]) not in seeded]
        # Full diagram in the report, parasitic or not, so every pillar can be
        # inspected visually.
        report.append(
            f"[{sid}] pillar ({x},{z}) y{base}..y{top}: "
            + (f"{len(parasites)} PARASITE(S)" if parasites else "clean")
            + "\n" + diagram(world, x, z, base, top, parasites))
        if parasites:
            dirty += 1
            violations.append(
                f"[{sid}] pillar ({x},{z}) y{base}..y{top}: "
                f"{len(parasites)} parasite(s)\n"
                + diagram(world, x, z, base, top, parasites))
    if not dirty:
        stats["clean"] += 1


def scenes_vallees():
    """A few dedicated hollows and valleys that force real pillars."""
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
        world, initial, traces, bad, models = sc.run()
        if bad:
            violations.append(f"[{sc.sid}] self-check invalide: {bad[:2]}")
            continue
        # All pre-existing blocks are terrain, seeds or water, filtered by the scan.
        process(sc.sid, world, set(), getattr(sc, "seeds", ()))
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "pillar_report.txt")
    with open(out, "w") as f:
        f.write("\n\n".join(report))
    print(f"=== PILLAR SCAN: {stats['scenes']} scenes | "
          f"{stats['pillars']} pillars | {stats['clean']} clean scenes ===")
    print(f"full diagram of every pillar -> {out}")
    if violations:
        show = int(os.environ.get("PILLAR_DIAG", "12"))
        print(f"{len(violations)} pillar(s) with parasites "
              f"(full diagrams, first {show} shown):")
        for v in violations[:show]:
            print(v)
        sys.exit(1)
    print("OK: no parasitic block around the pillars.")


if __name__ == "__main__":
    main()
