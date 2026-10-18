# Bug report - Railway Tools for Axiom

Branch `arena/f944b842-railwaytoolv2`, based on `main` @ `386e209` (v2.0.1).
All claims below were produced by commands actually run in this workspace;
in-game checks that could not be run are listed explicitly in section 6.

## 1. Method

| Layer | Command | Result |
| --- | --- | --- |
| Compilation + packaging | GitHub Actions `Build` job (`./gradlew build`, JDK 25, Loom 1.16) | green (runs `39169994094`, `39171382021`, `39173176156`, `39173378474`) |
| Java/simulator parity | CI `parity` job (`./gradlew parityCheck`) | green on every run above; 752 scenes, block-for-block |
| Scene export + invariants I1-I5 | `PYTHONHASHSEED=0 python3 sim/parity_export.py` | `OK: 752 scenes exportees` |
| Adversarial geometry | `python3 sim/stress.py` | 729 scenarios / 6068 builds, green |
| Realistic multi-line | `python3 sim/realistic_stress.py` | 56 lines / 288 builds / 232,206,007 checks, green (162 s) |
| Random walks, rebuilds, hostile terrain | `python3 sim/stupid_stress.py` | 2490 scenarios / 6649 builds / 75,129 voxels, green (13 s) |
| Crests and tunnels | `python3 sim/ridges_stress.py` | 8000 scenarios / 32,000 builds, green (14 s) |
| Pillar parasites | `python3 sim/pillar_scan.py` | 754 scenes / 2778 pillars, 0 parasites |
| Degenerate inputs | ad-hoc probe (single point, duplicates, y=-60, x=z=30000) | 9/9 cases, no exception, both styles |
| Java syntax (local) | `javac_parser.check_syntax` over 22 files | no errors (syntax only; the real compile is CI) |
| Translations | 129 lang files, placeholder arity and format | 0 mismatches, 0 malformed |

## 2. Fixed bugs

### B1 - Presets were never saved (logic)

- **Symptom**: pressing *Save* on a preset then *Load* restored the old values;
  the preset file never changed.
- **Cause**: `RailwayTool.drawPresets()` copied `config.preset1..3` into a local
  `RailConfig.Data[] slots` array and assigned `slots[idx] = snapshotData()`.
  The assignment only replaced an element of the local array; the `config`
  fields were never written.
- **Fix** (`4fefed3`): `preset(int)` / `setPreset(int, Data)` read and write the
  config fields directly, followed by `persistConfig()`.
- **Re-test**: re-read of the call path after the fix; `./gradlew build` green in
  CI. UI interaction itself is not testable here (section 6).

### B2 - Hand-edited config could index UI arrays out of range (crash)

- **Symptom**: a config with `styleIndex`/`orientIndex` beyond its combo range
  is loaded straight into the ImGui combo state.
- **Cause**: `RailConfig.load()` trusted the JSON; `restoreConfigOnce()` copied
  the raw indices into the `ImInt` selectors.
- **Fix** (`4fefed3`): `RailConfig.sanitize()` clamps every index (and every
  preset on load); `applyData()` clamps each field as well.
- **Re-test**: config round-trip exercised through the Java build; clamping is
  pure arithmetic (inspectable, no IO).

### B3 - Support soil and headroom air erased neighbouring rail decor (world bug)

- **Symptom**: rebuilding or crossing a line could remove a wall/shelf/door of
  the neighbouring line, then leave the core without its lateral decor.
- **Cause**: `ClassicDesign.ColumnWriter.column()` guarded only the core write;
  the support soil (`startY`) and the headroom air (`startY + 2`) were written
  unconditionally and could overwrite `RAIL_FAMILY` blocks of another column.
- **Fix** (`40919a2`): both writes are now skipped when the target cell belongs
  to the rail family. `rail_sim.build_column()` received the same guard.
- **Re-test (A/B, actually executed)**: with the old simulator guard removed,
  `stupid_stress.py` reports 50 `rail detruit` violations; with the guard in
  place the same suite is green (2490/6649). Parity corpus and all four stress
  suites stay green, so legitimate decor-to-core junction replacement still
  happens.

