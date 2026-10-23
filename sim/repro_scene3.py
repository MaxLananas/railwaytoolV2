#!/usr/bin/env python3
"""SCENE 3: inspectable ASCII renderings of the four repro_photos.py cases.

Screenshots are not enough to judge the result, so for each of the four bugs
reported with a picture (5 Sept 2026) this script runs the whole flow
(terrain -> written trace -> rectification -> design -> support pass) and
prints the full plan diagram of the blocks actually placed, style by style,
plus the side view of the height changes. Violations reported by the spy world
(same rules as repro_photos) stop the run with a failing exit status.

Legend: see rail_sim.GLYPHS (S/E coral, N/E lecterns, M moss, ~ carpet,
│─└┘┌┐ walls, n/s/e/w door panels, ░▒▓ soil hill).
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rail_sim as R
from repro_photos import (SpyWorld, flat_ground, trace_write, run_case,
                          counts, fails)

RENDER_ALL = "--no-render" not in sys.argv


def world_bounds(world):
    xs = [p[0] for p in world.blocks]
    zs = [p[2] for p in world.blocks]
    return min(xs), max(xs), min(zs), max(zs)


def render_plan(world, trace, title):
    """Plan diagram: for every y holding rail, the x/z grid with the glyph of
    the highest block placed in that column."""
    x0, x1, z0, z1 = world_bounds(world)
    # Every layer holding a block placed by the build (rail OR soil pillar):
    # the diagram must include the pillars, not only the track.
    ys = sorted({p[1] for p, st in world.blocks.items()
                 if st not in (R.AIR, "grass_block", R.GROUND)})
    print(f"\n### {title}")
    for y in ys:
        print(f"  -- layer y={y} --")
        print("     " + "".join(str(x % 10) for x in range(x0, x1 + 1)))
        for z in range(z0, z1 + 1):
            row = []
            for x in range(x0, x1 + 1):
                st = world.get(x, y, z)
                if st is None or st == R.AIR or st == "grass_block":
                    row.append(" " if (x, y, z) not in set(trace) else ".")
                else:
                    row.append(R.GLYPHS.get(st, "?"))
            line = "".join(row).rstrip()
            if line.strip():
                print(f"  z={z:>3} {line}")


def render_side(world, trace, title):
    """Side view: highest block placed per column along the dominant axis."""
    tv = sorted(set(trace))
    if not tv:
        return
    print("  -- profile (top y placed per x,z column) --")
    for (x, _y, z) in tv[:: max(1, len(tv) // 40)]:
        top = max((y for (px, y, pz), st in world.blocks.items()
                   if px == x and pz == z and st not in (R.AIR, "grass_block")),
                  default=None)
        if top is not None:
            print(f"   ({x},{z}) top=y{top}")


def flow(tag, trace, world, styles=("classic", "nature"), themes=(1, 2)):
    """End-to-end flow identical to repro_photos.run_case, plus diagrams."""
    print(f"\n{'=' * 70}\nFLOW [{tag}]: terrain -> trace -> design -> support")
    before = len(fails)
    for style in styles:
        for theme in themes:
            # A fresh world per build: repro_photos.run_case already isolates them.
            run_case(tag, trace, world, styles=(style,), themes=(theme,))
            if len(fails) != before:
                print(f"  !! violations during {style}/th{theme}")
                return
            sub = SpyWorld()
            # Rendering world: rebuild on a world dedicated to the render.
            x0, x1, z0, z1 = world_bounds(world)
            flat_ground(sub, x0, x1, z0, z1, y0=0)
            trace_write(sub, trace)
            opt = R.Options(style=style, theme=theme)
            R.build_all(sub, trace, opt)
            if RENDER_ALL:
                render_plan(sub, trace, f"{tag} - {style} th{theme}")
                render_side(sub, trace, tag)
    print(f"  OK {tag}: full flow green, diagrams above")


def scene1():
    """Photo 1: gentle classic line on flat ground with micro teeth."""
    world = SpyWorld()
    flat_ground(world, -10, 40, -6, 4, y0=0)
    trace = []
    x_cur, z_cur = -6, 0
    for i in range(36):
        x_cur += 1
        y_cur = 1 if i % 9 == 8 else 0
        trace.append((x_cur, y_cur, z_cur))
    trace_write(world, trace)
    flow("photo1-ligne-douce", trace, world)


def scene2():
    """Photo 2: long east branch then a sharp 90 degree turn at the control point."""
    world = SpyWorld()
    flat_ground(world, -2, 34, -20, 4, y0=0)
    trace = [(i, 0, 0) for i in range(28)]
    trace += [(27, 0, -i) for i in range(1, 14)]
    trace_write(world, trace)
    flow("photo2-virage-L", trace, world)


def scene3():
    """Photo 3: dense knot, an S doubling twice on the same crossing."""
    world = SpyWorld()
    flat_ground(world, -14, 10, -10, 10, y0=0)
    seq = [(0, 0), (1, 0), (1, 1), (1, 2), (0, 2), (-1, 2), (-2, 2), (-2, 1),
           (-2, 0), (-1, 0), (-1, 1), (0, 1), (2, 1), (2, 2), (2, 3), (1, 3),
           (0, 3), (-1, 3), (-3, 3), (-3, 2), (-3, 1), (-3, 0), (-2, 1)]
    trace = [(x, 0, z) for (x, z) in seq]
    trace_write(world, trace)
    flow("photo3-noeud-dense", trace, world)


def scene4():
    """Photo 4: gentle nature line with minimal y teeth and jogs."""
    world = SpyWorld()
    flat_ground(world, -3, 30, -4, 4, y0=0)
    trace = []
    for i in range(24):
        y = 1 if (i % 7 == 3) else 0
        z = 1 if (12 <= i < 16) else 0
        trace.append((i, y, z))
    trace_write(world, trace)
    flow("photo4-nature-douce", trace, world, styles=("nature",))


def main():
    scene1()
    scene2()
    scene3()
    scene4()
    print(f"\n=== SCENE 3 (renders): {counts['scenarios']} scenarios,"
          f" {counts['builds']} builds ===")
    if fails:
        print(f"{len(fails)} violations:")
        for f in fails[:25]:
            print("   ", f)
        sys.exit(1)
    print("All green: no violation.")


if __name__ == "__main__":
    main()
