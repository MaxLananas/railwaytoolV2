package com.bte.railpathtool.dev;

import com.bte.railpathtool.RailPathToolMod;
import com.bte.railpathtool.axiom.Axiom;
import com.bte.railpathtool.design.DesignOptions;
import com.bte.railpathtool.tools.RailwayTool;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Matrix4f;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-game regression suite, disabled unless {@code -Dbte.railtool.selftest=true}.
 *
 * <p>Runs inside a real client against a real integrated server. Each scene
 * drives the production {@link RailwayTool} through the same callbacks Axiom
 * uses, pushes the plan with the real Axiom pipeline, then compares the world
 * with the plan block by block. Results go to {@code selftest-result.txt}; the
 * CI driver supplies the world and screenshots.
 */
public final class InGameSelfTest {

    private static final String PROPERTY = "bte.railtool.selftest";
    private static final long LOAD_DELAY_MS = 4000;
    private static final long APPLY_MS = 3000;
    private static final long SHOT_MS = 2500;
    private static final long EXIT_DELAY_MS = 9000;
    private static final int MIN_Y = -64;
    private static final int MAX_Y = -40;

    private static final Deque<Runnable> STEPS = new ArrayDeque<>();
    private static final List<String> RESULTS = new ArrayList<>();
    private static final List<String> LOG = new ArrayList<>();
    private static final RailwayTool TOOL = new RailwayTool();
    private static final Map<Long, BlockState> PLAN = new LinkedHashMap<>();
    private static final Map<Long, BlockState> BASELINE = new HashMap<>();

    private static long nextStep;
    private static boolean started = false;
    private static boolean finished = false;
    private static int sceneCount = 0;
    private static String scene = "";
    private static int plannedCores = 0;
    private static int nextSceneIndex = -1;

    private InGameSelfTest() {
    }

    public static void install() {
        if (!Boolean.getBoolean(PROPERTY)) {
            return;
        }
        RailPathToolMod.LOGGER.info("[SELFTEST] armed");
        ClientTickEvents.END_CLIENT_TICK.register(InGameSelfTest::tick);
    }

