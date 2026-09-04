package com.bte.railpathtool.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remerciement sur le menu principal : bandeau bas-centre, grand et visible
 * (les 4 coins sont déjà pris par les textes vanilla), avec les liens de la
 * communauté (Discord + Modrinth). Sans dépendance externe.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

    @Unique
    private static final String BTE_RAIL$LINE_1 = "Railway Tools for Axiom";
    @Unique
    private static final String BTE_RAIL$LINE_2 = "Merci de jouer avec le mod !";
    @Unique
    private static final String BTE_RAIL$LINE_3 = "discord.gg/pnJhKuU2QK  ·  modrinth.com/user/maxlananass";

    @Inject(method = "render", at = @At("TAIL"))
    private void bteRail$renderThanks(GuiGraphics graphics, int mouseX, int mouseY,
                                      float partialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) {
            return;
        }
        int h = graphics.guiHeight();
        // Titre agrandi (x1.8), doré, centré : mis en valeur dès l'arrivée.
        var pose = graphics.pose();
        pose.pushPose();
        float scale = 1.8f;
        pose.scale(scale, scale, 1.0f);
        graphics.drawString(mc.font, BTE_RAIL$LINE_1,
                (int) ((graphics.guiWidth() - mc.font.width(BTE_RAIL$LINE_1) * scale) / (2.0f * scale)),
                (int) ((h - 40) / scale), 0xFFFFD54A, true);
        pose.popPose();
        graphics.drawCenteredString(mc.font, BTE_RAIL$LINE_2, graphics.guiWidth() / 2, h - 24, 0xFFFFFFFF);
        graphics.drawCenteredString(mc.font, BTE_RAIL$LINE_3, graphics.guiWidth() / 2, h - 14, 0xFF8FF3FF);
    }
}
