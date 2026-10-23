package com.bte.railpathtool.lib.curve;

import net.minecraft.world.phys.Vec3;

/**
 * Catmull-Rom core: evaluation and curvature of a p1->p2 segment. Used by the
 * spline sampler and the adaptive density.
 */
public final class CatmullRom {

    private CatmullRom() {
    }

    /** Point at t in [0,1] on segment p1-p2, with p0 and p3 as neighbours. */
    public static Vec3 eval(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return new Vec3(
                cr(p0.x, p1.x, p2.x, p3.x, t, t2, t3),
                cr(p0.y, p1.y, p2.y, p3.y, t, t2, t3),
                cr(p0.z, p1.z, p2.z, p3.z, t, t2, t3));
    }

    private static double cr(double a, double b, double c, double d,
                             double t, double t2, double t3) {
        return 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2
                + (-a + 3 * b - 3 * c + d) * t3);
    }

    /**
     * Discrete control-point curvature: angle between consecutive segments
     * divided by the mean chord (0 is straight; above 0.25 is a tight turn).
     */
    public static double curvature(Vec3 prev, Vec3 cur, Vec3 next) {
        Vec3 v1 = cur.subtract(prev);
        Vec3 v2 = next.subtract(cur);
        double l1 = v1.length();
        double l2 = v2.length();
        if (l1 < 1.0e-9 || l2 < 1.0e-9) {
            return 0.0;
        }
        double dot = (v1.x * v2.x + v1.y * v2.y + v1.z * v2.z) / (l1 * l2);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.acos(dot) / (0.5 * (l1 + l2));
    }
}