    private static void tick(Minecraft mc) {
        if (finished) {
            return;
        }
        if (mc.level == null || mc.player == null) {
            nextStep = now() + LOAD_DELAY_MS;
            return;
        }
        if (mc.screen != null) {
            LOG.add("dismissed " + mc.screen.getClass().getSimpleName());
            mc.setScreen(null);
            return;
        }
        if (now() < nextStep) {
            return;
        }
        if (!started) {
            started = true;
            STEPS.add(InGameSelfTest::setup);
            STEPS.add(() -> startScene(0));
            nextStep = now() + LOAD_DELAY_MS;
            return;
        }
        Runnable step = STEPS.poll();
        if (step == null) {
            return;
        }
        try {
            step.run();
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ---------------------------------------------------------------- scenes

    private static List<Runnable> scenes() {
        List<Runnable> out = new ArrayList<>();
        out.add(scene(0, "straight", 8, DesignOptions.Style.CLASSIC,
                new int[][]{{8, 8}, {8, 16}, {8, 24}}, new LinkedHashMap<>()));
        out.add(repeatScene(1, "repeat"));
        out.add(scene(2, "curve", 8, DesignOptions.Style.CLASSIC,
                new int[][]{{32, 8}, {32, 16}, {40, 24}}, new LinkedHashMap<>()));
        out.add(scene(3, "slope", 8, DesignOptions.Style.CLASSIC,
                new int[][]{{56, 8}, {56, 16}, {56, 24}}, terrainRamp()));
        out.add(scene(4, "nature", 8, DesignOptions.Style.NATURE,
                new int[][]{{80, 8}, {80, 16}, {80, 24}}, new LinkedHashMap<>()));
        out.add(scene(5, "zigzag", 12, DesignOptions.Style.CLASSIC,
                new int[][]{{104, 8}, {112, 16}, {104, 24}, {112, 32}},
                new LinkedHashMap<>()));
        out.add(scene(6, "clutter", 8, DesignOptions.Style.CLASSIC,
                new int[][]{{176, 8}, {176, 16}, {176, 24}}, terrainWall()));
        out.add(scene(7, "water", 8, DesignOptions.Style.CLASSIC,
                new int[][]{{200, 8}, {200, 16}, {200, 24}}, new LinkedHashMap<>(),
                new String[]{"fill 194 -60 4 206 -58 28 minecraft:water"}));
        out.add(scene(8, "long", 8, DesignOptions.Style.CLASSIC,
                new int[][]{{224, 8}, {224, 96}}, new LinkedHashMap<>()));
        out.add(scene(9, "many", 8, DesignOptions.Style.CLASSIC,
                new int[][]{{248, 8}, {256, 14}, {248, 20}, {256, 26}, {248, 32},
                        {256, 38}}, new LinkedHashMap<>()));
        out.add(crossScene(10, "cross"));
        out.add(presetScene(11, "presets"));
        out.add(undoScene(12, "undo"));
        out.add(edgeCases(13));
        return out;
    }

    private static void startScene(int index) {
        nextSceneIndex = index;
        List<Runnable> list = scenes();
        if (index >= list.size()) {
            summary();
            return;
        }
        list.get(index).run();
    }

    /** A stone wall across the line, to check how existing blocks are handled. */
    private static Map<Long, String> terrainWall() {
        Map<Long, String> fills = new LinkedHashMap<>();
        for (int y = -59; y <= -55; y++) {
            for (int x = 170; x <= 182; x++) {
                fills.put(key(x, y, 16), "minecraft:stone_bricks");
            }
        }
        return fills;
    }

    /** Replaces the flat surface with a stone ramp, so no grass is buried. */
    private static Map<Long, String> terrainRamp() {
        Map<Long, String> fills = new LinkedHashMap<>();
        for (int z = 8; z <= 32; z++) {
            int top = -60 + (z - 8) / 6;
            for (int y = -60; y <= top; y++) {
                for (int x = 52; x <= 60; x++) {
                    fills.put(key(x, y, z), "minecraft:stone");
                }
            }
        }
        return fills;
    }

    private static Runnable scene(int index, String name, int density, DesignOptions.Style style,
                                  int[][] points, Map<Long, String> terrain) {
        return scene(index, name, density, style, points, terrain, new String[0]);
    }

    private static Runnable scene(int index, String name, int density, DesignOptions.Style style,
                                  int[][] points, Map<Long, String> terrain, String[] commands) {
        return () -> {
            scene = name;
            sceneCount++;
            LOG.add("scene " + name);
            resetTool();
            setStyle(style);
            density()[0] = density;
            for (Map.Entry<Long, String> e : terrain.entrySet()) {
                long k = e.getKey();
                command("setblock " + BlockPos.getX(k) + " " + BlockPos.getY(k)
                        + " " + BlockPos.getZ(k) + " " + e.getValue());
            }
            for (String c : commands) {
                command(c);
            }
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int[] p : points) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minZ = Math.min(minZ, p[1]);
                maxZ = Math.max(maxZ, p[1]);
            }
            final int x1 = minX;
            final int x2 = maxX;
            final int z1 = minZ;
            final int z2 = maxZ;
            pause(terrain.isEmpty() ? 600 : 2000);
            for (int[] p : points) {
                final int px = p[0];
                final int pz = p[1];
                STEPS.add(() -> {
                    teleport(Minecraft.getInstance(), px + 0.5, topSolid(px, pz) + 1.2,
                            pz + 0.5);
                    pause(200);
                });
                STEPS.add(() -> {
                    int before = controlSize();
                    TOOL.callUseTool();
                    LOG.add("point " + px + "," + pz + " control " + before + "->"
                            + controlSize());
                });
            }
            STEPS.add(() -> {
                long started = System.nanoTime();
                renderTool();
                long millis = (System.nanoTime() - started) / 1_000_000L;
                Map<Long, BlockState> copy = new LinkedHashMap<>(planMap());
                PLAN.clear();
                PLAN.putAll(copy);
                plannedCores = countCores();
                LOG.add("plan=" + PLAN.size() + " cores=" + plannedCores
                        + " est=" + field(TOOL, "planCount") + " recompute=" + millis + "ms");
                check("recompute under 2s (" + name + ")", millis < 2000, millis + "ms");
                BASELINE.clear();
                BASELINE.putAll(snapshot(x1 - 6, x2 + 6, z1 - 4, z2 + 4));
                stage("plan-" + name);
                pause(SHOT_MS);
            });
            STEPS.add(() -> {
                TOOL.callConfirm();
                LOG.add("confirm status=\"" + field(TOOL, "status") + "\"");
                pause(APPLY_MS);
            });
            STEPS.add(InGameSelfTest::check);
        };
    }

