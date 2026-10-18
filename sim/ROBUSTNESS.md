# ROBUSTNESS - contracts, invariants and coverage

Goal: **within thousands of cases it must be impossible to find a visible
bug**. This document lists every situation handled, every invariant checked
and where it is enforced (production Java pipeline, reference simulator,
parity corpus).

## 1. Visible contracts (what the player must never see)

| # | Contract | Implemented in | Checked by |
|---|----------|----------------|------------|
| C1 | **No missing rail**: every trace voxel becomes a visible core (coral, black pillar, lectern, pale moss, +/-1 vertically). | classic/nature designs, anti-refusal guards | invariant **I1** (export) |
| C2 | **No floating track piece**: no rail and no column base keeps air or water underneath (up to 4 blocks filled; deeper gaps are deliberate bridges). | `fillSupports` (classic/nature, air and water), sim `support_fill` | invariant **I4** |
| C3 | **No stacking**: a column (x,z) never carries two track voxels at different heights. | `dedupe_columns` / `Grounding.dedupeColumns` | invariant **I3** |
| C4 | **Continuous track**: the cores of a trace form ONE ribbon (adjacent columns at any height, including stairs and stacked crossings; only a horizontal hole breaks it). | grounding plus dedupe pass | invariant **I2** (`components_ribbon`) |
| C5 | **No leftover wool**: no trace wool stays visible after the build. | L-corner purge, dedupe, wool consumption on refused columns | invariant **I5** |
| C6 | **Idempotent rebuild**: rebuilding the same track changes nothing (no wall/panel/door churn, no orphan door). | two-speed column guard (section 3) | stress `classic-idempotence`, `classic-protect` |
| C7 | **No existing rail destroyed**: a second line (or a rebuild) never erases an existing core, and only a core may reclaim another line's decor. | core guards (`NATURE_CORES` / `isRailCore`) | stress `-protect`, `jun-*` corpus |
| C8 | **No parasitic block around a pillar**: support pillars descend cleanly to the ground (or the water bed) with no stubs in mid-air. | extended `fillSupports` | `pillar_scan` (0 parasites) |
| C9 | **No uncontrolled digging**: a tunnel cuts at most ONE crest of at most 2 blocks per column, across all passes. | `dug` branch of vertical rectification | `stupid_stress` |
| C10 | **Faithful leaf litter**: 3 segments as soon as the corner changes X or Z (7-pair tables, `is_moss`, lectern+carpet otherwise moss+button). | nature design (exact port) | stress true-diag, parity |
| C11 | **Classic doors are lower halves only** (light theme). | `build_column` / `ColumnWriter.column` | parity `door_lower_*` tokens |

## 2. Situations covered

The parity corpus (`sim/parity_export.py`, **752 scenes**) is regenerated in CI
(deterministic, `PYTHONHASHSEED=0`) and replayed block for block by the real
Java pipeline in `ParityHarness` (`gradle parityCheck`, build gate).

| Family | N | What it catches |
|--------|---|-----------------|
| `drift/long2/lturn/tjun/xlvl/spiral/zigzag` (historical) | 22 | the real regressions from the screenshots (jagged drift, L branch, T/X crossings at the same level and dy=1, hill spiral, zigzag chaos) |
| `fuzz-{flat,hills,chaos}-*` | 180 | random polylines of 6-16 points over 3 terrains and 3 style/theme pairs |
| `jun-*` | 90 | dense 2-3 line junctions at a common centre (real crossings) |
| `buried-*` | 40 | buried mode (`baseDy=-1`) |
| `lake-*` | 80 | tracks crossing ponds (surface and bed, cross mixes) |
| `wool-*` | 80 | player wool laid on the path (obstacles or markers) |
| `rock-*` | 50 | terrain dotted with stone, andesite and gravel |
| `mount-*` | 80 | steep climbs and descents (+/-1 and +/-2 steps) |
| `tun-*` | 50 | tunnels under crests (control points below the terrain) |
| `teeth-*` | 80 | jagged drift with alternating y (saw teeth) |

The corpus always exports valid: invariants I1-I5 reject any scene whose
expectation would be wrong - a violation fails the export (exit 1) and
therefore CI. That is a real bug, never an "adjusted" expectation.

Local stress suites (sim), all green:

