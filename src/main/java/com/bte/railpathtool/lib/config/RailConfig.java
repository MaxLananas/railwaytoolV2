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

        public Data copy() {
            Data d = new Data();
            d.density = density;
            d.styleIndex = styleIndex;
            d.themeIndex = themeIndex;
            d.fillIndex = fillIndex;
            d.heightIndex = heightIndex;
            d.orientIndex = orientIndex;
            d.smoothRidges = smoothRidges;
            d.purgeCorners = purgeCorners;
            d.snapGround = snapGround;
            return d;
        }
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