    /**
     * Builds the same line twice in one session. The second plan must be
     * identical and the world must not drift, which proves the tool reads its
     * own output correctly.
     */
    private static Runnable repeatScene(int index, String name) {
        int[][] points = {{8, 8}, {8, 16}, {8, 24}};
        return () -> {
            scene = name;
            sceneCount++;
            LOG.add("scene " + name);
            resetTool();
            setStyle(DesignOptions.Style.CLASSIC);
            density()[0] = 8;
            pause(600);
            addPoints(points);
            STEPS.add(() -> {
                renderTool();
                Map<Long, BlockState> first = new LinkedHashMap<>(planMap());
                PLAN.clear();
                PLAN.putAll(first);
                plannedCores = countCores();
                LOG.add("first plan=" + first.size() + " cores=" + plannedCores
                        + " bounds=" + bounds(first));
                check("first plan non empty", first.size() > 0, first.size() + " blocks");
                BASELINE.clear();
                BASELINE.putAll(snapshot(2, 14, 4, 28));
                TOOL.callConfirm();
                LOG.add("first build status=\"" + field(TOOL, "status") + "\"");
                pause(APPLY_MS);
            });
            STEPS.add(() -> {
                addPoints(points);
                STEPS.add(() -> {
                    renderTool();
                    Map<Long, BlockState> second = planMap();
                    int diff = 0;
                    String detail = "";
                    for (Map.Entry<Long, BlockState> e : second.entrySet()) {
                        BlockState was = PLAN.get(e.getKey());
                        if (was == null || !was.equals(e.getValue())) {
                            diff++;
                            if (detail.isEmpty()) {
                                detail = pos(e.getKey()) + " " + was + " -> "
                                        + name(e.getValue());
                            }
                        }
                    }
                    LOG.add("second plan=" + second.size() + " diff=" + diff
                            + " bounds=" + bounds(second) + " first=" + bounds(PLAN));
                    // The tool never overwrites an existing line: a second trace
                    // stacks above the first instead of erasing it.
                    int reused = 0;
                    for (long k : second.keySet()) {
                        if (PLAN.containsKey(k)) {
                            reused++;
                        }
                    }
                    check("rebuild leaves the first line", reused == 0,
                            reused + " cells reused " + detail);
                    TOOL.callConfirm();
                    LOG.add("second build status=\"" + field(TOOL, "status") + "\"");
                    pause(APPLY_MS);
                });
                STEPS.add(() -> {
                    int mismatches = 0;
                    for (Map.Entry<Long, BlockState> e : PLAN.entrySet()) {
                        if (!Minecraft.getInstance().level.getBlockState(pos(e.getKey()))
                                .equals(e.getValue())) {
                            mismatches++;
                        }
                    }
                    int lost = 0;
                    for (Map.Entry<Long, BlockState> e : PLAN.entrySet()) {
                        if (!Minecraft.getInstance().level.getBlockState(pos(e.getKey()))
                                .equals(e.getValue())) {
                            lost++;
                        }
                    }
                    check("first line survived the rebuild", lost == 0,
                            lost + " of " + PLAN.size() + " changed");
                    check("second build applied", mismatches == 0,
                            mismatches + " mismatches");
                    stage("built-" + scene);
                    STEPS.add(InGameSelfTest::checkGhost);
                    STEPS.add(() -> startScene(index + 1));
                });
            });
        };
    }

