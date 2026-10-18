package com.bte.railpathtool.lib.curve;

import net.minecraft.world.phys.Vec3;

/**
 * Noyau Catmull-Rom : evaluation, derivee et courbure d'un segment p1->p2.
 * Utilise par la spline (echantillonnage) et la densite adaptative.
 */
public final class CatmullRom {

    private CatmullRom() {
    }

    /** Point a t dans [0,1] sur le segment p1-p2 (voisinage p0,p3). */
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

    /** Vecteur vitesse (derivee premiere) a t. */
    public static Vec3 deriv(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t;
        return new Vec3(
                crD(p0.x, p1.x, p2.x, p3.x, t, t2),
                crD(p0.y, p1.y, p2.y, p3.y, t, t2),
                crD(p0.z, p1.z, p2.z, p3.z, t, t2));
    }

    private static double crD(double a, double b, double c, double d,
                              double t, double t2) {
        return 0.5 * ((-a + c) + 2 * (2 * a - 5 * b + 4 * c - d) * t
                + 3 * (-a + 3 * b - 3 * c + d) * t2);
    }

    /** Vecteur acceleration (derivee seconde) a t. */
    public static Vec3 deriv2(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        return new Vec3(
                crD2(p0.x, p1.x, p2.x, p3.x, t),
                crD2(p0.y, p1.y, p2.y, p3.y, t),
                crD2(p0.z, p1.z, p2.z, p3.z, t));
    }

    private static double crD2(double a, double b, double c, double d, double t) {
        return (2 * a - 5 * b + 4 * c - d) + 3 * (-a + 3 * b - 3 * c + d) * t;
    }

    /**
     * Courbure discrete du controle : angle entre les segments consecutifs
     * normalise par la corde moyenne (0 = droit). >0.25 : virage serre.
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
