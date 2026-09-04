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
 * Petit remerciement discret sur le menu principal : deux lignes en bas à droite
 * avec les liens de la communauté (Discord + Modrinth). Sans dépendance externe.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

    @Unique
    private static final String BTE_RAIL$LINE_1 = "Railway Tools for Axiom — merci de jouer avec !";
    @Unique
    private static final String BTE_RAIL$LINE_2 = "discord.gg/pnJhKuU2QK  ·  modrinth.com/user/maxlananass";

    @Inject(method = "render", at = @At("TAIL"))
    private void bteRail$renderThanks(GuiGraphics graphics, int mouseX, int mouseY,
                                      float partialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) {
            return;
        }
        int x1 = graphics.guiWidth() - mc.font.width(BTE_RAIL$LINE_1) - 4;
        int x2 = graphics.guiWidth() - mc.font.width(BTE_RAIL$LINE_2) - 4;
        int y = graphics.guiHeight() - 20;
        graphics.drawString(mc.font, BTE_RAIL$LINE_1, x1, y, 0x55FFFFFF, false);
        graphics.drawString(mc.font, BTE_RAIL$LINE_2, x2, y + 10, 0x55FFFFFF, false);
    }
}