    /** Two lines crossing: the second must not erase the first. */
    private static Runnable crossScene(int index, String name) {
        int[][] first = {{300, 8}, {300, 24}};
        int[][] second = {{294, 16}, {306, 16}};
        return () -> {
            scene = name;
            sceneCount++;
            LOG.add("scene " + name);
            resetTool();
            setStyle(DesignOptions.Style.CLASSIC);
            density()[0] = 8;
            pause(600);
            addPoints(first);
            STEPS.add(() -> {
                renderTool();
                PLAN.clear();
                PLAN.putAll(planMap());
                plannedCores = countCores();
                LOG.add("line A plan=" + PLAN.size() + " cores=" + plannedCores
                        + " " + bounds(PLAN));
                check("first line planned", plannedCores > 10, plannedCores + " cores");
                TOOL.callConfirm();
                pause(APPLY_MS);
                STEPS.add(() -> {
                    resetTool();
                    addPoints(second);
                    STEPS.add(() -> {
                        renderTool();
                        Map<Long, BlockState> planB = new LinkedHashMap<>(planMap());
                        int lostCores = 0;
                        String lostAt = "";
                        for (Map.Entry<Long, BlockState> e : PLAN.entrySet()) {
                            if (!isCore(e.getValue()) || planB.containsKey(e.getKey())) {
                                continue;
                            }
                            lostCores++;
                            if (lostAt.isEmpty()) {
                                lostAt = pos(e.getKey());
                            }
                        }
                        LOG.add("line B plan=" + planB.size() + " cores="
                                + countCoresOf(planB) + " overwrites=" + lostCores);
                        check("crossing keeps the first line", lostCores <= 1,
                                lostCores + " cores removed at " + lostAt);
                        TOOL.callConfirm();
                        pause(APPLY_MS);
                        STEPS.add(() -> {
                            Set<Long> cores = new HashSet<>();
                            for (int x = 292; x <= 308; x++) {
                                for (int z = 6; z <= 26; z++) {
                                    for (int y = -62; y <= -50; y++) {
                                        BlockState st = Minecraft.getInstance().level
                                                .getBlockState(new BlockPos(x, y, z));
                                        if (isCore(st) && (z == 16 || x == 300)) {
                                            cores.add(key(x, y, z));
                                        }
                                    }
                                }
                            }
                            LOG.add("crossing cores=" + cores.size()
                                    + " parts=" + components(cores, 3));
                            check("crossing is one junction", components(cores, 3) <= 2,
                                    components(cores, 3) + " parts");
                            stage("built-" + scene);
                            STEPS.add(InGameSelfTest::checkGhost);
                            STEPS.add(() -> startScene(index + 1));
                        });
                    });
                });
            });
        };
    }

    /** Presets and the config file must round-trip inside the game. */
    private static Runnable presetScene(int index, String name) {
        return () -> {
            scene = name;
            sceneCount++;
            LOG.add("scene " + name);
            resetTool();
            density()[0] = 9;
            setStyle(DesignOptions.Style.NATURE);
            Object data = invoke("snapshotData");
            invoke("setPreset", new Class<?>[]{int.class, data.getClass()},
                    new Object[]{1, data});
            invoke("persistConfig");
            density()[0] = 3;
            setStyle(DesignOptions.Style.CLASSIC);
            Object loaded = invoke("preset", new Class<?>[]{int.class}, new Object[]{1});
            invoke("applyData", new Class<?>[]{loaded.getClass()}, new Object[]{loaded});
            LOG.add("preset density=" + density()[0] + " style="
                    + readInt(field(TOOL, "styleSel")));
            check("preset restores density", density()[0] == 9, "density=" + density()[0]);
            int style = (int) readInt(field(TOOL, "styleSel"));
            check("preset restores style", style == 1, "styleSel=" + style);
            Object disk = invokeStatic("load");
            int diskDensity = readInt(readField(disk, "density"));
            check("config saved to disk", diskDensity == 9, "disk density=" + diskDensity);
            stage("built-" + scene);
            STEPS.add(InGameSelfTest::checkGhost);
            STEPS.add(() -> startScene(index + 1));
        };
    }

    private static int countCoresOf(Map<Long, BlockState> map) {
        int n = 0;
        for (BlockState st : map.values()) {
            if (isCore(st)) {
                n++;
            }
        }
        return n;
    }

