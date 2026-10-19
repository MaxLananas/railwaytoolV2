package com.bte.railpathtool.dev;

import com.bte.railpathtool.RailPathToolMod;
import com.bte.railpathtool.axiom.Axiom;
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
import java.util.List;
import java.util.Map;

/**
 * In-game self test, disabled unless {@code -Dbte.railtool.selftest=true}.
 *
 * <p>Runs inside a real client against a real integrated server: it drives the
 * production {@link RailwayTool} through the same callbacks Axiom uses, lets
 * the tool build its plan, pushes it with the real Axiom pipeline and reads the
 * blocks back from the client level. The CI job supplies a flat world and
 * captures screenshots; the result is written to {@code selftest-result.txt}.
 */
public final class InGameSelfTest {

    private static final String PROPERTY = "bte.railtool.selftest";
    private static final int LOAD_DELAY = 80;
    private static final int APPLY_DELAY = 100;
    private static final int SNAPSHOT_DELAY = 20;
    private static final int EXIT_DELAY = 8000;

    private static final Deque<Runnable> ACTIONS = new ArrayDeque<>();
    private static final RailwayTool TOOL = new RailwayTool();
    private static final List<String> LOG = new ArrayList<>();

    private static int wait = LOAD_DELAY;
    private static boolean planned = false;
    private static boolean finished = false;

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
            wait = LOAD_DELAY;
            return;
        }
        if (mc.screen != null) {
            LOG.add("dismissed screen " + mc.screen.getClass().getSimpleName());
            mc.setScreen(null);
            return;
        }
        if (!ACTIONS.isEmpty()) {
            try {
                ACTIONS.poll().run();
            } catch (Throwable t) {
                fail(t);
            }
            return;
        }
        if (wait-- > 0) {
            return;
        }
        try {
            if (!planned) {
                planned = true;
                plan(mc);
            } else {
                verify(mc);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void plan(Minecraft mc) {
        int[] zs = {8, 16, 24};
        for (int z : zs) {
            int ground = topSolid(mc, 8, z);
            ACTIONS.add(() -> {
                teleport(mc, 8.5, ground + 1.2, z + 0.5);
                BlockHitResult hit = Axiom.raycastBlock();
                String where = hit == null ? "null"
                        : hit.getType() + "@" + hit.getBlockPos();
                LOG.add("z=" + z + " ground=" + ground + " hit=" + where);
                TOOL.callUseTool();
                LOG.add("control=" + controlSize());
            });
        }
        ACTIONS.add(() -> {
            if (controlSize() != 3) {
                throw new IllegalStateException("expected 3 points, got " + controlSize());
            }
            Camera camera = mc.gameRenderer.getMainCamera();
            TOOL.render(camera, 0.0f, System.nanoTime(), new PoseStack(), new Matrix4f());
            Map<?, ?> plan = (Map<?, ?>) field(TOOL, "plan");
            LOG.add("plan=" + plan.size());
            int shown = 0;
            for (Map.Entry<?, ?> e : plan.entrySet()) {
                long key = ((Long) e.getKey()).longValue();
                LOG.add("block " + pos(key) + "=" + shortName((BlockState) e.getValue()));
                if (++shown >= 6) {
                    break;
                }
            }
            TOOL.callConfirm();
            LOG.add("confirm status=\"" + field(TOOL, "status") + "\"");
            wait = SNAPSHOT_DELAY;
            ACTIONS.add(() -> {
                LOG.add("snapshot=" + snapshot(mc));
                wait = APPLY_DELAY;
            });
        });
        wait = 10;
    }

    private static String snapshot(Minecraft mc) {
        int total = 0;
        List<String> first = new ArrayList<>();
        for (int z = 6; z <= 26; z++) {
            for (int y = -59; y < 0; y++) {
                BlockState st = mc.level.getBlockState(new BlockPos(8, y, z));
                if (!st.isAir()) {
                    total++;
                    if (first.size() < 5) {
                        first.add(y + ":" + shortName(st));
                    }
                }
            }
        }
        return total + " " + first;
    }

    private static void teleport(Minecraft mc, double x, double y, double z) {
        mc.player.setPos(x, y, z);
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.setYRot(0.0f);
        mc.player.setXRot(90.0f);
        mc.player.setOldPosAndRot();
    }

    private static int topSolid(Minecraft mc, int x, int z) {
        int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        for (int y = top + 2; y > mc.level.getMinY(); y--) {
            BlockState st = mc.level.getBlockState(new BlockPos(x, y, z));
            if (!st.isAir() && st.getFluidState().isEmpty()) {
                return y;
            }
        }
        return top;
    }

    private static void verify(Minecraft mc) {
        int cores = 0;
        int walls = 0;
        int supports = 0;
        int airAbove = 0;
        int woolLeft = 0;
        String sample = "";
        for (int z = 8; z <= 24; z++) {
            int y = coreY(mc, z);
            if (y == Integer.MIN_VALUE) {
                continue;
            }
            BlockState core = mc.level.getBlockState(new BlockPos(8, y, z));
            if (!core.is(Blocks.DEAD_BUBBLE_CORAL_WALL_FAN)) {
                continue;
            }
            cores++;
            BlockState below = mc.level.getBlockState(new BlockPos(8, y - 1, z));
            BlockState above = mc.level.getBlockState(new BlockPos(8, y + 1, z));
            if (!below.isAir()) {
                supports++;
            }
            if (above.isAir()) {
                airAbove++;
            }
            for (int dx : new int[]{-1, 1}) {
                BlockState wall = mc.level.getBlockState(new BlockPos(8 + dx, y, z));
                if (wall.is(Blocks.MUD_BRICK_WALL) || wall.is(Blocks.ANDESITE_WALL)) {
                    walls++;
                }
            }
            if (z == 16) {
                sample = shortName(core);
            }
        }
        for (int z = 8; z <= 24; z++) {
            for (int y = mc.level.getMinY(); y < mc.level.getMaxY(); y++) {
                if (mc.level.getBlockState(new BlockPos(8, y, z)).is(Blocks.WHITE_WOOL)) {
                    woolLeft++;
                }
            }
        }
        boolean[] ghost = new boolean[3];
        String ghostText = ghostLock(ghost);
        boolean ghostOk = !ghost[0] || (!ghost[1] && ghost[2]);
        LOG.add("cores=" + cores + " walls=" + walls + " supports=" + supports
                + " airAbove=" + airAbove + " woolLeft=" + woolLeft
                + " ghost=" + ghostText + " sample=" + sample);
        boolean ok = cores >= 8 && walls >= 8 && supports >= cores / 2
                && airAbove >= cores / 2 && woolLeft == 0 && ghostOk;
        LOG.add("ghostOk=" + ghostOk);

        moveToView(mc);
        write(ok ? "PASS" : "FAIL", ok);
        finished = true;
        finish(ok ? "PASS" : "FAIL");
    }

    private static void moveToView(Minecraft mc) {
        int y = coreY(mc, 16);
        double base = y == Integer.MIN_VALUE ? -59 : y;
        mc.player.setPos(8.5, base + 7.0, 16.5);
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.setYRot(0.0f);
        mc.player.setXRot(28.0f);
        mc.player.setOldPosAndRot();
    }

    private static int coreY(Minecraft mc, int z) {
        for (int y = mc.level.getMaxY(); y > mc.level.getMinY(); y--) {
            BlockState st = mc.level.getBlockState(new BlockPos(8, y, z));
            if (st.is(Blocks.DEAD_BUBBLE_CORAL_WALL_FAN)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static String ghostLock(boolean[] out) {
        boolean first = Axiom.acquireChunkRenderOverrider("bte_railtool_selftest");
        boolean second = Axiom.acquireChunkRenderOverrider("bte_railtool_selftest_other");
        out[0] = first;
        out[1] = second;
        Axiom.setBlockRenderOverride(8, -58, 16, Blocks.GOLD_BLOCK.defaultBlockState());
        Axiom.releaseChunkRenderOverrider("bte_railtool_selftest");
        boolean reacquired = Axiom.acquireChunkRenderOverrider("bte_railtool_selftest_2");
        out[2] = reacquired;
        Axiom.releaseChunkRenderOverrider("bte_railtool_selftest_2");
        return "first=" + first + " second=" + second + " reacquired=" + reacquired
                + " probe=" + probeLock();
    }

    /** Reads the real error message instead of the wrapper's boolean. */
    private static String probeLock() {
        try {
            Class<?> c = Class.forName("com.moulberry.axiom.render.ChunkRenderOverrider");
            try {
                c.getMethod("acquire", String.class).invoke(null, "bte_probe");
                c.getMethod("release", String.class).invoke(null, "bte_probe");
                return "acquired";
            } catch (java.lang.reflect.InvocationTargetException e) {
                return String.valueOf(e.getCause());
            }
        } catch (Throwable t) {
            return "unavailable " + t;
        }
    }

    private static int controlSize() {
        return ((List<?>) field(TOOL, "control")).size();
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

    private static String pos(long key) {
        return BlockPos.getX(key) + "," + BlockPos.getY(key) + "," + BlockPos.getZ(key);
    }

    private static String shortName(BlockState state) {
        String s = state.toString();
        return s.startsWith("Block{") ? s.substring(6, Math.min(s.length(), 60)) : s;
    }

    private static void write(String status, boolean ok) {
        String line = "[SELFTEST] " + status + " " + String.join(" | ", LOG);
        RailPathToolMod.LOGGER.info(line);
        try {
            Path p = FabricLoader.getInstance().getGameDir().resolve("selftest-result.txt");
            Files.write(p, List.of(line, status));
        } catch (Exception e) {
            RailPathToolMod.LOGGER.warn("[SELFTEST] cannot write result", e);
        }
    }

    private static void fail(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        LOG.add("exception: " + t);
        write("FAIL", false);
        RailPathToolMod.LOGGER.error("[SELFTEST] exception", t);
        finished = true;
        finish("FAIL");
    }

    private static void finish(String status) {
        RailPathToolMod.LOGGER.info("[SELFTEST] finished {}", status);
        System.out.println("[SELFTEST] finished " + status);
        new Thread(() -> {
            try {
                Thread.sleep(EXIT_DELAY);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "selftest-exit").start();
    }
}
