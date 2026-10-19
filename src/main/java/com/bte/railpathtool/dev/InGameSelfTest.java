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
        out.add(scene(0, "straight", -60, DesignOptions.Style.CLASSIC,
                new int[][]{{8, 8}, {8, 16}, {8, 24}}, new LinkedHashMap<>()));
        out.add(scene(1, "curve", -60, DesignOptions.Style.CLASSIC,
                new int[][]{{32, 8}, {32, 16}, {40, 24}}, new LinkedHashMap<>()));
        out.add(scene(2, "slope", -60, DesignOptions.Style.CLASSIC,
                new int[][]{{56, 8}, {56, 16}, {56, 24}}, terrainRamp()));
        out.add(scene(3, "nature", -60, DesignOptions.Style.NATURE,
                new int[][]{{80, 8}, {80, 16}, {80, 24}}, new LinkedHashMap<>()));
        out.add(scene(4, "zigzag", -60, DesignOptions.Style.CLASSIC,
                new int[][]{{104, 8}, {112, 16}, {104, 24}, {112, 32}},
                new LinkedHashMap<>()));
        out.add(edgeCases(5));
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

    private static Map<Long, String> terrainRamp() {
        Map<Long, String> fills = new LinkedHashMap<>();
        for (int z = 8; z <= 32; z++) {
            int top = -60 + (z - 8) / 6;
            for (int y = top + 1; y <= -56; y++) {
                for (int x = 52; x <= 60; x++) {
                    fills.put(key(x, y, z), "minecraft:stone");
                }
            }
        }
        return fills;
    }

    private static Runnable scene(int index, String name, int ground,
                                  DesignOptions.Style style, int[][] points,
                                  Map<Long, String> terrain) {
        return () -> {
            scene = name;
            sceneCount++;
            LOG.add("scene " + name);
            resetTool();
            setStyle(style);
            density()[0] = 8;
            for (Map.Entry<Long, String> e : terrain.entrySet()) {
                long k = e.getKey();
                command("setblock " + BlockPos.getX(k) + " " + BlockPos.getY(k)
                        + " " + BlockPos.getZ(k) + " " + e.getValue());
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
            pause(terrain.isEmpty() ? 600 : 1500);
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
                renderTool();
                PLAN.clear();
                PLAN.putAll(planMap());
                plannedCores = countCores();
                LOG.add("plan=" + PLAN.size() + " cores=" + plannedCores
                        + " est=" + field(TOOL, "planCount"));
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

    private static Runnable edgeCases(int index) {
        return () -> {
            scene = "edges";
            sceneCount++;
            LOG.add("scene edges");
            resetTool();
            setStyle(DesignOptions.Style.CLASSIC);
            STEPS.add(() -> {
                teleport(Minecraft.getInstance(), 128.5, 100.0, 16.5);
                Minecraft.getInstance().player.setXRot(90.0f);
                pause(300);
            });
            STEPS.add(() -> {
                int before = controlSize();
                boolean handled = TOOL.callUseTool();
                LOG.add("air click handled=" + handled + " control " + before
                        + "->" + controlSize() + " status=\"" + field(TOOL, "status") + "\"");
                check("air-click adds no point", controlSize() == before);
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
            if (e.getValue().is(Blocks.DEAD_BUBBLE_CORAL_WALL_FAN)) {
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
        int components = components(corePositions);
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
        stage("built-" + scene);
        STEPS.add(InGameSelfTest::checkGhost);
        STEPS.add(() -> startScene(nextSceneIndex + 1));
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

    private static int components(Set<Long> cores) {
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
                    for (int dy = -1; dy <= 1; dy++) {
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

    private static int countCores() {
        int n = 0;
        for (BlockState st : PLAN.values()) {
            if (st.is(Blocks.DEAD_BUBBLE_CORAL_WALL_FAN)) {
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
