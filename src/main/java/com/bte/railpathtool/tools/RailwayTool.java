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
import com.moulberry.axiomclientapi.regions.BlockRegion;
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
    private final ImBoolean purgeCorners = new ImBoolean(true);
    private final ImBoolean showGhost = new ImBoolean(true);

    private final DesignOptions options = new DesignOptions();
    private TrackModel.OverrideMode overrideMode = TrackModel.OverrideMode.AUTO;
    private final int[][] soilPercentUI;

    private final Long2ObjectOpenHashMap<BlockState> plan = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet ghostPositions = new LongOpenHashSet();
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
        control.clear();
        plan.clear();
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
        ImGui.text(tr("ui.points", control.size()));
        if (!status.isEmpty()) {
            ImGui.textWrapped(status);
        }

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
        ImGui.textDisabled(tr("ui.hint"));
        ImGui.separator();

        ImGui.text(tr("ui.gen"));
        if (ImGui.sliderInt(tr("ui.density"), density, 2, 12)) {
            dirty = true;
        }
        if (ImGui.checkbox(tr("ui.ground_snap"), groundSnap)) {
            dirty = true;
        }
        if (ImGui.checkbox(tr("ui.purge_corners"), purgeCorners)) {
            dirty = true;
        }
        ImGui.checkbox(tr("ui.ghost"), showGhost);
        ImGui.separator();

        ImGui.text(tr("ui.style"));
        if (ImGui.radioButton(tr("ui.style.classic"),
                options.style == DesignOptions.Style.CLASSIC)) {
            options.style = DesignOptions.Style.CLASSIC;
            dirty = true;
        }
        ImGui.sameLine();
        if (ImGui.radioButton(tr("ui.style.nature"),
                options.style == DesignOptions.Style.NATURE)) {
            options.style = DesignOptions.Style.NATURE;
            dirty = true;
        }

        if (options.style == DesignOptions.Style.CLASSIC) {
            drawClassicOptions();
        }
        ImGui.separator();

        ImGui.text(tr("ui.orientation"));
        TrackModel.OverrideMode[] modes = TrackModel.OverrideMode.values();
        String[] labels = {tr("ui.orient.auto"), tr("ui.orient.ns"),
                tr("ui.orient.ew"), tr("ui.orient.diag")};
        for (int i = 0; i < modes.length; i++) {
            if (i > 0) {
                ImGui.sameLine();
            }
            if (ImGui.radioButton(labels[i], overrideMode == modes[i])) {
                overrideMode = modes[i];
                dirty = true;
            }
        }
    }

    private void drawClassicOptions() {
        ImGui.text(tr("ui.theme"));
        if (ImGui.radioButton(tr("ui.theme.dark"),
                options.theme == DesignOptions.Theme.DARK)) {
            options.theme = DesignOptions.Theme.DARK;
            dirty = true;
        }
        ImGui.sameLine();
        if (ImGui.radioButton(tr("ui.theme.light"),
                options.theme == DesignOptions.Theme.LIGHT)) {
            options.theme = DesignOptions.Theme.LIGHT;
            dirty = true;
        }

        ImGui.text(tr("ui.fill"));
        if (ImGui.radioButton(tr("ui.fill.uniform"),
                options.fillMode == DesignOptions.FillMode.UNIFORM)) {
            options.fillMode = DesignOptions.FillMode.UNIFORM;
            dirty = true;
        }
        ImGui.sameLine();
        if (ImGui.radioButton(tr("ui.fill.random"),
                options.fillMode == DesignOptions.FillMode.RANDOM)) {
            options.fillMode = DesignOptions.FillMode.RANDOM;
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

        ImGui.text(tr("ui.height"));
        if (ImGui.radioButton(tr("ui.height.surface"), options.baseDy == 0)) {
            options.baseDy = 0;
            dirty = true;
        }
        ImGui.sameLine();
        if (ImGui.radioButton(tr("ui.height.buried"), options.baseDy == -1)) {
            options.baseDy = -1;
            dirty = true;
        }
    }

    private void recompute(Minecraft mc) {
        plan.clear();
        clearGhost();
        if (mc.level == null || control.size() < 2) {
            return;
        }
        WorldView view = new WorldView(mc.level);

        List<Vec3> samples = Spline.sample(control, density[0]);
        List<BlockPos> trace = Spline.voxelize(samples);
        for (BlockPos v : trace) {
            view.put(v.getX(), v.getY(), v.getZ(), Blocks.WHITE_WOOL.defaultBlockState());
        }

        if (groundSnap.get()) {
            trace = Grounding.apply(view, trace);
        }
        if (purgeCorners.get()) {
            trace = LCorners.purge(view, trace);
        }

        TrackModel model = new TrackModel(view, trace, overrideMode);
        if (options.style == DesignOptions.Style.CLASSIC) {
            new ClassicDesign().emitCases(model, options, plan);
        } else {
            new NatureDesign().emitCases(model, options, plan);
        }

        if (showGhost.get() && plan.size() <= MAX_GHOST_BLOCKS) {
            for (Map.Entry<Long, BlockState> e : plan.entrySet()) {
                setGhost(e.getKey().longValue(), e.getValue());
            }
            for (BlockPos p : control) {
                setGhost(p.asLong(), Blocks.ORANGE_WOOL.defaultBlockState());
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

    private void setStatus(String s, boolean error) {
        status = s;
        statusError = error;
    }

    private static String tr(String suffix, Object... args) {
        return net.minecraft.client.resources.language.I18n.get(
                "bte_railpathtool." + suffix, args);
    }
}