- `stress.py`: 729 scenarios / 6068 builds (lines, true diagonals, stairs,
  loops, crossings, strict idempotence, rebuild protection).
- `realistic_stress.py`: 56 lines / 288 builds / 232 M checks.
- `stupid_stress.py`: 2490 scenarios / 6649 builds / 75 129 voxels.
- `ridges_stress.py`: 8000 crest scenarios / 32 000 builds.
- `mass_stress.py`, `repro_*.py`: older stress and screenshot reproductions.
- `pillar_scan.py`: every pillar, no parasite plus diagrams
  (`sim/pillar_report.txt`, generated).

### Strict determinism: the soil draw never decides the geometry

The soil mix (deepslate, cobbled deepslate, pale oak, ores, gravel) is drawn
randomly, but **every one of its blocks is wool-layable** (sim
`NATURAL_SOFT` = Java `Grounding.isWoolLayable`). A later trace crossing a
support placed earlier therefore continues without drifting. Otherwise the
track position would depend on the RNG: not reproducible between the
simulator and the Java port, and unstable from one run to the next
(witness scene `jun-70`, three-trace crossing). The Java `pickSoil` RNG is
also **seeded** (`Random(20240913)`, never `ThreadLocalRandom`), so
screenshots and parity are reproducible block for block and every mix block
maps to the `soil` token on both sides.

**The symmetric trap**: `gravel` is also part of `RAIL_FAMILY` (it is the
nature support ground), and Java `isWoolLayable` used to test the rail guard
BEFORE the terrain switch, so wool was never placed on gravel and the voxel
stayed frozen in the air (scenes `rock-*`, `jun-10/48`: track duplicated two
blocks above). The terrain switch now precedes the rail guard: gravel is
ground, never a track.

## 3. Priority rules (which write owns a cell?)

1. **A core (coral, black pillar, lectern, pale moss) is NEVER replaced** -
   neither by decor nor by a later core (guard `NATURE_CORES` /
   `ColumnWriter.isRailCore`, in design and grounding passes).
2. **A later core reclaims a decor cell** from an earlier line (dense
   junctions: visible rail wins over a neighbouring wall).
3. **Decor yields to any existing rail block** (anti-churn on rebuild),
   except an identical block. Gravel is the passive bed that decor may
   improve, never the opposite.
4. **A refused column consumes its wool**: when the core of a voxel is
   exactly the block occupying the cell (shared crossing column), the white
   trace wool disappears cleanly - never a visible leftover (C5).

## 4. Trace membership is POSITIONAL, never name-based

The mod's default uniform fill is `ORANGE_WOOL`, and players may place wool
by hand. A `*_wool` block is therefore never a track criterion: every low
pass (`Grounding`, `LCorners`, `flattenTeeth`, `dedupeColumns` and their sim
mirrors) reads `_TRACE_WOOL` / `laidWool`, the set of POSITIONS of the voxels
that this trace actually laid with wool. Consequences:

- the wool floor of a previous build no longer locks descents;
- the leftover corner of a climb can no longer erase a neighbouring coral
  just because the voxel "was in the list" (bug `jun-45`);
- player wool on the path is a **protected solid obstacle**, never absorbed
  or removed;
- the initial wool pass (`isWoolLayable` / `NATURAL_SOFT`) accepts only air
  and soft natural terrain (grass, rock, dirt, sand, snow, ice, water):
  galleries inside rock work, existing rails, wool and decor do not move.

## 5. Crossings and junctions (geometry)

- Descending wool stops on any solid block; an existing rail block IS a
  support (the second line rests on it, never erases it).
- **Junction sink**: wool stopped on a DECOR column of another line
  (support soil + decor, no core) is taken back into the base, so its core
  claims the decor cell and the crossing stays **continuous at the same
  level** instead of a two-block bounce that breaks the ribbon.
- Crossing on a core: the column shares the point (both cores coexist,
  stacked - I2 treats them as the same track point).
- L purge: diagonal veto, local 3x3x3 connectivity, global connectivity
  (articulation points); a corner only becomes grass when placed out of
  water.

## 6. Current numbers

- **752 parity scenes**, all valid (I1-I5 green).
- Java harness: **block-for-block** parity required (bidirectional change
  set: any leak anywhere fails).
- 0 pillar parasites, 0 stress violations over 6068 + 288 + 6649 + 32000
  builds.
