package com.bte.railpathtool.dev;

import com.bte.railpathtool.RailPathToolMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Screenshot helper, disabled unless {@code -Dbte.railtool.ui=true}.
 *
 * <p>Axiom's editor is driven by the mouse, which a headless test cannot use
 * reliably, so this selects the rail tool through Axiom's own tool manager and
 * skips the introductory tutorial. The panel that then appears is Axiom's real
 * tool options window.
 */
public final class EditorShot {

    private static final String PROPERTY = "bte.railtool.ui";
    private static final int DELAY_TICKS = 100;

    private static int ticks;
    private static boolean tutorialSkipped;
    private static boolean selected;

    private EditorShot() {
    }

    public static void install() {
        if (!Boolean.getBoolean(PROPERTY)) {
            return;
        }
        RailPathToolMod.LOGGER.info("[EDITORSHOT] armed");
        ClientTickEvents.END_CLIENT_TICK.register(EditorShot::tick);
    }

    private static void tick(Minecraft mc) {
        if (selected || mc.player == null) {
            return;
        }
        if (++ticks < DELAY_TICKS) {
            return;
        }
        ticks = 0;
        try {
            if (!tutorialSkipped) {
                Class<?> tutorial = Class.forName(
                        "com.moulberry.axiom.editor.tutorial.TutorialManager");
                tutorial.getMethod("skip").invoke(null);
                tutorialSkipped = true;
                RailPathToolMod.LOGGER.info("[EDITORSHOT] tutorial skipped");
            }
            selected = selectRailTool();
        } catch (Throwable t) {
            RailPathToolMod.LOGGER.warn("[EDITORSHOT] failed: " + t);
        }
    }

    private static boolean selectRailTool() throws Exception {
        Class<?> manager = Class.forName("com.moulberry.axiom.tools.ToolManager");
        Method getTools = manager.getMethod("getTools");
        Method nameOf = Class.forName("com.moulberry.axiom.tools.Tool")
                .getMethod("name");
        List<?> tools = (List<?>) getTools.invoke(null);
        for (int i = 0; i < tools.size(); i++) {
            Object tool = tools.get(i);
            Object name = nameOf.invoke(tool);
            if (name instanceof String text && text.contains("BTE")) {
                manager.getMethod("setCurrentToolIndex", int.class).invoke(null, i);
                manager.getMethod("setToolSelected", boolean.class).invoke(null, true);
                RailPathToolMod.LOGGER.info("[EDITORSHOT] selected tool " + i
                        + " of " + tools.size() + ": " + text);
                return true;
            }
        }
        RailPathToolMod.LOGGER.warn("[EDITORSHOT] rail tool missing from "
                + tools.size() + " tools");
        return false;
    }
}
