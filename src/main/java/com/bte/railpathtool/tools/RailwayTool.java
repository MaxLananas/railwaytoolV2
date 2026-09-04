package com.bte.railpathtool.tools;

import com.bte.railpathtool.axiom.Axiom;
import com.bte.railpathtool.design.ClassicDesign;
import com.bte.railpathtool.design.DesignOptions;
import com.bte.railpathtool.design.NatureDesign;
import com.bte.railpathtool.lib.algo.Rdp;
import com.bte.railpathtool.lib.config.RailConfig;
import com.bte.railpathtool.lib.curve.AdaptiveSampler;
import com.bte.railpathtool.lib.graph.UnionFind;
import com.bte.railpathtool.lib.stats.Profiler;
import com.bte.railpathtool.spline.Spline;
import com.bte.railpathtool.track.Grounding;
import com.bte.railpathtool.track.LCorners;
import com.bte.railpathtool.track.TrackModel;
import com.bte.railpathtool.track.WorldView;
import com.moulberry.axiomclientapi.CustomTool;
import com.moulberry.axiomclientapi.Effects;
import com.moulberry.axiomclientapi.regions.BlockRegion;
import com.moulberry.axiomclientapi.regions.BooleanRegion;
import com.mojang.blaze3d.vertex.PoseStack;
import imgui.moulberry92.ImGui;
import imgui.moulberry92.type.ImBoolean;
import imgui.moulberry92.type.ImInt;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RailwayTool implements CustomTool {

    private static final int MAX_PLAN_BLOCKS = 60_000;
    private static final int MAX_GHOST_BLOCKS = 6_000;

    private final List<BlockPos> control = new ArrayList<>();
    private final int[] density = {6};
    private final ImBoolean groundSnap = new ImBoolean(true);
    private final ImBoolean smoothRidges = new ImBoolean(true);
    private final ImBoolean purgeCorners = new ImBoolean(true);
    private final ImBoolean showGhost = new ImBoolean(true);
    private final ImBoolean showSplineGhost = new ImBoolean(true);
    private final ImBoolean adaptive = new ImBoolean(true);
    private final RailConfig config;
    private boolean configLoaded = false;

    private final DesignOptions options = new DesignOptions();
    private TrackModel.OverrideMode overrideMode = TrackModel.OverrideMode.AUTO;
    private final int[][] soilPercentUI;
    private final ImInt styleSel = new ImInt(0);
    private final ImInt orientSel = new ImInt(0);
    private final ImInt themeSel = new ImInt(0);
    private final ImInt fillSel = new ImInt(0);
    private final ImInt heightSel = new ImInt(0);

    private final Long2ObjectOpenHashMap<BlockState> plan = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet ghostPositions = new LongOpenHashSet();
    private final LongOpenHashSet dugPositions = new LongOpenHashSet();
    private BooleanRegion splineGhost;
    private int planCount = 0;
    private int splineLength = 0;
    private boolean dirty = true;
    private boolean phantomAcquired = false;
    private String status = "";
    private boolean statusError = false;

    public RailwayTool() {
        config = RailConfig.load();
        soilPercentUI = new int[options.soilSlots.length][1];
        for (int i = 0; i < options.soilSlots.length; i++) {
            soilPercentUI[i][0] = options.soilSlots[i].percent;
        }
    }

    @Override
    public String name() {
        return tr("tool.name");
    }

    private void restoreConfigOnce() {
        if (configLoaded) {
            return;
        }
        configLoaded = true;
        density[0] = Math.max(2, Math.min(12, config.density));
        styleSel.set(config.styleIndex);
        themeSel.set(config.themeIndex);
        fillSel.set(config.fillIndex);
        heightSel.set(config.heightIndex);
        orientSel.set(config.orientIndex);
        smoothRidges.set(config.smoothRidges);
        purgeCorners.set(config.purgeCorners);
        groundSnap.set(config.snapGround);
        showGhost.set(config.showGhost);
        showSplineGhost.set(config.showSplineGhost);
        applySelections();
    }

    @Override
    public void reset() {
        clearGhost();
        clearSplineGhost();
        control.clear();
        plan.clear();
        planCount = 0;
        dirty = true;
        status = "";
        statusError = false;
        if (phantomAcquired) {
            Axiom.releaseChunkRenderOverrider("bte_railpathtool");
            phantomAcquired = false;
        }
    }

    @Override
    public void render(Camera camera, float partialTick, long nanos,
                       PoseStack poseStack, Matrix4f projection) {
        if (dirty) {
            dirty = false;
            recompute(Minecraft.getInstance());
        }
        if (splineGhost != null) {
            splineGhost.render(camera, Vec3.ZERO, poseStack, projection, nanos, Effects.OUTLINE);
        }
    }

    @Override
    public boolean callUseTool() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        BlockHitResult hit = Axiom.raycastBlock();
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            setStatus(tr("ui.no_target"), true);
            return true;
        }
        BlockPos target = hit.getBlockPos();

        BlockPos point = mc.level.getBlockState(target).isAir() ? target : target.above();
        control.add(point);
        dirty = true;
        setStatus(tr("ui.point_added", control.size()), false);
        return true;
    }

    @Override
    public boolean callConfirm() {
        confirm(Minecraft.getInstance());
        return true;
    }

    @Override
    public boolean callDelete() {
        if (!control.isEmpty()) {
            control.remove(control.size() - 1);
            dirty = true;
            setStatus(tr("ui.point_removed", control.size()), false);
        }
        return true;
    }

    @Override
    public void displayImguiOptions() {
        Minecraft mc = Minecraft.getInstance();
        restoreConfigOnce();

        ImGui.textDisabled(tr("ui.help_1"));
        ImGui.textDisabled(tr("ui.help_2"));
        ImGui.textDisabled(tr("ui.help_3"));
        if (!status.isEmpty()) {
            ImGui.textWrapped((statusError ? "/!\\ " : "(i) ") + status);
        }
        ImGui.separator();

        if (ImGui.button(tr("ui.undo"))) {
            callDelete();
        }
        ImGui.sameLine();
        if (ImGui.button(tr("ui.confirm"))) {
            confirm(mc);
        }
        ImGui.sameLine();
        if (ImGui.button(tr("ui.clear"))) {
            control.clear();
            dirty = true;
        }
        ImGui.text(tr("ui.points", control.size()));
        if (control.size() > 3) {
            ImGui.sameLine();
            if (ImGui.button(tr("ui.simplify"))) {
                simplifyControl();
            }
        }
        if (planCount > 0) {
            ImGui.text(tr("ui.est_blocks", planCount));
            if (splineLength > 0) {
                ImGui.text(tr("ui.length", splineLength));
            }
        }
        ImGui.separator();

        ImGui.textDisabled(tr("ui.gen"));
        if (ImGui.sliderInt(tr("ui.density"), density, 2, 12)) {
            dirty = true;
        }
        if (ImGui.checkbox(tr("ui.adaptive"), adaptive)) {
            dirty = true;
        }
        if (ImGui.checkbox(tr("ui.ground_snap"), groundSnap)) {
            dirty = true;
        }
        if (groundSnap.get()) {
            if (ImGui.checkbox(tr("ui.smooth_ridges"), smoothRidges)) {
                dirty = true;
            }
        }
        if (ImGui.checkbox(tr("ui.purge_corners"), purgeCorners)) {
            dirty = true;
        }
        if (ImGui.checkbox(tr("ui.ghost"), showGhost)) {
            dirty = true;
        }
        if (ImGui.checkbox(tr("ui.ghost_spline"), showSplineGhost)) {
            dirty = true;
        }
        ImGui.separator();

        String[] styles = {tr("ui.style.classic"), tr("ui.style.nature")};
        if (ImGui.combo(tr("ui.style"), styleSel, styles)) {
            options.style = styleSel.get() == 0
                    ? DesignOptions.Style.CLASSIC : DesignOptions.Style.NATURE;
            dirty = true;
        }
        String[] orients = {tr("ui.orient.auto"), tr("ui.orient.ns"),
                tr("ui.orient.ew"), tr("ui.orient.diag")};
        TrackModel.OverrideMode[] modes = TrackModel.OverrideMode.values();
        if (ImGui.combo(tr("ui.orientation"), orientSel, orients)) {
            overrideMode = modes[orientSel.get()];
            dirty = true;
        }

        if (options.style == DesignOptions.Style.CLASSIC) {
            drawClassicOptions();
        }
        drawPresets();
        if (ImGui.collapsingHeader(tr("ui.debug"))) {
            for (Map.Entry<String, Double> e : Profiler.summaryMs().entrySet()) {
                ImGui.textDisabled(String.format(java.util.Locale.ROOT,
                        "%s: %.3f ms", e.getKey(), e.getValue()));
            }
        }
    }

    private void drawPresets() {
        if (!ImGui.collapsingHeader(tr("ui.presets"))) {
            return;
        }
        RailConfig.Data[] slots = {config.preset1, config.preset2, config.preset3};
        for (int i = 0; i < slots.length; i++) {
            final int idx = i;
            if (ImGui.button(tr("ui.preset_load") + "##pL" + idx)) {
                applyData(slots[idx]);
            }
            ImGui.sameLine();
            if (ImGui.button(tr("ui.preset_save") + "##pS" + idx)) {
                slots[idx] = snapshotData();
                persistConfig();
            }
            ImGui.sameLine();
            ImGui.textDisabled("#" + (idx + 1));
        }
    }

    private RailConfig.Data snapshotData() {
        RailConfig.Data d = new RailConfig.Data();
        d.density = density[0];
        d.styleIndex = styleSel.get();
        d.themeIndex = themeSel.get();
        d.fillIndex = fillSel.get();
        d.heightIndex = heightSel.get();
        d.orientIndex = orientSel.get();
        d.smoothRidges = smoothRidges.get();
        d.purgeCorners = purgeCorners.get();
        d.snapGround = groundSnap.get();
        return d;
    }

    private void applyData(RailConfig.Data d) {
        if (d == null) {
            return;
        }
        density[0] = Math.max(2, Math.min(12, d.density));
        styleSel.set(d.styleIndex);
        themeSel.set(d.themeIndex);
        fillSel.set(d.fillIndex);
        heightSel.set(d.heightIndex);
        orientSel.set(d.orientIndex);
        smoothRidges.set(d.smoothRidges);
        purgeCorners.set(d.purgeCorners);
        groundSnap.set(d.snapGround);
        applySelections();
        dirty = true;
    }

    private void applySelections() {
        options.style = styleSel.get() == 0
                ? DesignOptions.Style.CLASSIC : DesignOptions.Style.NATURE;
        options.theme = themeSel.get() == 0
                ? DesignOptions.Theme.DARK : DesignOptions.Theme.LIGHT;
        options.fillMode = fillSel.get() == 0
                ? DesignOptions.FillMode.RANDOM : DesignOptions.FillMode.UNIFORM;
        options.baseDy = heightSel.get() == 0 ? 0 : -1;
        overrideMode = TrackModel.OverrideMode.values()[
                Math.max(0, Math.min(TrackModel.OverrideMode.values().length - 1,
                        orientSel.get()))];
    }

    private void persistConfig() {
        RailConfig.Data cur = snapshotData();
        config.density = cur.density;
        config.styleIndex = cur.styleIndex;
        config.themeIndex = cur.themeIndex;
        config.fillIndex = cur.fillIndex;
        config.heightIndex = cur.heightIndex;
        config.orientIndex = cur.orientIndex;
        config.smoothRidges = cur.smoothRidges;
        config.purgeCorners = cur.purgeCorners;
        config.snapGround = cur.snapGround;
        config.showGhost = showGhost.get();
        config.showSplineGhost = showSplineGhost.get();
        RailConfig.save(config);
    }

    private void drawClassicOptions() {
        String[] themes = {tr("ui.theme.dark"), tr("ui.theme.light")};
        if (ImGui.combo(tr("ui.theme"), themeSel, themes)) {
            options.theme = themeSel.get() == 0
                    ? DesignOptions.Theme.DARK : DesignOptions.Theme.LIGHT;
            dirty = true;
        }
        String[] fills = {tr("ui.fill.random"), tr("ui.fill.uniform")};
        if (ImGui.combo(tr("ui.fill"), fillSel, fills)) {
            options.fillMode = fillSel.get() == 0
                    ? DesignOptions.FillMode.RANDOM : DesignOptions.FillMode.UNIFORM;
            dirty = true;
        }

        if (options.fillMode == DesignOptions.FillMode.UNIFORM) {
            ImGui.textWrapped(tr("ui.fill.block",
                    options.uniformBlock.getBlock().getName().getString()));
            if (ImGui.button(tr("ui.pick_active"))) {
                options.uniformBlock = Axiom.getActiveBlock();
                dirty = true;
            }
        } else {
            ImGui.textDisabled(tr("ui.fill.random_hint"));
            for (int i = 0; i < options.soilSlots.length; i++) {
                if (ImGui.sliderInt(
                        options.soilSlots[i].state.getBlock().getName().getString()
                                + "##fill" + i,
                        soilPercentUI[i], 0, 100)) {
                    options.soilSlots[i].percent = soilPercentUI[i][0];
                    dirty = true;
                }
                if (ImGui.button(tr("ui.pick_active") + "##fillbtn" + i)) {
                    options.soilSlots[i].state = Axiom.getActiveBlock();
                    dirty = true;
                }
            }
        }

        String[] heights = {tr("ui.height.surface"), tr("ui.height.buried")};
        if (ImGui.combo(tr("ui.height"), heightSel, heights)) {
            options.baseDy = heightSel.get() == 0 ? 0 : -1;
            dirty = true;
        }
    }

    private void recompute(Minecraft mc) {
        plan.clear();
        planCount = 0;
        clearGhost();
        clearSplineGhost();
        if (mc.level == null || control.size() < 2) {
            return;
        }
        WorldView view = new WorldView(mc.level);

        long t0 = Profiler.timeStart();
        List<Vec3> samples = adaptive.get()
                ? AdaptiveSampler.sample(control, density[0])
                : Spline.sample(control, density[0]);
        Profiler.timeEnd("sample", t0);
        t0 = Profiler.timeStart();
        List<BlockPos> trace = Spline.voxelize(samples);
        Profiler.timeEnd("voxelize", t0);
        for (BlockPos v : trace) {
            view.put(v.getX(), v.getY(), v.getZ(), Blocks.WHITE_WOOL.defaultBlockState());
        }

        dugPositions.clear();
        if (groundSnap.get()) {
            trace = Grounding.apply(view, trace,
                    smoothRidges.get() ? dugPositions : null);
        }
        if (purgeCorners.get()) {
            trace = LCorners.purge(view, trace);
        }
        if (groundSnap.get()) {
            trace = Grounding.apply(view, trace,
                    smoothRidges.get() ? dugPositions : null);
        }
        trace = Grounding.flattenTeeth(view, trace);
        splineLength = trace.size();

        int islands = countIslands(trace);
        if (islands > 1) {
            setStatus(tr("ui.disconnected", islands), true);
        }

        t0 = Profiler.timeStart();
        TrackModel model = new TrackModel(view, trace, overrideMode);
        if (options.style == DesignOptions.Style.CLASSIC) {
            new ClassicDesign().emitCases(model, options, plan);
        } else {
            new NatureDesign().emitCases(model, options, plan);
        }

        Profiler.timeEnd("design", t0);
        for (long k : dugPositions) {
            if (!plan.containsKey(k)) {
                plan.put(k, Blocks.AIR.defaultBlockState());
            }
        }
        planCount = plan.size();

        if (showGhost.get() && plan.size() <= MAX_GHOST_BLOCKS) {
            for (Map.Entry<Long, BlockState> e : plan.entrySet()) {
                setGhost(e.getKey().longValue(), e.getValue());
            }
            for (BlockPos p : control) {
                setGhost(p.asLong(), Blocks.ORANGE_WOOL.defaultBlockState());
            }
        }
        if (showSplineGhost.get() && !trace.isEmpty() && plan.size() <= MAX_GHOST_BLOCKS) {
            splineGhost = Axiom.createBooleanRegion();
            if (splineGhost != null) {
                for (BlockPos v : trace) {
                    splineGhost.add(v.getX(), v.getY(), v.getZ());
                }
            }
        }
        if (plan.size() > MAX_PLAN_BLOCKS) {
            setStatus(tr("ui.plan_too_big", plan.size(), MAX_PLAN_BLOCKS), true);
        } else if (statusError) {
            status = "";
            statusError = false;
        }
    }

    private void confirm(Minecraft mc) {
        if (dirty) {
            dirty = false;
            recompute(mc);
        }
        if (plan.isEmpty()) {
            setStatus(tr("ui.nothing"), true);
            return;
        }
        if (plan.size() > MAX_PLAN_BLOCKS) {
            setStatus(tr("ui.plan_too_big", plan.size(), MAX_PLAN_BLOCKS), true);
            return;
        }
        BlockRegion region = Axiom.createBlockRegion();
        for (Map.Entry<Long, BlockState> e : plan.entrySet()) {
            long k = e.getKey().longValue();
            region.addBlock(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k),
                    e.getValue());
        }
        persistConfig();
        Axiom.push(region);
        int placed = plan.size();
        reset();
        setStatus(tr("ui.built", placed), false);
        dirty = true;
    }

    private int countIslands(java.util.List<BlockPos> trace) {
        int n = trace.size();
        if (n == 0) {
            return 0;
        }
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap idx =
                new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        idx.defaultReturnValue(-1);
        for (int i = 0; i < n; i++) {
            idx.put(trace.get(i).asLong(), i);
        }
        UnionFind uf = new UnionFind(n);
        for (int i = 0; i < n; i++) {
            BlockPos p = trace.get(i);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        int j = idx.get(BlockPos.asLong(p.getX() + dx,
                                p.getY() + dy, p.getZ() + dz));
                        if (j >= 0) {
                            uf.union(i, j);
                        }
                    }
                }
            }
        }
        return uf.components();
    }

    /** Retire les points de controle quasi colineaires (RDP, 0.5 bloc). */
    private void simplifyControl() {
        java.util.List<Vec3> pts = new java.util.ArrayList<>();
        for (BlockPos p : control) {
            pts.add(Vec3.atCenterOf(p));
        }
        java.util.List<Vec3> kept = Rdp.simplify(pts, 0.5);
        if (kept.size() < control.size()) {
            control.clear();
            for (Vec3 v : kept) {
                control.add(BlockPos.containing(v));
            }
            setStatus(tr("ui.points", control.size()), false);
            dirty = true;
        }
    }

    private void setGhost(long key, BlockState state) {
        if (!phantomAcquired) {
            Axiom.acquireChunkRenderOverrider("bte_railpathtool");
            phantomAcquired = true;
        }
        Axiom.setBlockRenderOverride(BlockPos.getX(key), BlockPos.getY(key),
                BlockPos.getZ(key), state);
        ghostPositions.add(key);
    }

    private void clearGhost() {
        for (long key : ghostPositions) {
            Axiom.setBlockRenderOverride(BlockPos.getX(key), BlockPos.getY(key),
                    BlockPos.getZ(key), null);
        }
        ghostPositions.clear();
    }

    private void clearSplineGhost() {
        if (splineGhost != null) {
            splineGhost.close();
            splineGhost = null;
        }
    }

    private void setStatus(String s, boolean error) {
        status = s;
        statusError = error;
    }

    private static String tr(String suffix, Object... args) {
        return net.minecraft.client.resources.language.I18n.get(
                "bte_railpathtool." + suffix, args);
    }
}