### B4 - Chunk render overrider leaked and could crash the client (Axiom lifecycle)

- **Symptom**: after the ghost preview emptied (fewer than two points, or all
  points deleted), the tool kept the global render-override lock. Any later
  acquire (same tool or another Axiom tool) throws; the old code also assumed
  acquisition always succeeded.
- **Cause**: `RailwayTool` acquired the lock in `setGhost()` but never released
  it outside `reset()`; `Axiom.acquireChunkRenderOverrider` called the throwing
  Axiom API directly.
- **Fix** (`40919a2`, `ae438bb`): named lock (`OVERRIDER_ID`), boolean
  acquisition wrapper that catches `RuntimeException | Error`, `releaseGhost()`
  called when the ghost is empty, when the trace disappears and from `reset()`.
- **Re-test**: compiled and parity-tested in CI. Axiom's own behaviour
  (`acquire` throws when held, `release` name-checked) was verified from the
  shipped 5.4.2 bytecode disassembly.

### B5 - Ghost cells were cleared without holding the lock (state bug)

- **Cause**: `ghostPositions` survived a release, so `clearGhost()` could call
  `setBlockRenderOverride` after the lock had been released or taken by another
  tool.
- **Fix** (`ae438bb`): `clearGhost()` returns immediately when the lock is not
  held, and always clears the position set.

### B6 - Preview exception escaped `render()` (crash / broken panel)

- **Cause**: `recompute()` ran unguarded from the render tick; a failure left
  stale plan/warning/length state and threw into the render thread.
- **Fix** (`40919a2`, `ae438bb`): `render()` catches `RuntimeException`, clears
  plan/ghost/spline state and surface a localized `ui.error` status; `warning`
  and `splineLength` are reset on every failure path.

### B7 - Axiom build failure escaped `confirm()` (crash on Enter)

- **Cause**: `Axiom.push()` was called unguarded; an exception from the edit
  pipeline propagated out of the tool callback.
- **Fix** (`ae438bb`): the push is wrapped, reports `ui.error` and keeps the
  plan intact so the player can retry.

### B8 - Block picker could store air or null (world bug)

- **Cause**: the *pick active block* buttons assigned `Axiom.getActiveBlock()`
  without validation; air/null then became the uniform fill or a soil slot.
- **Fix** (`40919a2`): null and air are rejected.

### B9 - Lock acquisition retried for every ghost block (performance)

- **Cause**: when another Axiom tool held the render lock, `setGhost()` retried
  `acquire()` for up to 6,000 ghost cells per recompute, each throwing an
  exception.
- **Fix** (`96ec039`): one attempt per preview (`ghostDenied`), so the panel
  stays responsive.

### B10 - O(n^2) connectivity checks on large selections (performance)

- **Cause**: `LCorners.purge()` re-flooded the whole remaining trace for every
  candidate corner; `LCorners` also kept the wool set in a mutable static field
  shared by all calls. `Grounding` repeated equivalent scans.
- **Fix** (`4fefed3`): the global guard is `keepsConnected()` - a flood limited
  to the 26 neighbours with early exit - after a single up-front connectivity
  check; the wool set is an explicit parameter. Block output is unchanged.
- **Re-test**: `parityCheck` requires an identical block map and passes; all
  stress suites pass. Wall-clock gain was not measured in game (no client).

### B11 - Nondeterministic stress corpus (test reliability)

- **Cause**: `stupid_stress.py` seeded scenarios from Python's randomized
  `hash()`, so failures were not reproducible between runs.
- **Fix** (`40919a2`): seeds use `zlib.crc32(...)`, stable across processes.

### B12 - CI did not build what was shipped (release pipeline)

- **Cause**: the 47 MB Axiom jar and generated logs were committed; `gradlew`
  was stored without the executable bit; the jar was not gated on parity.
