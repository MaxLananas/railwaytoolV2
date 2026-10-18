package com.bte.railpathtool.lib.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistance des options de l'outil + 3 presets joueur dans
 * config/bte_railpathtool.json (Gson, ecriture atomique via tmp+move).
 */
public final class RailConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int density = 6;
    public boolean smoothRidges = true;
    public boolean showGhost = true;
    public boolean showSplineGhost = true;
    public boolean purgeCorners = true;
    public boolean snapGround = true;
    public int styleIndex;
    public int themeIndex;
    public int fillIndex;
    public int heightIndex;
    public int orientIndex;
    public Data preset1 = new Data();
    public Data preset2 = new Data();
    public Data preset3 = new Data();

    /** Instantane des choix de l'UI (slots de preset). */
    public static final class Data {
        public int density = 6;
        public int styleIndex;
        public int themeIndex;
        public int fillIndex;
        public int heightIndex;
        public int orientIndex;
        public boolean smoothRidges = true;
        public boolean purgeCorners = true;
        public boolean snapGround = true;

        private void sanitize() {
            density = clamp(density, 2, 12);
            styleIndex = clamp(styleIndex, 0, 1);
            themeIndex = clamp(themeIndex, 0, 1);
            fillIndex = clamp(fillIndex, 0, 1);
            heightIndex = clamp(heightIndex, 0, 1);
            orientIndex = clamp(orientIndex, 0, 3);
        }
    }

    /** Ramene les valeurs hors bornes d'un fichier edite a la main. */
    public void sanitize() {
        density = clamp(density, 2, 12);
        styleIndex = clamp(styleIndex, 0, 1);
        themeIndex = clamp(themeIndex, 0, 1);
        fillIndex = clamp(fillIndex, 0, 1);
        heightIndex = clamp(heightIndex, 0, 1);
        orientIndex = clamp(orientIndex, 0, 3);
        preset1.sanitize();
        preset2.sanitize();
        preset3.sanitize();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("bte_railpathtool.json");
    }

    public static RailConfig load() {
        Path p = path();
        if (Files.isRegularFile(p)) {
            try (Reader r = Files.newBufferedReader(p)) {
                RailConfig c = GSON.fromJson(r, RailConfig.class);
                if (c != null) {
                    if (c.preset1 == null) {
                        c.preset1 = new Data();
                    }
                    if (c.preset2 == null) {
                        c.preset2 = new Data();
                    }
                    if (c.preset3 == null) {
                        c.preset3 = new Data();
                    }
                    c.sanitize();
                    return c;
                }
            } catch (IOException | RuntimeException ignored) {
                // Fichier corrompu : on repart sur les defauts.
            }
        }
        return new RailConfig();
    }

    public static void save(RailConfig cfg) {
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(cfg, w);
            }
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Disque plein / droits : non bloquant pour l'outil.
        }
    }
}
