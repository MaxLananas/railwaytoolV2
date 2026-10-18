package com.bte.railpathtool.dev;

import com.bte.railpathtool.RailPathToolMod;
import com.bte.railpathtool.axiom.Axiom;
import com.bte.railpathtool.tools.RailwayTool;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-game self test, disabled unless {@code -Dbte.railtool.selftest=true}.
 *
 * <p>Runs inside a real client against a real integrated server: it drives the
 * production {@link RailwayTool} through its public callbacks, builds a short
 * line through Axiom and reads the blocks back from the client level. The
 * automated CI job supplies a world and screenshots; the result is written to
 * {@code selftest-result.txt} in the game directory.
 */
public final class InGameSelfTest {

    private static final String PROPERTY = "bte.railtool.selftest";
    private static final int LOAD_DELAY = 80;
    private static final int APPLY_DELAY = 100;

    private static final Deque<Runnable> ACTIONS = new ArrayDeque<>();
    private static final RailwayTool TOOL = new RailwayTool();
    private static final List<String> LOG = new ArrayList<>();

    private static int phase = 0;
    private static int wait = LOAD_DELAY;
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
        if (mc.level == null || mc.player == null || mc.screen != null) {
            wait = LOAD_DELAY;
            return;
        }
        if (wait-- > 0) {
            return;
        }
        if (!ACTIONS.isEmpty()) {
            try {
                ACTIONS.poll().run();
            } catch (Throwable t) {
                fail(t);
                return;
            }
            return;
        }
        try {
            if (phase == 0) {
                phase = 1;
                planBuild(mc);
            } else if (phase == 1) {
                phase = 2;
                verify(mc);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void planBuild(Minecraft mc) {
        int[] zs = {8, 16, 24};
        for (int z : zs) {
            int ground = topSolid(mc, 8, z);
            ACTIONS.add(() -> teleport(mc, 8.5, ground + 1.2, z + 0.5));
            ACTIONS.add(() -> {
                boolean added = TOOL.callUseTool();
                LOG.add("point z=" + z + " ground=" + ground + " added=" + added);
                if (!added) {
                    throw new IllegalStateException("callUseTool refused at z=" + z);
                }
            });
        }
        ACTIONS.add(() -> {
            TOOL.callConfirm();
            LOG.add("confirm sent");
        });
        LOG.add("waiting " + APPLY_DELAY + " ticks for the server");
        wait = APPLY_DELAY;
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
        for (int z = 9; z <= 23; z++) {
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
                sample = core.toString();
            }
        }
        for (int z = 9; z <= 23; z++) {
            for (int y = mc.level.getMinY(); y < mc.level.getMaxY(); y++) {
                if (mc.level.getBlockState(new BlockPos(8, y, z)).is(Blocks.WHITE_WOOL)) {
                    woolLeft++;
                }
            }
        }
        boolean[] ghost = new boolean[3];
        String ghostText = ghostLock(ghost);
        LOG.add("cores=" + cores + " walls=" + walls + " supports=" + supports
                + " airAbove=" + airAbove + " woolLeft=" + woolLeft
                + " ghost=" + ghostText + " sample=" + sample);
        // If the lock is free, a second acquisition must fail and the lock must
        // be reusable after release. If another tool owns it, the wrapper must
        // simply report false without throwing.
        boolean ghostOk = !ghost[0] || (!ghost[1] && ghost[2]);
        boolean ok = cores >= 10 && walls >= 8 && supports >= cores
                && airAbove >= cores / 2 && woolLeft == 0 && ghostOk;
        write(ok ? "PASS" : "FAIL", ok);
        finished = true;
        finish(ok ? "PASS" : "FAIL");
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
        return "first=" + first + " second=" + second + " reacquired=" + reacquired;
    }

    private static void write(String status, boolean ok) {
        String line = "[SELFTEST] " + status + " " + String.join(" | ", LOG);
        RailPathToolMod.LOGGER.info(line);
        try {
            Path p = FabricLoader.getInstance().getGameDir().resolve("selftest-result.txt");
            List<String> out = new ArrayList<>();
            out.add(line);
            out.add(status);
            Files.write(p, out);
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
                Thread.sleep(3000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "selftest-exit").start();
    }
}
