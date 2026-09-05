package com.bte.railpathtool.dev;

import com.bte.railpathtool.design.ClassicDesign;
import com.bte.railpathtool.design.DesignOptions;
import com.bte.railpathtool.design.NatureDesign;
import com.bte.railpathtool.lib.curve.AdaptiveSampler;
import com.bte.railpathtool.spline.Spline;
import com.bte.railpathtool.track.Grounding;
import com.bte.railpathtool.track.LCorners;
import com.bte.railpathtool.track.TrackModel;
import com.bte.railpathtool.track.TrackType;
import com.bte.railpathtool.track.WorldView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Harnais de PARITÉ Java/simulateur : rejoue le VRAI pipeline du mod
 * (sampler adaptatif 6 → voxelize → Grounding ×2 → LCorners → flattenTeeth
 * → dedupeColumns → TrackModel → design, tunnel actif = défaut produit) sur
 * les scènes de sim/parity/scenes.txt, puis compare le monde final, bloc par
 * bloc, avec la sortie attendue du simulateur (auto-validée : aucun core
 * manquant, aucun flottant, aucun doublon vertical). Toute différence est
 * un bug visible en jeu — le job CI `parity` échoue alors.
 */
public final class ParityHarness {

    private ParityHarness() {
    }

    private static final class Scene {
        String id;
        String style;
        String theme;
        boolean buried;
        final List<int[]> boxes = new ArrayList<>();       // x0,x1,y0,y1,z0,z1
        final Map<Integer, List<int[]>> controls = new LinkedHashMap<>();
        final Map<String, String> expect = new TreeMap<>();
        final Map<String, String> simTypes = new TreeMap<>();    // D
        final Map<String, String> simNeighbors = new TreeMap<>(); // N
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Path scenesFile = Path.of(args.length > 0 ? args[0] : "sim/parity/scenes.txt");
        Map<String, Scene> scenes = new LinkedHashMap<>();
        Scene cur = null;
        for (String raw : Files.readAllLines(scenesFile)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.equals("@end")) {
                cur = null;
                continue;
            }
            if (line.startsWith("@")) {
                cur = new Scene();
                cur.id = line.substring(1);
                scenes.put(cur.id, cur);
                continue;
            }
            if (cur == null) {
                continue;
            }
            String[] p = line.split(" ");
            switch (p[0]) {
                case "O" -> {
                    for (int i = 1; i < p.length; i++) {
                        String[] kv = p[i].split("=");
                        switch (kv[0]) {
                            case "style" -> cur.style = kv[1];
                            case "theme" -> cur.theme = kv[1];
                            case "buried" -> cur.buried = kv[1].equals("1");
                            default -> {
                            }
                        }
                    }
                }
                case "R" -> cur.boxes.add(new int[]{i(p[1]), i(p[2]), i(p[3]),
                        i(p[4]), i(p[5]), i(p[6])});
                case "C" -> cur.controls.computeIfAbsent(i(p[1]), k -> new ArrayList<>())
                        .add(new int[]{i(p[2]), i(p[3]), i(p[4])});
                case "E" -> cur.expect.put(key(i(p[1]), i(p[2]), i(p[3])), p[4]);
                case "D" -> cur.simTypes.put(key(i(p[1]), i(p[2]), i(p[3])), p[4]);
                case "N" -> cur.simNeighbors.put(key(i(p[1]), i(p[2]), i(p[3])), p[4]);
                default -> {
                }
            }
        }

