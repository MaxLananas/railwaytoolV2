# BTE Rail Path Tool

Axiom addon that builds the BuildTheEarth France railway design from a spline,
in one tool: the four reference scripts (spline, levelling, colouring,
construction) are merged into a live preview and a single edit.

**Minecraft 1.21.10 · Fabric · Axiom 5.4.2+ · client-side**

## Features

- Catmull-Rom spline with adaptive sampling, voxelised into a continuous
  26-connected path.
- Vertical rectification (terrain following, tunnel through crests, bridge
  over deeper gaps) and L-corner cleanup with diagonal / local / global
  connectivity guards.
- Deterministic N-S / E-O / diagonal classification from the trace itself.
- Two rail designs:
  - **Classic** — dead bubble coral centres, mud-brick or andesite walls,
    spruce shelves or iron-door panels, weighted deepslate soil mix.
  - **Nature** — lecterns / pale moss, gravel, leaf litter and oak buttons.
- Ghost preview of the exact plan and of the spline, before anything is
  written. Construction goes through Axiom's block pipeline, so it is
  undoable with `Ctrl+Z` and synchronised with the server.
- Rails already in the world are never overwritten: cores are protected,
  lateral decor is only replaced by a new core at a junction, and the
  support soil of a new column never eats an existing wall.
- Options and three presets persist in `config/bte_railpathtool.json`.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.10.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) and
   [Axiom 5.4.2+](https://modrinth.com/plugin/axiom).
3. Drop `rail-path-tool-<version>.jar` into `.minecraft/mods/`.

Axiom is optional at load time: without it the tool is disabled and the game
starts normally.

## Usage

| Input | Action |
| --- | --- |
| Right click | Add a control point above the targeted block |
| `Delete` | Remove the last control point |
| `Enter` | Build the previewed rail |
| `Ctrl+Z` (Editor Mode) | Undo the last build through Axiom's history |

The tool panel is organised around the build action: a status line, a
collapsible how-to, the line summary with a block-budget bar, then **Build
rail** with Undo / Simplify / Clear underneath. Density, style and orientation
sit in a labelled column, the six generation toggles in a compact grid, and
the classic-only settings (theme, ground fill, height) live in one collapsed
section. Three save/load preset slots and a timing section close the panel.
Long notes wrap at the panel edge, and every option carries a tooltip.

| Input | Action |
| --- | --- |
| `Build rail` / `Enter` | Build the previewed rail |
| `Undo point` / `Delete` | Remove the last control point |
| `Simplify` | Drop nearly collinear points |
| `Clear` | Empty the control line |
| `Pick` | Use the block selected in Axiom's palette |

The ghost is capped at 6 000 overridden blocks (sampled evenly above that) and
a build is refused above 60 000 planned blocks.

## Repository layout

```
src/main/java/com/bte/railpathtool/
  RailPathToolMod.java     Fabric entry point
  axiom/                   ServiceLoader bridge to Axiom
  tools/RailwayTool.java   CustomTool: input, UI, preview, build
  spline/                  Catmull-Rom sampling and voxelisation
  track/                   Grounding, L-corner cleanup, classification, view
  design/                  Classic and Nature block writers
  lib/                     RDP, adaptive sampler, config, profiler, union-find
  dev/ParityHarness.java   Java-vs-simulator parity gate
  dev/InGameSelfTest.java  Opt-in in-game regression suite
  mixin/                   Title-screen credit
sim/                       Python reference model, parity exporter, stress tests
tools/ingame_driver.py     Headless client driver used by CI
tools/panel_shot.py        Headless driver for the UI screenshots
docs/ingame/               Last in-game run: results, logs, screenshots
docs/panel/                Screenshots of the tool panel in Axiom's editor
```

## Build

Requirements: JDK 21 or newer (CI uses Temurin 25) and the Axiom jar for 1.21.10.

```bash
mkdir -p libs
curl -L -o libs/Axiom-5.4.2-for-MC1.21.10.jar \
  https://cdn.modrinth.com/data/N6n5dqoA/versions/65TQdBZB/Axiom-5.4.2-for-MC1.21.10.jar
./gradlew build
```

Loom extracts the nested `axiomclientapi.jar` automatically. The mod jar is
written to `build/libs/`.

## Tests

The Python model in `sim/` is the executable specification. It is
auto-validated by the export invariants and replayed block-for-block by the
real Java pipeline:

```bash
python3 sim/parity_export.py       # 752 scenes, validated by I1-I5
python3 sim/stress.py              # adversarial geometry
python3 sim/realistic_stress.py    # realistic multi-line scenes
python3 sim/stupid_stress.py       # random walks, rebuilds, hostile terrain
python3 sim/ridges_stress.py       # crests and tunnels
./gradlew parityCheck              # requires sim/parity/scenes.txt
```

`parityCheck` is part of `check`, and CI runs it before producing the jar.
The comparison is a bidirectional set of changed blocks across the whole
world, so a single stray or missing block fails the build.

### In-game regression suite

`dev/InGameSelfTest` runs the real tool inside a real client against an
integrated server. It is inert unless the JVM property is set:

```bash
export JAVA_TOOL_OPTIONS=-Dbte.railtool.selftest=true
./gradlew runClient
```

A superflat creative world must be loaded. The suite plans, previews and builds
sixteen scenes (straight, replan, curve, slope, nature, zigzag, clutter,
water, long, many, cross, presets, undo, undo-build, edges, persist)
through the production callbacks and Axiom pipeline, then checks the resulting
blocks against the plan: exact block map, no side effects, supported cores,
single ribbon, no temporary wool, released render lock, cleared ghost, saved
presets. The `undo-build` scene builds 153 blocks, then waits while the CI
driver opens Axiom's editor, presses `Ctrl+Z` and closes it, and checks that
every block of the build, and every cell around it, is back to its previous
state. The `persist` scene then flushes the world and records every block it
built. A summary lands in `selftest-result.txt` and in the log with the
`[SELFTEST]` prefix.

Passing `-Dbte.railtool.selftest.phase=2` runs the restart half instead: it
reads the recorded blocks back from the reopened world, checks the preset on
disk and builds another line. The CI driver runs both launches in sequence.

CI runs that suite headless in `.github/workflows/ingame.yml`: GitHub's
ubuntu runner, Xvfb, Mesa llvmpipe, a superflat world generated by the vanilla
server, and `--quickPlaySingleplayer`. `tools/ingame_driver.py` drives the
launch and screenshots each stage; results are committed to `docs/ingame/`.

### Panel screenshots

`.github/workflows/panel.yml` launches the same client, opens Axiom's editor,
selects the rail tool and drives it with real mouse clicks: three control
points, the ghost preview, then **Build rail**. Each step is captured and
committed to `docs/panel/`. `dev/EditorShot` only skips Axiom's six-step
introduction, which needs a keyboard; it is inert without
`-Dbte.railtool.ui=true`.

## Changelog

Notable changes per release are in [CHANGELOG.md](CHANGELOG.md); it is also the
source of the GitHub release notes. `.github/workflows/release.yml` publishes a
jar manually and refuses to run without a matching changelog section.

## License

MIT. See [LICENSE](LICENSE).
