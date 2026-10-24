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
import imgui.moulberry92.ImVec4;
import imgui.moulberry92.flag.ImGuiCol;
import imgui.moulberry92.flag.ImGuiTreeNodeFlags;
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

    private static final float LABEL_W = 96.0f;
    private static final ImVec4 ACCENT = new ImVec4(0.91f, 0.66f, 0.26f, 1.0f);
    private static final ImVec4 OK_TEXT = new ImVec4(0.55f, 0.85f, 0.58f, 1.0f);
    private static final ImVec4 WARN_TEXT = new ImVec4(0.93f, 0.78f, 0.35f, 1.0f);
    private static final ImVec4 ERROR_TEXT = new ImVec4(0.93f, 0.47f, 0.47f, 1.0f);

    private static final int MAX_PLAN_BLOCKS = 60_000;
    private static final int MAX_GHOST_BLOCKS = 6_000;
    private static final String OVERRIDER_ID = "bte_railpathtool";

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
    private boolean ghostDenied = false;
    private String status = "";
    private boolean statusError = false;
    private String warning = "";

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
        warning = "";
        releaseGhost();
    }

    @Override
    public void render(Camera camera, float partialTick, long nanos,
                       PoseStack poseStack, Matrix4f projection) {
        if (dirty) {
            dirty = false;
            try {
                recompute(Minecraft.getInstance());
            } catch (RuntimeException e) {
                plan.clear();
                planCount = 0;
                splineLength = 0;
                warning = "";
                clearGhost();
                releaseGhost();
                clearSplineGhost();
                setStatus(tr("ui.error", String.valueOf(e.getMessage())), true);
            }
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

        drawHeader();
        drawStatus();
        drawHelp();

        drawSummary();
        drawActions(mc);

        drawTrack();
        drawLook();
        drawPresets();
        drawDebug();
    }

    private void drawHeader() {
        ImGui.spacing();
        ImGui.textColored(ACCENT, tr("tool.name"));
        wrap(() -> ImGui.textDisabled(tr("ui.subtitle")));
        ImGui.spacing();
    }

    private void drawStatus() {
        if (!warning.isEmpty()) {
            wrap(() -> ImGui.textColored(WARN_TEXT, "[!] " + warning));
        }
        if (!status.isEmpty()) {
            wrap(() -> ImGui.textColored(statusError ? ERROR_TEXT : OK_TEXT,
                    (statusError ? "[!] " : "[i] ") + status));
        }
        if (warning.isEmpty() && status.isEmpty()) {
            wrap(() -> ImGui.textDisabled(tr("ui.idle")));
        }
    }

    /** Long sentences wrap at the panel edge instead of being clipped. */
    private static void wrap(Runnable text) {
        ImGui.pushTextWrapPos(0.0f);
        text.run();
        ImGui.popTextWrapPos();
    }

    private void drawHelp() {
        if (!ImGui.collapsingHeader(tr("ui.howto"), ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }
        ImGui.textWrapped(tr("ui.help_1"));
        ImGui.textWrapped(tr("ui.help_2"));
        ImGui.textWrapped(tr("ui.help_3"));
        ImGui.spacing();
    }

    private void drawSummary() {
        ImGui.separatorText(tr("ui.section.line"));
        rowStart(tr("ui.points_short"));
        ImGui.text(String.valueOf(control.size()));
        if (planCount <= 0) {
            ImGui.spacing();
            return;
        }
        rowStart(tr("ui.planned_short"));
        ImGui.text(String.valueOf(planCount));
        if (splineLength > 0) {
            rowStart(tr("ui.length_short"));
            ImGui.text(tr("ui.blocks", splineLength));
        }
        ImGui.spacing();
        float fraction = Math.min(1.0f, planCount / (float) MAX_PLAN_BLOCKS);
        ImGui.pushStyleColor(ImGuiCol.PlotHistogram, ACCENT);
        ImGui.progressBar(fraction, tr("ui.plan_budget", planCount, MAX_PLAN_BLOCKS));
        ImGui.popStyleColor();
        ImGui.spacing();
    }

    private void drawActions(Minecraft mc) {
        float full = ImGui.getContentRegionAvailX();
        float height = ImGui.getFrameHeight() * 1.35f;
        if (accentButton(tr("ui.action.build"), full, height, tr("ui.tip_build"))) {
            confirm(mc);
        }
        if (control.size() < 2) {
            ImGui.textDisabled(tr("ui.need_points"));
        }

        float gap = 8.0f;
        float third = (ImGui.getContentRegionAvailX() - 2.0f * gap) / 3.0f;
        if (ImGui.button(tr("ui.action.undo") + "##undo", third, 0.0f)) {
            callDelete();
        }
        ImGui.setItemTooltip(tr("ui.tip_undo_point"));
        ImGui.sameLine();
        ImGui.beginDisabled(control.size() <= 3);
        if (ImGui.button(tr("ui.action.simplify") + "##simplify", third, 0.0f)) {
            simplifyControl();
        }
        ImGui.endDisabled();
        ImGui.setItemTooltip(tr("ui.tip_simplify"));
        ImGui.sameLine();
        if (ImGui.button(tr("ui.action.clear") + "##clear", third, 0.0f)) {
            control.clear();
            dirty = true;
        }
        ImGui.setItemTooltip(tr("ui.tip_clear"));
        ImGui.spacing();
    }

    private void drawTrack() {
        ImGui.separatorText(tr("ui.section.track"));

        rowStart(tr("ui.density_short"));
        ImGui.setNextItemWidth(-1.0f);
        if (ImGui.sliderInt("##density", density, 2, 12)) {
            dirty = true;
        }
        ImGui.setItemTooltip(tr("ui.tip_density"));

        rowStart(tr("ui.style_short"));
        ImGui.setNextItemWidth(-1.0f);
        String[] styles = {tr("ui.style.classic"), tr("ui.style.nature")};
        if (ImGui.combo("##style", styleSel, styles)) {
            options.style = styleSel.get() == 0
                    ? DesignOptions.Style.CLASSIC : DesignOptions.Style.NATURE;
            dirty = true;
        }
        ImGui.setItemTooltip(tr("ui.tip_style"));

        rowStart(tr("ui.orient_short"));
        ImGui.setNextItemWidth(-1.0f);
        String[] orients = {tr("ui.orient.auto"), tr("ui.orient.ns"),
                tr("ui.orient.ew"), tr("ui.orient.diag")};
        TrackModel.OverrideMode[] modes = TrackModel.OverrideMode.values();
        if (ImGui.combo("##orient", orientSel, orients)) {
            overrideMode = modes[orientSel.get()];
            dirty = true;
        }
        ImGui.setItemTooltip(tr("ui.tip_orient"));

        ImGui.spacing();
        float half = (ImGui.getContentRegionAvailX() - 10.0f) * 0.5f;
        if (ImGui.checkbox(tr("ui.opt.snap"), groundSnap)) {
            dirty = true;
        }
        ImGui.setItemTooltip(tr("ui.tip_snap"));
        ImGui.sameLine(half + 10.0f);
        if (ImGui.checkbox(tr("ui.opt.adaptive"), adaptive)) {
            dirty = true;
        }
        ImGui.setItemTooltip(tr("ui.tip_adaptive"));

        if (groundSnap.get()) {
            if (ImGui.checkbox(tr("ui.opt.ridges"), smoothRidges)) {
                dirty = true;
            }
            ImGui.setItemTooltip(tr("ui.tip_ridges"));
            ImGui.sameLine(half + 10.0f);
        }
        if (ImGui.checkbox(tr("ui.opt.corners"), purgeCorners)) {
            dirty = true;
        }
        ImGui.setItemTooltip(tr("ui.tip_corners"));

        if (ImGui.checkbox(tr("ui.opt.ghost"), showGhost)) {
            dirty = true;
        }
        ImGui.setItemTooltip(tr("ui.tip_ghost"));
        ImGui.sameLine(half + 10.0f);
        if (ImGui.checkbox(tr("ui.opt.spline"), showSplineGhost)) {
            dirty = true;
        }
        ImGui.setItemTooltip(tr("ui.tip_spline"));

        ImGui.spacing();
    }

    private void drawLook() {
        if (options.style != DesignOptions.Style.CLASSIC) {
            return;
        }
        if (!ImGui.collapsingHeader(tr("ui.section.look"))) {
            return;
        }
        rowStart(tr("ui.theme_short"));
        ImGui.setNextItemWidth(-1.0f);
        String[] themes = {tr("ui.theme.dark"), tr("ui.theme.light")};
        if (ImGui.combo("##theme", themeSel, themes)) {
            options.theme = themeSel.get() == 0
                    ? DesignOptions.Theme.DARK : DesignOptions.Theme.LIGHT;
            dirty = true;
        }

        rowStart(tr("ui.fill_short"));
        ImGui.setNextItemWidth(-1.0f);
        String[] fills = {tr("ui.fill.random"), tr("ui.fill.uniform")};
        if (ImGui.combo("##fill", fillSel, fills)) {
            options.fillMode = fillSel.get() == 0
                    ? DesignOptions.FillMode.RANDOM : DesignOptions.FillMode.UNIFORM;
            dirty = true;
        }

        if (options.fillMode == DesignOptions.FillMode.UNIFORM) {
            rowStart(tr("ui.block_short"));
            ImGui.textWrapped(options.uniformBlock.getBlock().getName().getString());
            if (ImGui.button(tr("ui.pick_active") + "##uniform")) {
                BlockState picked = activeBlockOrNull();
                if (picked != null) {
                    options.uniformBlock = picked;
                    dirty = true;
                }
            }
            ImGui.setItemTooltip(tr("ui.tip_pick"));
        } else {
            ImGui.textDisabled(tr("ui.fill.random_hint"));
            for (int i = 0; i < options.soilSlots.length; i++) {
                final int slot = i;
                ImGui.pushID("soil" + i);
                ImGui.alignTextToFramePadding();
                ImGui.textDisabled(options.soilSlots[i].state.getBlock()
                        .getName().getString());
                ImGui.sameLine(ImGui.getContentRegionMaxX() - 42.0f);
                if (ImGui.smallButton(tr("ui.pick") + "##pick")) {
                    BlockState picked = activeBlockOrNull();
                    if (picked != null) {
                        options.soilSlots[slot].state = picked;
                        dirty = true;
                    }
                }
                ImGui.setItemTooltip(tr("ui.tip_pick"));
                ImGui.setNextItemWidth(-1.0f);
                if (ImGui.sliderInt("##share", soilPercentUI[i], 0, 100, "%d%%")) {
                    options.soilSlots[i].percent = soilPercentUI[i][0];
                    dirty = true;
                }
                ImGui.popID();
            }
        }

        rowStart(tr("ui.height_short"));
        ImGui.setNextItemWidth(-1.0f);
        String[] heights = {tr("ui.height.surface"), tr("ui.height.buried")};
        if (ImGui.combo("##height", heightSel, heights)) {
            options.baseDy = heightSel.get() == 0 ? 0 : -1;
            dirty = true;
        }
        ImGui.spacing();
    }

    /** The block selected in Axiom's palette, or null when it is unusable. */
    private static BlockState activeBlockOrNull() {
        BlockState picked = Axiom.getActiveBlock();
        return picked == null || picked.isAir() ? null : picked;
    }

    private void drawPresets() {
        if (!ImGui.collapsingHeader(tr("ui.section.presets"))) {
            return;
        }
        float width = (ImGui.getContentRegionAvailX() - 44.0f) * 0.5f;
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            ImGui.pushID("preset" + i);
            ImGui.alignTextToFramePadding();
            ImGui.textDisabled("#" + (i + 1));
            ImGui.sameLine(38.0f);
            if (ImGui.button(tr("ui.preset_load"), width, 0.0f)) {
                applyData(preset(idx));
            }
            ImGui.sameLine();
            if (ImGui.button(tr("ui.preset_save"), width, 0.0f)) {
                setPreset(idx, snapshotData());
                persistConfig();
            }
            ImGui.popID();
        }
        ImGui.spacing();
    }

    private RailConfig.Data preset(int index) {
        return switch (index) {
            case 1 -> config.preset2;
            case 2 -> config.preset3;
            default -> config.preset1;
        };
    }

    private void setPreset(int index, RailConfig.Data data) {
        switch (index) {
            case 1 -> config.preset2 = data;
            case 2 -> config.preset3 = data;
            default -> config.preset1 = data;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
        density[0] = clamp(d.density, 2, 12);
        styleSel.set(clamp(d.styleIndex, 0, 1));
        themeSel.set(clamp(d.themeIndex, 0, 1));
        fillSel.set(clamp(d.fillIndex, 0, 1));
        heightSel.set(clamp(d.heightIndex, 0, 1));
        orientSel.set(clamp(d.orientIndex, 0, 3));
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
                clamp(orientSel.get(), 0, TrackModel.OverrideMode.values().length - 1)];
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

    private void drawDebug() {
        if (!ImGui.collapsingHeader(tr("ui.debug"))) {
            return;
        }
        for (Map.Entry<String, Double> e : Profiler.summaryMs().entrySet()) {
            ImGui.textDisabled(String.format(java.util.Locale.ROOT,
                    "%s: %.3f ms", e.getKey(), e.getValue()));
        }
    }

    /** Primary action: full-width button in the rail accent colour. */
    private boolean accentButton(String label, float width, float height, String tooltip) {
        ImGui.pushStyleColor(ImGuiCol.Button, 0.72f, 0.45f, 0.12f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.86f, 0.56f, 0.18f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.62f, 0.37f, 0.08f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.Text, 0.10f, 0.07f, 0.03f, 1.0f);
        boolean pressed = ImGui.button(label, width, height);
        ImGui.setItemTooltip(tooltip);
        ImGui.popStyleColor(4);
        return pressed;
    }

    /** Label column shared by every option row. */
    private static void rowStart(String label) {
        ImGui.alignTextToFramePadding();
        ImGui.textDisabled(label);
        ImGui.sameLine(LABEL_W);
    }

    private void recompute(Minecraft mc) {
        plan.clear();
        planCount = 0;
        splineLength = 0;
        warning = "";
        clearGhost();
        ghostDenied = false;
        clearSplineGhost();
        if (mc.level == null || control.size() < 2) {
            releaseGhost();
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
            // Never over existing rail or wool (junctions); natural terrain only
            // (mirror of rail_sim.build_trace_seq).
            if (com.bte.railpathtool.track.Grounding.isWoolLayable(
                    view.at(v.getX(), v.getY(), v.getZ()))) {
                view.put(v.getX(), v.getY(), v.getZ(),
                        Blocks.WHITE_WOOL.defaultBlockState());
            }
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
        trace = Grounding.dedupeColumns(view, trace);
        splineLength = trace.size();

        int islands = countIslands(trace);
        warning = islands > 1 ? tr("ui.disconnected", islands) : "";

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

        if (showGhost.get()) {
            // Too large for a full ghost: regular sampling over the whole line so the
            // preview never disappears.
            int stride = plan.size() > MAX_GHOST_BLOCKS
                    ? (int) Math.ceil(plan.size() / (double) MAX_GHOST_BLOCKS)
                    : 1;
            int idx = 0;
            for (Map.Entry<Long, BlockState> e : plan.entrySet()) {
                if ((idx++ % stride) == 0) {
                    setGhost(e.getKey().longValue(), e.getValue());
                }
            }
            for (BlockPos p : control) {
                setGhost(p.asLong(), Blocks.ORANGE_WOOL.defaultBlockState());
            }
        }
        if (showSplineGhost.get() && !trace.isEmpty()) {
            splineGhost = Axiom.createBooleanRegion();
            if (splineGhost != null) {
                int stride = trace.size() > MAX_GHOST_BLOCKS
                        ? (int) Math.ceil(trace.size() / (double) MAX_GHOST_BLOCKS)
                        : 1;
                for (int i = 0; i < trace.size(); i += stride) {
                    BlockPos v = trace.get(i);
                    splineGhost.add(v.getX(), v.getY(), v.getZ());
                }
            }
        }
        if (ghostPositions.isEmpty()) {
            releaseGhost();
        }
        if (plan.size() > MAX_PLAN_BLOCKS) {
            setStatus(tr("ui.plan_too_big", plan.size(), MAX_PLAN_BLOCKS), true);
        }
    }

    private void confirm(Minecraft mc) {
        if (mc.level == null) {
            setStatus(tr("ui.no_target"), true);
            return;
        }
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
        try {
            Axiom.push(region);
        } catch (RuntimeException e) {
            setStatus(tr("ui.error", String.valueOf(e.getMessage())), true);
            return;
        }
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

    /** Removes nearly collinear control points (RDP, 0.5 block). */
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
            if (ghostDenied) {
                return;
            }
            phantomAcquired = Axiom.acquireChunkRenderOverrider(OVERRIDER_ID);
            if (!phantomAcquired) {
                // Another tool owns the lock: try once per recompute, not once
                // per ghost block.
                ghostDenied = true;
                return;
            }
        }
        Axiom.setBlockRenderOverride(BlockPos.getX(key), BlockPos.getY(key),
                BlockPos.getZ(key), state);
        ghostPositions.add(key);
    }

    private void releaseGhost() {
        if (phantomAcquired) {
            Axiom.releaseChunkRenderOverrider(OVERRIDER_ID);
            phantomAcquired = false;
        }
    }

    private void clearGhost() {
        if (!phantomAcquired) {
            ghostPositions.clear();
            return;
        }
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