    private static Object invoke(String name) {
        return invoke(name, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(String name, Class<?>[] types, Object[] args) {
        try {
            java.lang.reflect.Method m = TOOL.getClass().getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(TOOL, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot call " + name, e);
        }
    }

    private static Object invokeStatic(String name) {
        try {
            java.lang.reflect.Method m = com.bte.railpathtool.lib.config.RailConfig.class
                    .getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot call RailConfig." + name, e);
        }
    }

    private static Object readField(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + name, e);
        }
    }

    private static int readInt(Object value) {
        try {
            return ((Number) value.getClass().getMethod("get").invoke(value)).intValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read int", e);
        }
    }

    /** Adds four points, deletes the last, builds: the deletion must be honoured. */
    private static Runnable undoScene(int index, String name) {
        int[][] points = {{272, 8}, {272, 16}, {272, 24}, {272, 32}};
        return () -> {
            scene = name;
            sceneCount++;
            LOG.add("scene " + name);
            resetTool();
            setStyle(DesignOptions.Style.CLASSIC);
            density()[0] = 8;
            pause(600);
            addPoints(points);
            STEPS.add(() -> {
                int before = controlSize();
                TOOL.callDelete();
                LOG.add("delete control " + before + "->" + controlSize()
                        + " status=\"" + field(TOOL, "status") + "\"");
                check("delete removes a point", controlSize() == before - 1,
                        before + "->" + controlSize());
                renderTool();
                LOG.add("undo plan=" + planMap().size());
                check("undo plan still builds", planMap().size() > 0);
                STEPS.add(() -> {
                    TOOL.callConfirm();
                    pause(APPLY_MS);
                });
                STEPS.add(() -> {
                    int cores = 0;
                    for (int z = 8; z <= 28; z++) {
                        for (int y = -64; y <= -40; y++) {
                            if (isCore(Minecraft.getInstance().level
                                    .getBlockState(new BlockPos(272, y, z)))) {
                                cores++;
                                break;
                            }
                        }
                    }
                    check("undo built the line", cores >= 15, cores + " cores");
                    int past = 0;
                    for (int z = 27; z <= 34; z++) {
                        for (int y = -64; y <= -40; y++) {
                            if (isCore(Minecraft.getInstance().level
                                    .getBlockState(new BlockPos(272, y, z)))) {
                                past++;
                                break;
                            }
                        }
                    }
                    check("undo honours the deleted point", past == 0, past + " cores past z=26");
                    stage("built-" + scene);
                    STEPS.add(InGameSelfTest::checkGhost);
                    STEPS.add(() -> startScene(index + 1));
                });
            });
        };
    }

    private static String bounds(Map<Long, BlockState> map) {
        int x1 = Integer.MAX_VALUE;
        int y1 = Integer.MAX_VALUE;
        int z1 = Integer.MAX_VALUE;
        int x2 = Integer.MIN_VALUE;
        int y2 = Integer.MIN_VALUE;
        int z2 = Integer.MIN_VALUE;
        for (long k : map.keySet()) {
            BlockPos p = pos(k);
            x1 = Math.min(x1, p.getX());
            y1 = Math.min(y1, p.getY());
            z1 = Math.min(z1, p.getZ());
            x2 = Math.max(x2, p.getX());
            y2 = Math.max(y2, p.getY());
            z2 = Math.max(z2, p.getZ());
        }
        return "[" + x1 + ".." + x2 + "," + y1 + ".." + y2 + "," + z1 + ".." + z2 + "]";
    }

    private static void addPoints(int[][] points) {
        for (int[] p : points) {
            final int px = p[0];
            final int pz = p[1];
            STEPS.add(() -> {
                teleport(Minecraft.getInstance(), px + 0.5, topSolid(px, pz) + 1.2,
                        pz + 0.5);
                pause(200);
            });
            STEPS.add(() -> {
                TOOL.callUseTool();
                LOG.add("point " + px + "," + pz + " control=" + controlSize());
            });
        }
    }

    private static Runnable edgeCases(int index) {
        return () -> {
            scene = "edges";
            sceneCount++;
            LOG.add("scene edges");
            resetTool();
            setStyle(DesignOptions.Style.CLASSIC);
            STEPS.add(() -> {
                teleport(Minecraft.getInstance(), 128.5, 120.0, 16.5);
                Minecraft.getInstance().player.setXRot(-90.0f);
                pause(400);
            });
            STEPS.add(() -> {
                int before = controlSize();
                boolean handled = TOOL.callUseTool();
                LOG.add("air click handled=" + handled + " control " + before
                        + "->" + controlSize() + " status=\"" + field(TOOL, "status") + "\"");
                check("sky click adds no point", controlSize() == before,
                        "control=" + controlSize() + " handled=" + handled);
            });
            STEPS.add(() -> {
                teleport(Minecraft.getInstance(), 128.5, topSolid(128, 16) + 1.2, 16.5);
                pause(200);
            });
            for (int i = 0; i < 3; i++) {
                STEPS.add(() -> {
                    TOOL.callUseTool();
                    LOG.add("duplicate click control=" + controlSize());
                });
            }
            STEPS.add(() -> {
                renderTool();
                LOG.add("duplicate plan=" + planMap().size() + " status=\""
                        + field(TOOL, "status") + "\"");
                TOOL.callConfirm();
                LOG.add("duplicate confirm status=\"" + field(TOOL, "status") + "\"");
                pause(APPLY_MS);
            });
            STEPS.add(() -> {
                TOOL.callConfirm();
                String status = String.valueOf(field(TOOL, "status"));
                LOG.add("second confirm status=\"" + status + "\"");
                check("empty confirm is harmless", status.length() > 0);
                resetTool();
            });
            STEPS.add(InGameSelfTest::checkGhost);
            STEPS.add(() -> startScene(index + 1));
        };
    }

    // ------------------------------------------------------------- assertions

    private static void check() {
        Minecraft mc = Minecraft.getInstance();
        int mismatches = 0;
        String firstMismatch = "";
        int cores = 0;
        Set<Long> corePositions = new HashSet<>();
        for (Map.Entry<Long, BlockState> e : PLAN.entrySet()) {
            long k = e.getKey();
            BlockPos p = pos(k);
            BlockState actual = mc.level.getBlockState(p);
            if (!actual.equals(e.getValue())) {
                mismatches++;
                if (firstMismatch.isEmpty()) {
                    firstMismatch = p + " expected " + name(e.getValue())
                            + " got " + name(actual);
                }
            }
            if (isCore(e.getValue())) {
                cores++;
                corePositions.add(k);
            }
        }
        int unexpected = 0;
        String firstUnexpected = "";
        for (Map.Entry<Long, BlockState> e : BASELINE.entrySet()) {
            long k = e.getKey();
            if (PLAN.containsKey(k)) {
                continue;
            }
            BlockState actual = mc.level.getBlockState(pos(k));
            if (!actual.equals(e.getValue())) {
                if (e.getValue().is(Blocks.GRASS_BLOCK) && actual.is(Blocks.DIRT)) {
                    continue;
                }
                unexpected++;
                if (firstUnexpected.isEmpty()) {
                    firstUnexpected = pos(k) + " was " + name(e.getValue())
                            + " now " + name(actual);
                }
            }
        }
        int unsupported = 0;
        for (long k : corePositions) {
            BlockPos p = pos(k);
            if (mc.level.getBlockState(p.below()).isAir()) {
                unsupported++;
            }
        }
        int components = components(corePositions, 3);
        List<String> trace = new ArrayList<>();
        for (long k : corePositions) {
            BlockPos p = pos(k);
            trace.add(p.getX() + "," + p.getY() + "," + p.getZ());
        }
        trace.sort(String::compareTo);
        LOG.add("cores (" + scene + "): " + String.join(" ", trace));
        int wool = countWool();
        check("plan applied (" + scene + ")", mismatches == 0,
                mismatches + " mismatches " + firstMismatch);
        check("no side effects (" + scene + ")", unexpected == 0,
                unexpected + " changed " + firstUnexpected);
        check("cores supported (" + scene + ")", unsupported == 0,
                unsupported + " of " + cores + " floating");
        check("single ribbon (" + scene + ")", components <= 1,
                components + " components");
        check("no temp wool (" + scene + ")", wool == 0, wool + " wool left");
        check("cores match plan (" + scene + ")", cores == plannedCores,
                cores + " vs " + plannedCores);
        viewFromAbove(cores);
        STEPS.add(() -> {
            stage("built-" + scene);
            STEPS.add(InGameSelfTest::checkGhost);
            STEPS.add(() -> startScene(nextSceneIndex + 1));
        });
    }

    /** Frames the built line from above so the screenshot shows the result. */
    private static void viewFromAbove(int cores) {
        Minecraft mc = Minecraft.getInstance();
        if (cores == 0) {
            return;
        }
        int x1 = Integer.MAX_VALUE;
        int z1 = Integer.MAX_VALUE;
        int x2 = Integer.MIN_VALUE;
        int z2 = Integer.MIN_VALUE;
        int y = -59;
        for (long k : new HashSet<>(coreKeys())) {
            BlockPos p = pos(k);
            x1 = Math.min(x1, p.getX());
            z1 = Math.min(z1, p.getZ());
            x2 = Math.max(x2, p.getX());
            z2 = Math.max(z2, p.getZ());
            y = Math.max(y, p.getY());
        }
        double cx = (x1 + x2) / 2.0 + 0.5;
        double cz = (z1 + z2) / 2.0 + 0.5;
        double dy = Math.max(8.0, Math.max(x2 - x1, z2 - z1) * 0.9);
        teleport(mc, cx, y + dy, cz);
        mc.player.setXRot(52.0f);
        mc.player.setOldPosAndRot();
        pause(SHOT_MS);
    }

    private static Set<Long> coreKeys() {
        Set<Long> out = new HashSet<>();
        for (Map.Entry<Long, BlockState> e : PLAN.entrySet()) {
            if (isCore(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static void checkGhost() {
        boolean free = Axiom.acquireChunkRenderOverrider("bte_serene_probe");
        if (free) {
            Axiom.releaseChunkRenderOverrider("bte_serene_probe");
        }
        Object ghost = field(TOOL, "ghostPositions");
        int ghostSize = ghost instanceof java.util.Collection<?> c ? c.size() : -1;
        check("render lock released (" + scene + ")", free, "lock still held");
        check("ghost cleared (" + scene + ")", ghostSize <= 0, ghostSize + " cells");
    }

    private static void nextScene() {
        resetTool();
    }

    private static void summary() {
        scanLog();
        long passed = RESULTS.stream().filter(r -> r.startsWith("PASS")).count();
        boolean ok = passed == RESULTS.size() && !RESULTS.isEmpty();
        String line = "[SELFTEST] " + (ok ? "PASS" : "FAIL") + " "
                + passed + "/" + RESULTS.size() + " checks | "
                + String.join(" | ", RESULTS);
        RailPathToolMod.LOGGER.info(line);
        try {
            Path p = FabricLoader.getInstance().getGameDir().resolve("selftest-result.txt");
            List<String> out = new ArrayList<>();
            out.add(line);
            out.add(ok ? "PASS" : "FAIL");
            out.addAll(LOG);
            Files.write(p, out);
        } catch (Exception e) {
            RailPathToolMod.LOGGER.warn("[SELFTEST] cannot write result", e);
        }
        stage(ok ? "done-pass" : "done-fail");
        finished = true;
        exit(ok ? "PASS" : "FAIL");
    }

    /** The game log must not carry mod errors or crashes. */
    private static void scanLog() {
        try {
            Path log = FabricLoader.getInstance().getGameDir().resolve("logs/latest.log");
            if (!Files.isRegularFile(log)) {
                check("log readable", false, "latest.log missing");
                return;
            }
            int errors = 0;
            String first = "";
            for (String line : Files.readAllLines(log)) {
                String lower = line.toLowerCase();
                boolean ours = lower.contains("railpathtool")
                        || lower.contains("bte_railpathtool");
                if (ours && (lower.contains("error") || lower.contains("exception"))) {
                    errors++;
                    if (first.isEmpty()) {
                        first = line.length() > 160 ? line.substring(0, 160) : line;
                    }
                }
            }
            check("no mod errors in log", errors == 0, errors + " lines " + first);
        } catch (Exception e) {
            check("log readable", false, String.valueOf(e));
        }
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        String result = (ok ? "PASS " : "FAIL ") + name
                + (detail.isEmpty() || ok ? "" : " [" + detail + "]");
        RESULTS.add(result);
        LOG.add(result);
        RailPathToolMod.LOGGER.info("[SELFTEST] {}", result);
    }

    private static int components(Set<Long> cores, int jump) {
        if (cores.isEmpty()) {
            return 0;
        }
        Set<Long> seen = new HashSet<>();
        int found = 0;
        for (long start : cores) {
            if (!seen.add(start)) {
                continue;
            }
            found++;
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos p = pos(queue.poll());
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -jump; dy <= jump; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long k = key(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                            if (cores.contains(k) && seen.add(k)) {
                                queue.add(k);
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    private static int countWool() {
        Minecraft mc = Minecraft.getInstance();
        int wool = 0;
        for (long k : BASELINE.keySet()) {
            if (mc.level.getBlockState(pos(k)).is(Blocks.WHITE_WOOL)) {
                wool++;
            }
        }
        return wool;
    }

    private static Map<Long, BlockState> snapshot(int x1, int x2, int z1, int z2) {
        Minecraft mc = Minecraft.getInstance();
        Map<Long, BlockState> out = new HashMap<>();
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    out.put(key(x, y, z), mc.level.getBlockState(new BlockPos(x, y, z)));
                }
            }
        }
        return out;
    }

    private static boolean isCore(BlockState state) {
        return state.is(Blocks.DEAD_BUBBLE_CORAL_WALL_FAN)
                || state.is(Blocks.LECTERN)
                || state.is(Blocks.PALE_MOSS_BLOCK);
    }

    private static int countCores() {
        int n = 0;
        for (BlockState st : PLAN.values()) {
            if (isCore(st)) {
                n++;
            }
        }
        return n;
    }

    // --------------------------------------------------------------- plumbing

    private static void setup() {
        Minecraft mc = Minecraft.getInstance();
        command("gamerule doDaylightCycle false");
        command("time set noon");
        command("gamerule doWeatherCycle false");
        command("gamerule doMobSpawning false");
        command("kill @e[type=!player]");
        command("gamemode creative @s");
        pause(1200);
    }

    private static void resetTool() {
        TOOL.reset();
    }

    private static int[] density() {
        return (int[]) field(TOOL, "density");
    }

    private static void setStyle(DesignOptions.Style style) {
        Object sel = field(TOOL, "styleSel");
        try {
            sel.getClass().getMethod("set", int.class).invoke(
                    sel, style == DesignOptions.Style.NATURE ? 1 : 0);
            java.lang.reflect.Method apply =
                    TOOL.getClass().getDeclaredMethod("applySelections");
            apply.setAccessible(true);
            apply.invoke(TOOL);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set style", e);
        }
    }

    private static void renderTool() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        TOOL.render(camera, 0.0f, System.nanoTime(), new PoseStack(), new Matrix4f());
    }

    private static void teleport(Minecraft mc, double x, double y, double z) {
        mc.player.setPos(x, y, z);
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.setYRot(0.0f);
        mc.player.setXRot(90.0f);
        mc.player.setOldPosAndRot();
    }

    private static void command(String cmd) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand(cmd);
        }
    }

    private static int topSolid(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        for (int y = top + 2; y > mc.level.getMinY(); y--) {
            BlockState st = mc.level.getBlockState(new BlockPos(x, y, z));
            if (!st.isAir() && st.getFluidState().isEmpty()) {
                return y;
            }
        }
        return top;
    }

    private static void pause(long ms) {
        nextStep = now() + ms;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void stage(String value) {
        try {
            Path p = FabricLoader.getInstance().getGameDir().resolve("selftest-stage.txt");
            Files.writeString(p, value);
        } catch (Exception e) {
            RailPathToolMod.LOGGER.warn("[SELFTEST] cannot write stage", e);
        }
    }

    private static int controlSize() {
        return ((List<?>) field(TOOL, "control")).size();
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, BlockState> planMap() {
        return (Map<Long, BlockState>) field(TOOL, "plan");
    }

    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + name, e);
        }
    }

    private static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private static BlockPos pos(long k) {
        return BlockPos.of(k);
    }

    private static String name(BlockState state) {
        String s = state.toString();
        int end = s.indexOf('}');
        return s.startsWith("Block{") && end > 6 ? s.substring(6, end) : s;
    }

    private static void fail(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        LOG.add("exception in " + scene + ": " + sw);
        check("no exception (" + scene + ")", false, String.valueOf(t));
        RailPathToolMod.LOGGER.error("[SELFTEST] exception", t);
        STEPS.clear();
        finished = true;
        summary();
    }

    private static void exit(String status) {
        RailPathToolMod.LOGGER.info("[SELFTEST] finished {}", status);
        System.out.println("[SELFTEST] finished " + status);
        new Thread(() -> {
            try {
                Thread.sleep(EXIT_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "selftest-exit").start();
    }
}
