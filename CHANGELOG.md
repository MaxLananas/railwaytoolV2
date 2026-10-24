# Changelog

All notable changes to BTE Rail Path Tool are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/).

Releases are tagged `v<version>-mc<minecraft>`. Pushing such a tag builds the
jar and publishes the matching section below as the GitHub release body.

## [2.0.3] - 2026-10-24

A panel rebuilt around the one action that matters, plus the first UI evidence
produced by a real client. No generation behaviour changed: the rail is built by
the same pipeline, from the same options, with the same Axiom undo entry.

### Added

- **Build rail**: one full-width button in the rail accent colour, above every
  other action, with an `Enter` hint in its tooltip. It greys itself out and
  explains why (`Place at least 2 points to build.`) until a line can be built.
- **Block budget bar**: the planned block count against the 60 000 limit, so an
  oversized line is visible before `Enter` refuses it.
- **Status line with severity icons**: `[i]` green for success, `[!]` amber for
  warnings (disconnected segments), `[!]` red for failures. Idle state shows
  what to do next instead of an empty band.
- **How to use**: a three-step collapsible reminder, open by default, so a new
  user can start without leaving the game.
- **Tooltips on every control**: each option states what it does and, where it
  matters, what it costs (for example density: smoother curves, slower compute).
- **`Pick` buttons** next to each ground-fill block and the uniform block, which
  take the block currently selected in Axiom's palette.
- **`CHANGELOG.md`**, kept up to date and used as the release body.
- **`Panel shot` CI job** (`.github/workflows/panel.yml`): launches a real
  client on Xvfb, opens Axiom's editor, drives the tool with `xdotool` clicks and
  commits four screenshots to `docs/panel/`.
- **`dev/EditorShot`**: opt-in helper (`-Dbte.railtool.ui=true`) that dismisses
  Axiom's six-step introduction, which a headless client cannot click through.
  Inert in normal play.

### Changed

- **Panel layout**, now six readable blocks instead of one flat list:
  header → status → how-to → line summary and budget → actions → track options →
  classic look → presets → timings.
- Density, style and orientation sit in a labelled column, so their names no
  longer scroll away behind the widgets; the six generation toggles are laid out
  two per row instead of six stacked rows.
- Theme, ground fill (uniform block or three soil shares) and height are
  classic-only and now live in a single collapsed **Classic look** section.
- Presets are three labelled rows (`#1`…`#3`, load / save) instead of six loose
  buttons; the timings section stays collapsed.
- **Simplify** is disabled on its own (four points required) instead of the
  button disappearing from the layout.
- Panel strings are translated: `en_us` and `fr_fr` both go from ~50 to
  **96 keys**, covering every label, tooltip and status message introduced by
  the redesign.

### Fixed

- **Unresolvable translation keys**: the 45 new strings were written without the
  `bte_railpathtool.` prefix, so the whole new panel would have rendered raw
  keys. Caught by checking every `tr()` call against the language files.
- **Clipped text**: long notes (subtitle, warnings, error messages) were cut at
  the panel edge; they now wrap inside the window.
- **Overlapping label and value**: `Planned blocks` collided with its number;
  the shared label column is 122 px wide instead of 96 px.
- **`?` in the tool title**: the `═` decoration is not in Axiom's bundled ImGui
  font and rendered as a replacement glyph; the name is now plain `BTE Rail`.

### Verified

- `In-game test` run [40100442653](https://github.com/MaxLananas/railwaytoolV2/actions/runs/40100442653):
  **118/118** checks in the real client, then **7/7** after the world restart,
  with the new panel. Every scene (straight, replan, curve, slope, nature,
  zigzag, clutter, water, long, many, cross, presets, undo, undo-build, edges,
  persist, restart) passes unchanged.
- `Build & Release` run [40100442633](https://github.com/MaxLananas/railwaytoolV2/actions/runs/40100442633):
  Java/simulator parity (752 scenes) then `./gradlew build`.
- `Panel shot` run [40100442642](https://github.com/MaxLananas/railwaytoolV2/actions/runs/40100442642):
  `docs/panel/04-built.png` shows the status line reading `Rail built (48
  blocks)` and Axiom's History reading `(1) Modified 48 blocks` — the build went
  through Axiom's own pipeline and is undoable.

### Under the hood

- Every ImGui call used by the panel was checked against the bindings actually
  shipped inside Axiom 5.4.2 (`docs/ci/axiom-api.txt`), not against a guess.
- `docs/panel/` keeps only the four curated screenshots; the intermediate probe
  workflows used to locate the editor UI were removed.
- `README.md` documents the panel controls and the screenshot job;
  `BUG_REPORT.md` gains a section on the interface rework and states plainly
  which interactions are not driven by the mouse.

## [2.0.2] - 2026-10-23

Bug-hunt release: fourteen defects found, fixed and re-tested in a real client.

### Fixed

- Presets were never written to disk (`drawPresets` wrote into a copy).
- A hand-edited config could index the UI arrays out of range at construction.
- Support soil and headroom air erased decor of neighbouring lines at junctions.
- The chunk render overrider leaked across world changes and could crash the
  client; it is now acquired once and always released.
- Ghost cells were cleared without holding the render lock.
- A preview exception escaped `render()` and broke the panel for the session.
- An Axiom build failure escaped `confirm()` and crashed on `Enter`.
- The block picker could store air or `null`.
- Lock acquisition was retried for every ghost block (O(n) per frame).
- Connectivity checks were O(n²) on large selections.
- The stress corpus was not deterministic (`PYTHONHASHSEED`).
- CI did not build the jar that shipped.
- The license metadata contradicted `LICENSE`.
- The in-game harness lost clicks and keystrokes at the edges of a run.

## [2.0.1] - 2026-10-18

- First public build for Minecraft 1.21.10: spline rail generator with adaptive
  sampling, terrain following, L-corner cleanup, N-S / E-O / diagonal
  classification, Classic and Nature designs, ghost preview, Axiom undo.

[2.0.3]: https://github.com/MaxLananas/railwaytoolV2/compare/v2.0.2-mc1.21.10...v2.0.3-mc1.21.10
[2.0.2]: https://github.com/MaxLananas/railwaytoolV2/compare/v2.0.1-mc1.21.10...v2.0.2-mc1.21.10
[2.0.1]: https://github.com/MaxLananas/railwaytoolV2/releases/tag/v2.0.1-mc1.21.10