- **Fix** (`40919a2`): Axiom is downloaded by CI, `libs/*.jar`, logs and
  generated reports are ignored, `gradlew` is `100755`, the `build` job depends
  on the `parity` job, and version bump/release only run on `main`/`master`.
  `.github/workflows/build.yml` was rewritten; the artifact is
  `rail-path-tool-v<version>-mc<mc>`.

### B13 - License metadata contradicted the LICENSE file

- **Cause**: `fabric.mod.json` declared `CC0-1.0` and the README repeated it,
  while `LICENSE` is MIT.
- **Fix** (`723c13c`): both now declare MIT; the French mod description was
  translated for consistency.

### B14 - Dead code and debug artefacts

- Removed unused `ProfileSmoothing` and `Tr`, `TrackModel.isWoolTrace`, the
  no-op `IRON_DOOR` branch, `DBG`/`railparity.debug` blocks, the
  `grounding.debug` Gradle property, an unused lang key in 129 files, and a
  dead random-drawing loop in the scene exporter (RNG stream preserved, so the
  corpus is unchanged).

## 3. Investigated and deliberately not changed

- **`NatureDesign` cross-quad gravel/litter**: the Java and Python writers
  agree, the simulator's T-junction render shows the intended gravel bed plus
  litter across the diagonally adjacent crossing, and the parity corpus covers
  it. Changing it would change behaviour without proof of a defect.
- **Static seeded soil RNG** (`ClassicDesign.SOIL_RNG`): soil texture depends on
  build order within a session. Geometry never depends on it (every mix block
  is wool-layable), so parity and gameplay are unaffected; left as-is to avoid
  changing visuals.

## 4. Repository work

- README rewritten in English (features, install, usage, layout, build, tests).
- `.gitignore` rewritten (`.gradle/`, `build/`, `run/`, IDE folders, caches,
  generated parity scenes, pillar report, `libs/*.jar`, logs, `ci/`).
- `sim/ROBUSTNESS.md` rewritten in English with corrected counts
  (752 scenes, 22 historical scenes, current stress numbers).
- Java sources: every comment rewritten in English and trimmed to the
  non-obvious rationale; no generated or restating comments remain.
- `sim/` dead code and unused imports removed; `pyflakes` is clean on the four
  active harnesses and the exporter.

## 5. Current state

- Branch `arena/f944b842-railwaytoolv2`, head `96ec039`.
- CI `Build` + `Java / simulator parity` runs, all green: `9af8e5f`
  (39169994094), `40919a2` (39171382021), `ae438bb` (39173176156), `9a88e53`
  (39173378474), `723c13c` (39173770802), `96ec039` (39173807548).
- Jar produced by CI for `96ec039`:
  `rail-path-tool-v2.0.1-mc1.21.10` (216,116 bytes, Actions artifact); it is
  released only when the version bump runs on `main`.

## 6. Not testable in this environment (honest limits)

The sandbox has no Minecraft client and no way to obtain one: HTTPS to
Mojang/Fabric/Maven/Adoptium resets during the TLS handshake. It also has no
`javac` (only a Temurin 25 JRE inside the Python `jdk4py` package), no
`Xvfb`/`xdotool`, no OpenGL libraries, 2 CPU cores and 3.9 GB of RAM. The game
therefore cannot be launched, let alone driven with synthetic input. Therefore **no in-game test, screenshot,
`xdotool` sequence or `logs/latest.log` observation was performed**. All
verification is compile-level (CI), byte-level (Axiom disassembly) and
block-level (simulator + Java parity harness).

Consequences - the following still need a real client before being declared
fixed in game:

- ghost rendering and clearing, and the render-lock release when switching
  Axiom tools;
- ImGui panel behaviour (presets, sliders, pickers) and `Ctrl+Z` undo;
- world save/load, multiplayer sync, large-selection performance;
- the visual result of B3 on a real rebuild (the block plan is proven
  identical to the simulator, the rendering is not).

Remaining known risks, documented rather than silently changed: `SOIL_RNG`
build-order dependence (cosmetic), `ToolManager` reset on tool switch clearing
the current session, and the reliance on Axiom's `setBlockRenderOverride(..., null)`
to remove ghost cells.
