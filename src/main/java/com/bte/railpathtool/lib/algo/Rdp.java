package com.bte.railpathtool.lib.algo;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Ramer-Douglas-Peucker : simplifie une polyligne 3D en gardant les points
 * significatifs. Utilise pour epurer les points de controle redondants
 * (doublons quasi colineaires) avant le calcul de la spline.
 */
public final class Rdp {

    private Rdp() {
    }

    public static List<Vec3> simplify(List<Vec3> pts, double epsilon) {
        if (pts.size() <= 2) {
            return new ArrayList<>(pts);
        }
        boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[keep.length - 1] = true;
        simplifyRange(pts, 0, pts.size() - 1, epsilon * epsilon, keep);
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (keep[i]) {
                out.add(pts.get(i));
            }
        }
        return out;
    }

    private static void simplifyRange(List<Vec3> pts, int a, int b,
                                      double eps2, boolean[] keep) {
        if (b <= a + 1) {
            return;
        }
        Vec3 pa = pts.get(a);
        Vec3 pb = pts.get(b);
        double maxD2 = -1.0;
        int maxI = -1;
        for (int i = a + 1; i < b; i++) {
            double d2 = distPointSeg2(pts.get(i), pa, pb);
            if (d2 > maxD2) {
                maxD2 = d2;
                maxI = i;
            }
        }
        if (maxD2 > eps2 && maxI > 0) {
            keep[maxI] = true;
            simplifyRange(pts, a, maxI, eps2, keep);
            simplifyRange(pts, maxI, b, eps2, keep);
        }
    }

    /** Distance au carre point-segment. */
    private static double distPointSeg2(Vec3 p, Vec3 a, Vec3 b) {
        double abx = b.x - a.x;
        double aby = b.y - a.y;
        double abz = b.z - a.z;
        double len2 = abx * abx + aby * aby + abz * abz;
        if (len2 < 1.0e-12) {
            double dx = p.x - a.x;
            double dy = p.y - a.y;
            double dz = p.z - a.z;
            return dx * dx + dy * dy + dz * dz;
        }
        double t = ((p.x - a.x) * abx + (p.y - a.y) * aby + (p.z - a.z) * abz) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double dx = p.x - (a.x + abx * t);
        double dy = p.y - (a.y + aby * t);
        double dz = p.z - (a.z + abz * t);
        return dx * dx + dy * dy + dz * dz;
    }
}
