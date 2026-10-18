package com.bte.railpathtool.lib.curve;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Echantillonnage adaptatif de la spline : le pas decroit quand la courbure
 * monte (virage serre -> plus d'echantillons -> voxelisation plus fidele),
 * et croit sur les grandes lignes droites (moins de travail inutile).
 */
public final class AdaptiveSampler {

    private AdaptiveSampler() {
    }

    /**
     * Echantillonne le controle en ajustant la densite locale via la courbure.
     * density = pas de base (echantillons par bloc) du slider UI.
     */
    public static List<Vec3> sample(List<BlockPos> control, int baseDensity) {
        List<Vec3> out = new ArrayList<>();
        if (control.size() < 2) {
            return out;
        }
        List<Vec3> pts = new ArrayList<>();
        for (BlockPos p : control) {
            pts.add(Vec3.atCenterOf(p));
        }
        List<Vec3> ext = new ArrayList<>();
        ext.add(pts.get(0).scale(2).subtract(pts.get(1)));
        ext.addAll(pts);
        ext.add(pts.get(pts.size() - 1).scale(2).subtract(pts.get(pts.size() - 2)));

        for (int i = 1; i < ext.size() - 2; i++) {
            Vec3 p0 = ext.get(i - 1);
            Vec3 p1 = ext.get(i);
            Vec3 p2 = ext.get(i + 1);
            Vec3 p3 = ext.get(i + 2);
            double midCurv = CatmullRom.curvature(p0, p1, p2)
                    + CatmullRom.curvature(p1, p2, p3);
            // Facteur 1 (droit) a 3 (virage serre), borne.
            double boost = 1.0 + Math.min(2.0, midCurv * 3.0);
            int steps = Math.max(1,
                    (int) Math.ceil(p1.distanceTo(p2) * baseDensity * boost));
            for (int s = 0; s < steps; s++) {
                out.add(CatmullRom.eval(p0, p1, p2, p3, (double) s / steps));
            }
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }
}
