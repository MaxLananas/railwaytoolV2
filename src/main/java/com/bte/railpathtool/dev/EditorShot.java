package com.bte.railpathtool.dev;

import com.bte.railpathtool.RailPathToolMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Screenshot helper, disabled unless {@code -Dbte.railtool.ui=true}.
 *
 * <p>Axiom shows a six step tutorial on a fresh profile and blocks the rest of
 * the editor until it is dismissed. The CI client has no keyboard user, so the
 * helper skips it once; everything else is driven with real clicks.
 */
public final class EditorShot {

    private static final String PROPERTY = "bte.railtool.ui";
    private static final int DELAY_TICKS = 100;

    private static int ticks;
    private static boolean done;

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
        if (done || mc.player == null) {
            return;
        }
        if (++ticks < DELAY_TICKS) {
            return;
        }
        done = true;
        try {
            Class<?> tutorial = Class.forName(
                    "com.moulberry.axiom.editor.tutorial.TutorialManager");
            tutorial.getMethod("skip").invoke(null);
            RailPathToolMod.LOGGER.info("[EDITORSHOT] tutorial skipped");
        } catch (Throwable t) {
            RailPathToolMod.LOGGER.warn("[EDITORSHOT] failed: " + t);
        }
    }
}
