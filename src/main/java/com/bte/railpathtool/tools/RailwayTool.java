package com.bte.railpathtool.tools;

import com.bte.railpathtool.axiom.Axiom;
import com.bte.railpathtool.design.ClassicDesign;
import com.bte.railpathtool.design.DesignOptions;
import com.bte.railpathtool.design.NatureDesign;
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

    private final DesignOptions options = new DesignOptions();
    private TrackModel.OverrideMode overrideMode = TrackModel.OverrideMode.AUTO;
    private final int[][] soilPercentUI;

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
        soilPercentUI = new int[options.soilSlots.length][1];
        for (int i = 0; i < options.soilSlots.length; i++) {
            soilPercentUI[i][0] = options.soilSlots[i].percent;
        }
    }

    @Override
    public String name() {
        return tr("tool.name");
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
        int[] styleIdx = {options.style == DesignOptions.Style.CLASSIC ? 0 : 1};
        if (ImGui.combo(tr("ui.style"), styleIdx, styles)) {
            options.style = styleIdx[0] == 0
                    ? DesignOptions.Style.CLASSIC : DesignOptions.Style.NATURE;
            dirty = true;
        }
        String[] orients = {tr("ui.orient.auto"), tr("ui.orient.ns"),
                tr("ui.orient.ew"), tr("ui.orient.diag")};
        TrackModel.OverrideMode[] modes = TrackModel.OverrideMode.values();
        int[] orientIdx = {indexOf(modes, overrideMode)};
        if (ImGui.combo(tr("ui.orientation"), orientIdx, orients)) {
            overrideMode = modes[orientIdx[0]];
            dirty = true;
        }

        if (options.style == DesignOptions.Style.CLASSIC) {
            drawClassicOptions();
        }
    }

    private static int indexOf(TrackModel.OverrideMode[] modes, TrackModel.OverrideMode mode) {
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == mode) {
                return i;
            }
        }
        return 0;
    }

    private void drawClassicOptions() {
        String[] themes = {tr("ui.theme.dark"), tr("ui.theme.light")};
        int[] themeIdx = {options.theme == DesignOptions.Theme.DARK ? 0 : 1};
        if (ImGui.combo(tr("ui.theme"), themeIdx, themes)) {
            options.theme = themeIdx[0] == 0
                    ? DesignOptions.Theme.DARK : DesignOptions.Theme.LIGHT;
            dirty = true;
        }
        String[] fills = {tr("ui.fill.random"), tr("ui.fill.uniform")};
        int[] fillIdx = {options.fillMode == DesignOptions.FillMode.RANDOM ? 0 : 1};
        if (ImGui.combo(tr("ui.fill"), fillIdx, fills)) {
            options.fillMode = fillIdx[0] == 0
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
        int[] heightIdx = {options.baseDy == 0 ? 0 : 1};
        if (ImGui.combo(tr("ui.height"), heightIdx, heights)) {
            options.baseDy = heightIdx[0] == 0 ? 0 : -1;
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

        List<Vec3> samples = Spline.sample(control, density[0]);
        List<BlockPos> trace = Spline.voxelize(samples);
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
        splineLength = trace.size();

        TrackModel model = new TrackModel(view, trace, overrideMode);
        if (options.style == DesignOptions.Style.CLASSIC) {
            new ClassicDesign().emitCases(model, options, plan);
        } else {
            new NatureDesign().emitCases(model, options, plan);
        }

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
        Axiom.push(region);
        int placed = plan.size();
        reset();
        setStatus(tr("ui.built", placed), false);
        dirty = true;
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
