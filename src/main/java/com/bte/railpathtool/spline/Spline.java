package com.bte.railpathtool.spline;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class Spline {

    private Spline() {
    }

    public static List<Vec3> sample(List<BlockPos> control, int samplesPerBlock) {
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
            int steps = Math.max(1, (int) Math.ceil(p1.distanceTo(p2) * samplesPerBlock));
            for (int s = 0; s < steps; s++) {
                out.add(eval(p0, p1, p2, p3, (double) s / steps));
            }
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    private static Vec3 eval(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
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

    public static List<BlockPos> voxelize(List<Vec3> samples) {
        List<BlockPos> voxels = new ArrayList<>();
        for (Vec3 v : samples) {
            BlockPos cur = BlockPos.containing(v);
            if (voxels.isEmpty()) {
                voxels.add(cur);
                continue;
            }
            BlockPos last = voxels.get(voxels.size() - 1);
            if (cur.equals(last)) {
                continue;
            }
            int dx = cur.getX() - last.getX();
            int dy = cur.getY() - last.getY();
            int dz = cur.getZ() - last.getZ();
            int n = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
            if (n == 1) {
                voxels.add(cur);
                continue;
            }

            for (int k = 1; k < n; k++) {
                double f = (double) k / n;
                voxels.add(new BlockPos(
                        (int) Math.floor(last.getX() + dx * f + 0.5),
                        (int) Math.floor(last.getY() + dy * f + 0.5),
                        (int) Math.floor(last.getZ() + dz * f + 0.5)));
            }
            voxels.add(cur);
        }
        return new ArrayList<>(new LinkedHashSet<>(voxels));
    }
}
