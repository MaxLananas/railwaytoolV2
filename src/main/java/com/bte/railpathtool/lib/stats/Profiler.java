package com.bte.railpathtool.lib.stats;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Profileur minimaliste : anneaux de durees (ns) par section, moyennes
 * affichees dans l'onglet debug de l'outil. Zero allocation a l'usage.
 */
public final class Profiler {

    private static final int CAP = 128;

    private static final Map<String, Ring> RINGS = new TreeMap<>();

    private Profiler() {
    }

    public static void record(String section, long nanos) {
        RINGS.computeIfAbsent(section, s -> new Ring()).push(nanos);
    }

    public static long timeStart() {
        return System.nanoTime();
    }

    public static void timeEnd(String section, long startNanos) {
        record(section, System.nanoTime() - startNanos);
    }

    /** Moyenne en millisecondes sur l'anneau courant. */
    public static double avgMs(String section) {
        Ring r = RINGS.get(section);
        return r == null || r.count == 0 ? 0.0 : r.sum() / (double) r.count / 1.0e6;
    }

    public static Map<String, Double> summaryMs() {
        Map<String, Double> out = new TreeMap<>();
        for (Map.Entry<String, Ring> e : RINGS.entrySet()) {
            out.put(e.getKey(), avgMs(e.getKey()));
        }
        return out;
    }

    public static void clear() {
        RINGS.clear();
    }

    private static final class Ring {
        private final long[] buf = new long[CAP];
        private int head;
        private int count;

        void push(long v) {
            buf[head] = v;
            head = (head + 1) % CAP;
            if (count < CAP) {
                count++;
            }
        }

        long sum() {
            return Arrays.stream(buf, 0, count).sum();
        }
    }
}