        int ok = 0;
        int fail = 0;
        List<String> report = new ArrayList<>();
        for (Scene sc : scenes.values()) {
            List<String> diffs = run(sc);
            if (diffs.isEmpty()) {
                ok++;
            } else {
                fail++;
                report.add(sc.id + " : " + diffs.size() + " differences");
                for (int i = 0; i < Math.min(8, diffs.size()); i++) {
                    report.add("   " + diffs.get(i));
                }
            }
        }
        System.out.println("=== PARITY " + scenes.size() + " scenes ===");
        for (String r : report) {
            System.out.println(r);
        }
        System.out.println(ok + " OK / " + fail + " DIFFERENT");
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** Rejoue la scène côté Java réel et retourne la liste des divergences. */
    private static List<String> run(Scene sc) {
        WorldView view = new WorldView(null);
        for (int[] b : sc.boxes) {
            for (int x = b[0]; x <= b[1]; x++) {
                for (int y = b[2]; y <= b[3]; y++) {
                    for (int z = b[4]; z <= b[5]; z++) {
                        view.put(x, y, z,
                                net.minecraft.world.level.block.Blocks.GRASS_BLOCK
                                        .defaultBlockState());
                    }
                }
            }
        }

        DesignOptions options = new DesignOptions();
        options.style = sc.style.equals("nature")
                ? DesignOptions.Style.NATURE : DesignOptions.Style.CLASSIC;
        options.theme = sc.theme.equals("light")
                ? DesignOptions.Theme.LIGHT : DesignOptions.Theme.DARK;
        options.baseDy = sc.buried ? -1 : 0;

        for (Map.Entry<Integer, List<int[]>> e : sc.controls.entrySet()) {
            List<BlockPos> control = new ArrayList<>();
            for (int[] c : e.getValue()) {
                control.add(new BlockPos(c[0], c[1], c[2]));
            }
            if (control.size() < 2) {
                continue;
            }
            List<net.minecraft.world.phys.Vec3> samples =
                    AdaptiveSampler.sample(control, 6);
            List<BlockPos> trace = Spline.voxelize(samples);
            for (BlockPos v : trace) {
                view.put(v.getX(), v.getY(), v.getZ(),
                        net.minecraft.world.level.block.Blocks.WHITE_WOOL
                                .defaultBlockState());
            }
            LongOpenHashSet dug = new LongOpenHashSet();
            trace = Grounding.apply(view, trace, dug);
            // Pipeline EXACT du mod (RailwayTool.recompute) : pas de re-lay
            // intermediaire — le Grounding gere lui-meme la laine.
            trace = LCorners.purge(view, trace);
            trace = Grounding.apply(view, trace, dug);
            trace = Grounding.flattenTeeth(view, trace);
            trace = Grounding.dedupeColumns(view, trace);

            TrackModel model = new TrackModel(view, trace, TrackModel.OverrideMode.AUTO);
            long typeMism = 0;
            long nbMism = 0;
            for (BlockPos v : trace) {
                String k = key(v.getX(), v.getY(), v.getZ());
                String simT = sc.simTypes.get(k);
                String simN = sc.simNeighbors.get(k);
                TrackType jt = model.typeOf(v);
                String jts = jt == null ? "?" : jt.name();
                if (simT != null && !simT.equals(jts)) {
                    if (typeMism < 4) {
                        System.out.println("  [TYPE] " + k + " sim=" + simT
                                + " java=" + jts);
                    }
                    if (typeMism < 2) {
                        System.out.println("    [WHY] " + model.debugExplain(v));
                    }
                    typeMism++;
                }
                String jn = String.join(",", model.neighborDirections(
                        v.getX(), v.getY(), v.getZ()));
                if (jn.isEmpty()) {
                    jn = "-";
                }
                if (simN != null && !simN.equals(jn)) {
                    if (nbMism < 4) {
                        System.out.println("  [NB] " + k + " sim=" + simN
                                + " java=" + jn);
                    }
                    nbMism++;
                }
            }
            if (typeMism > 0 || nbMism > 0) {
                System.out.println("  -> types divergents=" + typeMism
                        + " voisinages divergents=" + nbMism);
            }
            // Comparaison bidirectionnelle des ensembles de voxels de trace :
            // un voxel java en plus (ou en moins) fausse les scans des agents
            // sans jamais apparaitre dans les diffs de types.
            {
                LongOpenHashSet javaKeys = new LongOpenHashSet();
                for (BlockPos v : trace) {
                    javaKeys.add(BlockPos.asLong(v.getX(), v.getY(), v.getZ()));
                }
                int missing = 0;
                int extra = 0;
                for (String k : sc.simTypes.keySet()) {
                    String[] c = k.split(",");
                    long lk = BlockPos.asLong(i(c[0]), i(c[1]), i(c[2]));
                    if (!javaKeys.contains(lk)) {
                        if (missing < 3) {
                            System.out.println("  [TRACE-] sim a " + k
                                    + " que java n'a pas");
                        }
                        missing++;
                    }
                }
                for (BlockPos v : trace) {
                    String k = key(v.getX(), v.getY(), v.getZ());
                    if (!sc.simTypes.containsKey(k)) {
                        if (extra < 3) {
                            System.out.println("  [TRACE+] java a " + k
                                    + " que sim n'a pas");
                        }
                        extra++;
                    }
                }
                if (missing > 0 || extra > 0) {
                    System.out.println("  -> trace: manquants=" + missing
                            + " supplementaires=" + extra);
                }
            }
            Long2ObjectOpenHashMap<BlockState> plan = new Long2ObjectOpenHashMap<>();
            if (options.style == DesignOptions.Style.CLASSIC) {
                new ClassicDesign().emitCases(model, options, plan);
            } else {
                new NatureDesign().emitCases(model, options, plan);
            }
            for (long k : dug) {
                if (!plan.containsKey(k)) {
                    plan.put(k, net.minecraft.world.level.block.Blocks.AIR
                            .defaultBlockState());
                }
            }
        }

        // Carte finale : overlay du view (l'overlay reflète déjà tout ce que
        // les passes/track/designs ont écrit — ColumnWriter/Writer écrivent
        // à double dans plan ET view).
        Long2ObjectOpenHashMap<BlockState> ov = view.overlay();
        Map<String, String> diffs = new TreeMap<>();
        Map<String, String> finalTok = new TreeMap<>();
        for (Map.Entry<Long, BlockState> en : ov.entrySet()) {
            long k = en.getKey();
            String token = token(en.getValue());
            if (!token.equals("air")) {
                finalTok.put(key(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k)),
                        token);
            }
        }
        for (Map.Entry<String, String> ex : sc.expect.entrySet()) {
            String got = finalTok.get(ex.getKey());
            if (got == null) {
                got = "air";
            }
            if (!got.equals(ex.getValue())) {
                diffs.put(ex.getKey(), ex.getKey() + " attendu=" + ex.getValue() + " obtenu=" + got);
            }
        }
        for (Map.Entry<String, String> g : finalTok.entrySet()) {
            if (!sc.expect.containsKey(g.getKey())) {
                diffs.put(g.getKey(), g.getKey() + " attendu=air obtenu=" + g.getValue());
            }
        }
        return new ArrayList<>(diffs.values());
    }

    private static int i(String s) {
        return Integer.parseInt(s);
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** Nom canonique d'un état de bloc, identique aux tokens du simulateur. */
    static String token(BlockState st) {
        if (st == null || st.isAir()) {
            return "air";
        }
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(st.getBlock()).getPath();
        switch (id) {
            case "white_wool", "red_wool", "blue_wool", "lime_wool",
                 "orange_wool", "black_wool", "grass_block",
                 "pale_moss_block", "pale_moss_carpet", "water" -> {
                return id;
            }
            case "gravel" -> {
                return "gravel";
            }
            case "deepslate", "cobbled_deepslate", "pale_oak_wood",
                 "deepslate_iron_ore", "deepslate_coal_ore" -> {
                return "soil";
            }
            case "dead_bubble_coral_wall_fan" -> {
                return "coral_" + prop(st, "facing");
            }
            case "mud_brick_wall", "andesite_wall" -> {
                return wallToken(st);
            }
            case "spruce_shelf" -> {
                return "side_" + prop(st, "facing");
            }
            case "iron_door" -> {
                return "door_" + prop(st, "half") + "_" + prop(st, "facing");
            }
            case "lectern" -> {
                return "lectern_" + prop(st, "facing");
            }
            case "oak_button" -> {
                return "button_" + prop(st, "facing");
            }
            case "leaf_litter" -> {
                return "leaf_" + prop(st, "segment_amount") + "_"
                        + prop(st, "facing");
            }
            case "air", "cave_air", "void_air" -> {
                return "air";
            }
            default -> {
                return "UNKNOWN:" + id;
            }
        }
    }

    private static String wallToken(BlockState st) {
        boolean n = !prop(st, "north").equals("none");
        boolean e = !prop(st, "east").equals("none");
        boolean s = !prop(st, "south").equals("none");
        boolean w = !prop(st, "west").equals("none");
        if (n && s && !e && !w) {
            return "wall_ns";
        }
        if (e && w && !n && !s) {
            return "wall_eo";
        }
        if (n && e && !s && !w) {
            return "wall_ne";
        }
        if (n && w && !s && !e) {
            return "wall_nw";
        }
        if (s && e && !n && !w) {
            return "wall_se";
        }
        if (s && w && !n && !e) {
            return "wall_sw";
        }
        return "wall_" + (n ? "n" : "") + (s ? "s" : "") + (e ? "e" : "")
                + (w ? "w" : "");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String prop(BlockState st, String name) {
        for (Property<?> p : st.getProperties()) {
            if (p.getName().equals(name)) {
                return ((Property) p).getName(st.getValue(p));
            }
        }
        return "?";
    }
}
