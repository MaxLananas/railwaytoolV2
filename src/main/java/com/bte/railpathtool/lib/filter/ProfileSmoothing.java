package com.bte.railpathtool.lib.filter;

import java.util.List;

/**
 * Filtres de profil vertical facon "plan de profil" : mediane puis moyenne
 * glissante sur les altitudes echantillonnees (lissage SNCF en ligne directe :
 * une voie reelle evite les micro-pentes abruptes).
 */
public final class ProfileSmoothing {

    private ProfileSmoothing() {
    }

    /** Mediane-3 : tue les pics d'un echantillon sans bouger les plateaux. */
    public static double[] median3(double[] y) {
        if (y.length < 3) {
            return y.clone();
        }
        double[] out = y.clone();
        for (int i = 1; i < y.length - 1; i++) {
            double a = y[i - 1];
            double b = y[i];
            double c = y[i + 1];
            out[i] = (a > b) ? (b > c ? b : Math.max(a, c)) : (a > c ? a : Math.min(b, c));
        }
        return out;
    }

    /** Moyenne glissante de demi-largeur r (bords preserves par troncature). */
    public static double[] boxcar(double[] y, int r) {
        if (r <= 0 || y.length == 0) {
            return y.clone();
        }
        double[] out = new double[y.length];
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < y.length; i++) {
            sum += y[i];
            count++;
            int evict = i - 2 * r - 1;
            if (evict >= 0) {
                sum -= y[evict];
                count--;
            }
            out[i] = sum / count;
        }
        return out;
    }

    /** Passe complete : mediane-3 puis moyenne glissante legere. */
    public static double[] smooth(double[] y, int halfWidth) {
        return boxcar(median3(y), Math.max(1, halfWidth));
    }

    /** Reapplique le profil lisse sur des echantillons (x, y, z). */
    public static List<double[]> apply(List<double[]> samples, int halfWidth) {
        double[] y = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            y[i] = samples.get(i)[1];
        }
        double[] sm = smooth(y, halfWidth);
        java.util.List<double[]> out = new java.util.ArrayList<>(samples.size());
        for (int i = 0; i < samples.size(); i++) {
            double[] s = samples.get(i);
            out.add(new double[]{s[0], sm[i], s[2]});
        }
        return out;
    }
}
