package com.bte.railpathtool.tools;

import com.moulberry.axiom.RayCaster;
import com.moulberry.axiom.UserAction;
import com.moulberry.axiom.UserAction.ActionResult;
import com.moulberry.axiom.render.AxiomWorldRenderContext;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.tools.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class RailPathTool implements Tool {

    private enum TrackType { NS, EW, DIAG }

    private static final String[] STYLE_NAMES = {"Classic", "Natural"};

    private final List<BlockPos> points = new ArrayList<>();
    private boolean dirty = true;
    private final int[] density = {10};
    private final boolean[] snapGround = {false};
    private final boolean[] livePreview = {true};
    private final int[] styleIndex = {0};
    private final int[] themeIndex = {0};
    private final int[] fillMode = {0};
    private final int[] pct1={45}, pct2={40}, pct3={10}, pct4={4}, pct5={1};
    private final Map<BlockPos, BlockState> previewBlocks = new HashMap<>();
    private final Deque<Map<BlockPos, BlockState>> undoStack = new ArrayDeque<>();
    private Map<BlockPos, TrackType> typeMap = new HashMap<>();
    private List<BlockPos> trackList = new ArrayList<>();
    private final Random rng = new Random();

    private BlockState _coralNS, _coralEW;
    private BlockState _wallNS, _wallEW;
    private BlockState _wallNE, _wallNW, _wallSE, _wallSW;
    private BlockState _sideE, _sideW, _sideN, _sideS;

    private static boolean IG_OK = false;
    private static Class<?> IG;
    private static Class<?> ig() {
        if (!IG_OK) { IG_OK=true; try { IG=Class.forName("imgui.moulberry92.ImGui"); } catch(Exception ignored){} }
        return IG;
    }
    private static void   igC(String m,Class<?>[] t,Object...a){try{Class<?> c=ig();if(c!=null)c.getMethod(m,t).invoke(null,a);}catch(Exception ignored){}}
    private static boolean igB(String m,Class<?>[] t,Object...a){try{Class<?> c=ig();if(c!=null)return(boolean)c.getMethod(m,t).invoke(null,a);}catch(Exception ignored){}return false;}
    private static void    igT(String s){igC("text",new Class<?>[]{String.class},s);}
    private static void    igD(String s){igC("textDisabled",new Class<?>[]{String.class},s);}
    private static void    igS(){igC("separator",new Class<?>[]{});}
    private static void    igL(){igC("sameLine",new Class<?>[]{});}
    private static boolean igSI(String l,int[]v,int a,int b){return igB("sliderInt",new Class<?>[]{String.class,int[].class,int.class,int.class},l,v,a,b);}
    private static boolean igCB(String l,boolean[]v){return igB("checkbox",new Class<?>[]{String.class,boolean[].class},l,v);}
    private static boolean igRB(String l,boolean a){return igB("radioButton",new Class<?>[]{String.class,boolean.class},l,a);}
    private static boolean igBT(String l){return igB("button",new Class<?>[]{String.class},l);}

    @Override public String name() { return "BTE Rail Path"; }
    @Override public char iconChar() { return '\ue900'; }
    @Override public String keybindId() { return "bte_rail_path"; }
    @Override public EnumSet<AxiomPermission> requiredPermissions() {
        return EnumSet.of(AxiomPermission.TOOL_PATH, AxiomPermission.BUILD_SECTION);
    }
    @Override public void writeSettings(CompoundTag t) {}
    @Override public void loadSettings(CompoundTag t) {}
    @Override public void reset() { points.clear(); previewBlocks.clear(); dirty=true; }

    @Override
    public void render(AxiomWorldRenderContext rc) {
        if (dirty) { previewBlocks.clear(); if (points.size()>=2) buildFullRail(previewBlocks); dirty=false; }
        if (livePreview[0] && !previewBlocks.isEmpty())
            for (BlockPos p : previewBlocks.keySet()) Tool.renderRaycastOverlay(rc, p);
        for (BlockPos p : points) Tool.renderRaycastOverlay(rc, p);
        Tool.renderRaycastOverlay(rc, Tool.raycastBlock());
    }

    @Override
    public ActionResult callAction(UserAction a, Object o) {
        String n = a.name();
        if ("RIGHT_MOUSE".equals(n)) {
            RayCaster.RaycastResult r = Tool.raycastBlock(false, true, true);
            if (r!=null && r.blockPos()!=null) { points.add(r.blockPos().above()); dirty=true; }
            return ActionResult.USED_STOP;
        }
        if ("ENTER".equals(n))  { if (points.size()<2) return ActionResult.NOT_HANDLED; doConfirm(); return ActionResult.USED_STOP; }
        if ("DELETE".equals(n)) { if (points.isEmpty()){reset();return ActionResult.USED_STOP;} points.remove(points.size()-1); dirty=true; return ActionResult.USED_STOP; }
        if ("ESCAPE".equals(n)) { if (!points.isEmpty()){points.remove(points.size()-1);dirty=true;return ActionResult.USED_STOP;} return ActionResult.NOT_HANDLED; }
        if ("UNDO".equals(n))   { if (!undoStack.isEmpty()){doUndo();return ActionResult.USED_STOP;} return ActionResult.NOT_HANDLED; }
        return ActionResult.NOT_HANDLED;
    }

    @Override
    public void displayImguiOptions() {
        igT("=== BTE Rail Path Tool ==="); igT("Points: "+points.size()); igS();
        boolean ch=false;
        if(igSI("Density (pts/block)",density,2,32)) ch=true;
        if(igCB("Snap to ground",snapGround)) ch=true; igL(); igCB("Live preview",livePreview); igS();
        igT("Style:");
        for(int i=0;i<STYLE_NAMES.length;i++){if(i>0)igL();if(igRB(STYLE_NAMES[i],styleIndex[0]==i)){styleIndex[0]=i;ch=true;}}
        igD(styleIndex[0]==0?"Coral + walls + shelves":"Lectern + pale moss + leaves"); igS();
        if(styleIndex[0]==0){
            String[]TN={"Dark (mud+shelf)","Light (andesite+door)"}; igT("Theme:");
            for(int i=0;i<TN.length;i++){if(i>0)igL();if(igRB(TN[i],themeIndex[0]==i)){themeIndex[0]=i;ch=true;}}
            igS();
            String[]FN={"Uniform","Random"}; igT("Fill:");
            for(int i=0;i<FN.length;i++){if(i>0)igL();if(igRB(FN[i],fillMode[0]==i)){fillMode[0]=i;ch=true;}}
            if(fillMode[0]==1){
                igT("Custom ground:");
                if(igSI("% Deepslate",pct1,0,100))ch=true;
                if(igSI("% Cobbled Deepslate",pct2,0,100))ch=true;
                if(igSI("% Pale Oak Wood",pct3,0,100))ch=true;
                if(igSI("% Iron Ore",pct4,0,100))ch=true;
                if(igSI("% Coal Ore",pct5,0,100))ch=true;
                igD("Total: "+(pct1[0]+pct2[0]+pct3[0]+pct4[0]+pct5[0])+"% (auto-normalized)");
            }
        }
        if(ch) dirty=true; igS();
        if(points.size()>=2 && igBT("Confirm (Enter)")) doConfirm();
        if(!points.isEmpty()){
            if(igBT("Undo last point")){points.remove(points.size()-1);dirty=true;} igL();
            if(igBT("Reset")) reset();
        }
        if(!undoStack.isEmpty()){
            if(igBT("Undo last rail")) doUndo(); igL();
            igD("("+undoStack.size()+" in history)");
        }
        igS(); igD("Right-click: add point | Enter: confirm | Ctrl+Z: undo");
    }

    @Override public String listenForEnter() { return points.size()>=2?"Confirm path":null; }
    @Override public String listenForEsc()   { return !points.isEmpty()?"Undo last point":null; }
    public static void register() { com.moulberry.axiom.tools.ToolManager.addTool(new RailPathTool()); }

    private void doUndo() {
        Map<BlockPos,BlockState> s=undoStack.poll(); if(s==null) return;
        var l=Minecraft.getInstance().level;
        if(l!=null) for(var e:s.entrySet()) l.setBlock(e.getKey(),e.getValue(),2);
    }

    private void doConfirm() {
        Map<BlockPos,BlockState> nb=new HashMap<>(); buildFullRail(nb);
        if(!nb.isEmpty()){
            var l=Minecraft.getInstance().level;
            if(l!=null){
                Map<BlockPos,BlockState> old=new HashMap<>();
                for(BlockPos p:nb.keySet()) old.put(p,l.getBlockState(p));
                undoStack.push(old);
                for(var e:nb.entrySet()) l.setBlock(e.getKey(),e.getValue(),2);
            }
        }
        reset();
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private void initBlocks() {
        _coralNS = Blocks.DEAD_BUBBLE_CORAL_WALL_FAN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                .setValue(BlockStateProperties.WATERLOGGED, false);
        _coralEW = Blocks.DEAD_BUBBLE_CORAL_WALL_FAN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.WATERLOGGED, false);
        _wallNS = wallConn("north","south");
        _wallEW = wallConn("east","west");
        _wallNE = wallConn("north","east");
        _wallNW = wallConn("north","west");
        _wallSE = wallConn("south","east");
        _wallSW = wallConn("south","west");
        _sideE  = mkSide(Direction.EAST);
        _sideW  = mkSide(Direction.WEST);
        _sideN  = mkSide(Direction.NORTH);
        _sideS  = mkSide(Direction.SOUTH);
    }

    private BlockState wallConn(String d1, String d2) {
        BlockState s = (themeIndex[0]==0 ? Blocks.MUD_BRICK_WALL : Blocks.ANDESITE_WALL)
                .defaultBlockState().setValue(BlockStateProperties.UP, false);
        return setWS(setWS(s, d1, WallSide.TALL), d2, WallSide.TALL);
    }

    private BlockState setWS(BlockState s, String dir, WallSide v) {
        return switch(dir) {
            case "north" -> s.setValue(BlockStateProperties.NORTH_WALL, v);
            case "south" -> s.setValue(BlockStateProperties.SOUTH_WALL, v);
            case "east"  -> s.setValue(BlockStateProperties.EAST_WALL, v);
            case "west"  -> s.setValue(BlockStateProperties.WEST_WALL, v);
            default -> s;
        };
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private BlockState mkSide(Direction facing) {
        if (themeIndex[0]==0) {
            BlockState s = Blocks.SPRUCE_SHELF.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.WATERLOGGED, false);
            try { s = s.setValue(BlockStateProperties.POWERED, true); } catch(Exception ignored) {}
            for (Property<?> p : s.getProperties()) {
                if (p.getName().equals("side_chain")) {
                    for (Object v : p.getPossibleValues())
                        if (v.toString().equalsIgnoreCase("center")) { s=s.setValue((Property)p,(Comparable)v); break; }
                    break;
                }
            }
            return s;
        } else {
            BlockState s = Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
                    .setValue(BlockStateProperties.POWERED, false)
                    .setValue(BlockStateProperties.OPEN, false);
            for (Property<?> p : s.getProperties()) {
                if (p.getName().equals("hinge")) {
                    for (Object v : p.getPossibleValues())
                        if (v.toString().equalsIgnoreCase("left")) { s=s.setValue((Property)p,(Comparable)v); break; }
                    break;
                }
            }
            return s;
        }
    }

    private BlockState pickGround() {
        if (fillMode[0]==0) return Blocks.ORANGE_WOOL.defaultBlockState();
        int tot = Math.max(1, pct1[0]+pct2[0]+pct3[0]+pct4[0]+pct5[0]);
        double n = rng.nextDouble()*100.0;
        double s1 = pct1[0]*100.0/tot;
        double s2 = s1 + pct2[0]*100.0/tot;
        double s3 = s2 + pct3[0]*100.0/tot;
        double s4 = s3 + pct4[0]*100.0/tot;
        if (n<=s1) return Blocks.DEEPSLATE.defaultBlockState();
        if (n<=s2) return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        if (n<=s3) return Blocks.PALE_OAK_WOOD.defaultBlockState();
        if (n<=s4) return Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
        return Blocks.DEEPSLATE_COAL_ORE.defaultBlockState();
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private BlockState leafLitter(int amount, String facing) {
        Direction dir = switch(facing.toLowerCase(Locale.ROOT)) {
            case "south" -> Direction.SOUTH;
            case "east"  -> Direction.EAST;
            case "west"  -> Direction.WEST;
            default      -> Direction.NORTH;
        };
        BlockState s = Blocks.LEAF_LITTER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, dir);
        for (Property<?> p : s.getProperties()) {
            if (p.getName().equals("segment_amount") && p instanceof IntegerProperty ip) {
                int mn = ip.getPossibleValues().stream().mapToInt(v->v).min().orElse(1);
                int mx = ip.getPossibleValues().stream().mapToInt(v->v).max().orElse(4);
                s = s.setValue(ip, Math.max(mn, Math.min(mx, amount)));
                break;
            }
        }
        return s;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private BlockState oakButton(Direction facing) {
        BlockState s = Blocks.OAK_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.POWERED, true);
        for (Property<?> p : s.getProperties()) {
            if (p.getName().equals("face")) {
                for (Object v : p.getPossibleValues())
                    if (v.toString().equalsIgnoreCase("floor")) { s=s.setValue((Property)p,(Comparable)v); break; }
                break;
            }
        }
        return s;
    }

    private boolean isProtected(BlockState s) {
        if (s==null || s.is(Blocks.AIR)) return false;
        return s.equals(_wallNS) || s.equals(_wallEW)
                || s.equals(_wallNE) || s.equals(_wallNW)
                || s.equals(_wallSE) || s.equals(_wallSW)
                || s.equals(_coralNS) || s.equals(_coralEW)
                || s.equals(_sideE) || s.equals(_sideW)
                || s.equals(_sideN) || s.equals(_sideS);
    }

    private void buildCol(Map<BlockPos,BlockState> r, int x, int y, int z, BlockState center) {
        BlockPos above = new BlockPos(x, y+1, z);
        BlockState existing = r.get(above);
        if (existing!=null && isProtected(existing)) return;
        r.put(new BlockPos(x, y+2, z), Blocks.AIR.defaultBlockState());
        r.put(above, center);
        r.put(new BlockPos(x, y, z), pickGround());
    }

    private boolean hasType(int x, int y, int z, TrackType t) {
        return typeMap.getOrDefault(new BlockPos(x,y,z), null)==t;
    }

    private boolean hasTypeNear(int x, int y, int z, TrackType t) {
        for (int dy=-1; dy<=1; dy++)
            if (typeMap.getOrDefault(new BlockPos(x,y+dy,z), null)==t) return true;
        return false;
    }

    private TrackType findTypeNear(int x, int y, int z) {
        for (int dy=-1; dy<=1; dy++) {
            TrackType t = typeMap.getOrDefault(new BlockPos(x,y+dy,z), null);
            if (t!=null) return t;
        }
        return null;
    }

    private static class Agent {
        int dist = 0;
        String dir = "none";
        int len = 1;
    }

    private Agent scanNS_Nord(int x, int y, int z) {
        Agent a = new Agent();
        int curY = y;
        for (int i=1; i<=20; i++) {
            boolean found = false;
            for (int dy : new int[]{0,1,-1}) {
                TrackType t = typeMap.getOrDefault(new BlockPos(x, curY+dy, z-i), null);
                if (t==TrackType.NS) {
                    a.dist++;
                    curY += dy;
                    found = true;
                    break;
                }
            }
            if (found) continue;
            for (int dy : new int[]{0,1,-1}) {
                TrackType te = typeMap.getOrDefault(new BlockPos(x+1, curY+dy, z-i), null);
                if (te==TrackType.NS) {
                    a.dir = "east";
                    int nextY = curY+dy;
                    boolean hasNext = false;
                    for (int dy2 : new int[]{0,1,-1}) {
                        TrackType tn = typeMap.getOrDefault(new BlockPos(x+1, nextY+dy2, z-i-1), null);
                        if (tn==TrackType.NS) { hasNext=true; break; }
                    }
                    a.len = hasNext ? 2 : 1;
                    return a;
                }
            }
            for (int dy : new int[]{0,1,-1}) {
                TrackType tw = typeMap.getOrDefault(new BlockPos(x-1, curY+dy, z-i), null);
                if (tw==TrackType.NS) {
                    a.dir = "west";
                    int nextY = curY+dy;
                    boolean hasNext = false;
                    for (int dy2 : new int[]{0,1,-1}) {
                        TrackType tn = typeMap.getOrDefault(new BlockPos(x-1, nextY+dy2, z-i-1), null);
                        if (tn==TrackType.NS) { hasNext=true; break; }
                    }
                    a.len = hasNext ? 2 : 1;
                    return a;
                }
            }
            break;
        }
        return a;
    }

    private Agent scanNS_Sud(int x, int y, int z) {
        Agent a = new Agent();
        int curY = y;
        for (int i=1; i<=20; i++) {
            boolean found = false;
            for (int dy : new int[]{0,1,-1}) {
                TrackType t = typeMap.getOrDefault(new BlockPos(x, curY+dy, z+i), null);
                if (t==TrackType.NS) {
                    a.dist++;
                    curY += dy;
                    found = true;
                    break;
                }
            }
            if (found) continue;
            for (int dy : new int[]{0,1,-1}) {
                TrackType te = typeMap.getOrDefault(new BlockPos(x+1, curY+dy, z+i), null);
                if (te==TrackType.NS) {
                    a.dir = "east";
                    int nextY = curY+dy;
                    boolean hasNext = false;
                    for (int dy2 : new int[]{0,1,-1}) {
                        TrackType tn = typeMap.getOrDefault(new BlockPos(x+1, nextY+dy2, z+i+1), null);
                        if (tn==TrackType.NS) { hasNext=true; break; }
                    }
                    a.len = hasNext ? 2 : 1;
                    return a;
                }
            }
            for (int dy : new int[]{0,1,-1}) {
                TrackType tw = typeMap.getOrDefault(new BlockPos(x-1, curY+dy, z+i), null);
                if (tw==TrackType.NS) {
                    a.dir = "west";
                    int nextY = curY+dy;
                    boolean hasNext = false;
                    for (int dy2 : new int[]{0,1,-1}) {
                        TrackType tn = typeMap.getOrDefault(new BlockPos(x-1, nextY+dy2, z+i+1), null);
                        if (tn==TrackType.NS) { hasNext=true; break; }
                    }
                    a.len = hasNext ? 2 : 1;
                    return a;
                }
            }
            break;
        }
        return a;
    }

    private Agent scanEW_Ouest(int x, int y, int z) {
        Agent a = new Agent();
        int curY = y;
        for (int i=1; i<=20; i++) {
            boolean found = false;
            for (int dy : new int[]{0,1,-1}) {
                TrackType t = typeMap.getOrDefault(new BlockPos(x-i, curY+dy, z), null);
                if (t==TrackType.EW) {
                    a.dist++;
                    curY += dy;
                    found = true;
                    break;
                }
            }
            if (found) continue;
            for (int dy : new int[]{0,1,-1}) {
                TrackType ts = typeMap.getOrDefault(new BlockPos(x-i, curY+dy, z+1), null);
                if (ts==TrackType.EW) {
                    a.dir = "south";
                    int nextY = curY+dy;
                    boolean hasNext = false;
                    for (int dy2 : new int[]{0,1,-1}) {
                        TrackType tn = typeMap.getOrDefault(new BlockPos(x-i-1, nextY+dy2, z+1), null);
                        if (tn==TrackType.EW) { hasNext=true; break; }
                    }
                    a.len = hasNext ? 2 : 1;
                    return a;
                }
            }
            for (int dy : new int[]{0,1,-1}) {
                TrackType tn2 = typeMap.getOrDefault(new BlockPos(x-i, curY+dy, z-1), null);
                if (tn2==TrackType.EW) {
                    a.dir = "north";
                    int nextY = curY+dy;
                    boolean hasNext = false;
                    for (int dy2 : new int[]{0,1,-1}) {
                        TrackType tn = typeMap.getOrDefault(new BlockPos(x-i-1, nextY+dy2, z-1), null);
                        if (tn==TrackType.EW) { hasNext=true; break; }
                    }
                    a.len = hasNext ? 2 : 1;
                    return a;
                }
            }
            break;
        }
        return a;
    }

    private Agent scanEW_Est(int x, int y, int z) {
        Agent a = new Agent();
        int curY = y;
        for (int i=1; i<=20; i++) {
            boolean found = false;
            for (int dy : new int[]{0,1,-1}) {
                TrackType t = typeMap.getOrDefault(new BlockPos(x+i, curY+dy, z), null);
                if (t==TrackType.EW) {
                    a.dist++;
                    curY += dy;
                    found = true;
                    break;
                }
            }
            if (found) continue;
            for (int dy : new int[]{0,1,-1}) {
                TrackType ts = typeMap.getOrDefault(new BlockPos(x+i, curY+dy, z+1), null);
                if (ts==TrackType.EW) {
                    a.dir = "south";
                    int nextY = curY+dy;
                    boolean hasNext = false;
                    for (int dy2 : new int[]{0,1,-1}) {
                        TrackType tn = typeMap.getOrDefault(new BlockPos(x+i+1, nextY+dy2, z+1), null);
                        if (tn==TrackType.EW) { hasNext=true; break; }
                    }
                    a.len = hasNext ? 2 : 1;
                    return a;
                }
            }
            for (int dy : new int[]{0,1,-1}) {
                TrackType tn2 = typeMap.getOrDefault(new BlockPos(x+i, curY+dy, z-1), null);
                if (tn2==TrackType.EW) {
                    a.dir = "north";
                    int nextY = curY+dy;
                    boolean hasNext = false;
                    for (int dy2 : new int[]{0,1,-1}) {
                        TrackType tn = typeMap.getOrDefault(new BlockPos(x+i+1, nextY+dy2, z-1), null);
                        if (tn==TrackType.EW) { hasNext=true; break; }
                    }
                    a.len = hasNext ? 2 : 1;
                    return a;
                }
            }
            break;
        }
        return a;
    }

    private void buildFullRail(Map<BlockPos,BlockState> region) {
        typeMap = new HashMap<>();
        trackList = new ArrayList<>();
        List<Pt> sp = catmullRom(points, density[0]);
        if (sp.size()<2) return;
        if (snapGround[0]) sp = snapDown(sp);
        List<Seg> segs = toSegments(sp);
        if (segs.isEmpty()) return;

        for (int i=0; i<segs.size(); i++) {
            Seg c=segs.get(i), p=i>0?segs.get(i-1):null, n=i<segs.size()-1?segs.get(i+1):null;
            int tx, tz;
            if      (p!=null&&n!=null) { tx=n.cx()-p.cx(); tz=n.cz()-p.cz(); }
            else if (p!=null)          { tx=c.cx()-p.cx(); tz=c.cz()-p.cz(); }
            else if (n!=null)          { tx=n.cx()-c.cx(); tz=n.cz()-c.cz(); }
            else                       { tx=c.dx();        tz=c.dz(); }
            BlockPos pos = new BlockPos(c.cx(), c.cy(), c.cz());
            if      (Math.abs(tx)==1 && Math.abs(tz)==1) typeMap.put(pos, TrackType.DIAG);
            else if (Math.abs(tz)>Math.abs(tx))          typeMap.put(pos, TrackType.NS);
            else                                          typeMap.put(pos, TrackType.EW);
            trackList.add(pos);
        }

        initBlocks();
        if (styleIndex[0]==0) applyClassic(region);
        else                  applyNatural(region);
    }

    private void applyClassic(Map<BlockPos,BlockState> r) {
        List<BlockPos> nsB=new ArrayList<>(), ewB=new ArrayList<>(), dgB=new ArrayList<>();
        for (BlockPos p : trackList) {
            TrackType tt = typeMap.get(p);
            if      (tt==TrackType.NS)   nsB.add(p);
            else if (tt==TrackType.EW)   ewB.add(p);
            else                         dgB.add(p);
        }
        for (BlockPos p : dgB) processDiag(r, p.getX(), p.getY(), p.getZ());
        for (BlockPos p : nsB) processNS(r, p.getX(), p.getY(), p.getZ());
        for (BlockPos p : ewB) processEW(r, p.getX(), p.getY(), p.getZ());
    }

    private void processNS(Map<BlockPos,BlockState> r, int x, int y, int z) {
        Agent nord = scanNS_Nord(x, y, z);
        Agent sud  = scanNS_Sud(x, y, z);

        int longueur = nord.dist + sud.dist + 1;
        int pos      = sud.dist + 1;

        BlockState blocCote = decideSideNS(nord, sud, longueur, pos);

        buildCol(r, x,   y, z, _coralNS);
        buildCol(r, x-1, y, z, blocCote);
        buildCol(r, x+1, y, z, blocCote);
    }

    private BlockState decideSideNS(Agent nord, Agent sud, int longueur, int pos) {
        if (longueur == 1) {
            return _wallNS;
        }

        if (longueur == 2) {
            if (pos == 1) {
                if ("east".equals(sud.dir))  return _sideW;
                if ("west".equals(sud.dir))  return _sideE;
                return _wallNS;
            } else {
                if (nord.len == 1) {
                    if ("east".equals(nord.dir)) return _sideW;
                    if ("west".equals(nord.dir)) return _sideE;
                }
                return _wallNS;
            }
        }

        int Q   = longueur / 3;
        int Rem = longueur % 3;

        if (pos <= Q) {
            if ("west".equals(sud.dir))  return _sideE;
            if ("east".equals(sud.dir))  return _sideW;
            return _wallNS;
        }
        if (pos > (2*Q + Rem)) {
            if ("west".equals(nord.dir)) return _sideE;
            if ("east".equals(nord.dir)) return _sideW;
            return _wallNS;
        }
        return _wallNS;
    }

    private void processEW(Map<BlockPos,BlockState> r, int x, int y, int z) {
        Agent ouest = scanEW_Ouest(x, y, z);
        Agent est   = scanEW_Est(x, y, z);

        int longueur = ouest.dist + est.dist + 1;
        int pos      = est.dist + 1;

        BlockState blocCote = decideSideEW(ouest, est, longueur, pos);

        buildCol(r, x,   y, z,   _coralEW);
        buildCol(r, x,   y, z-1, blocCote);
        buildCol(r, x,   y, z+1, blocCote);
    }

    private BlockState decideSideEW(Agent ouest, Agent est, int longueur, int pos) {
        if (longueur == 1) {
            return _wallEW;
        }

        if (longueur == 2) {
            if (pos == 1) {
                if ("north".equals(est.dir))  return _sideS;
                if ("south".equals(est.dir))  return _sideN;
                return _wallEW;
            } else {
                if (ouest.len == 1) {
                    if ("north".equals(ouest.dir)) return _sideS;
                    if ("south".equals(ouest.dir)) return _sideN;
                }
                return _wallEW;
            }
        }

        int Q   = longueur / 3;
        int Rem = longueur % 3;

        if (pos <= Q) {
            if ("south".equals(est.dir))  return _sideN;
            if ("north".equals(est.dir))  return _sideS;
            return _wallEW;
        }
        if (pos > (2*Q + Rem)) {
            if ("south".equals(ouest.dir)) return _sideN;
            if ("north".equals(ouest.dir)) return _sideS;
            return _wallEW;
        }
        return _wallEW;
    }

    private void processDiag(Map<BlockPos,BlockState> r, int x, int y, int z) {
        String diago = "senw";
        if (hasTypeNear(x+1, y, z-1, TrackType.DIAG) || hasTypeNear(x-1, y, z+1, TrackType.DIAG)) {
            diago = "swne";
        } else {
            TrackType bNe = findTypeNear(x+1, y, z-1);
            TrackType bSo = findTypeNear(x-1, y, z+1);
            if (bNe==TrackType.NS || bNe==TrackType.EW || bSo==TrackType.NS || bSo==TrackType.EW)
                diago = "swne";
        }

        boolean isSwne = "swne".equals(diago);
        BlockState w1 = isSwne ? _wallNW : _wallSW;
        BlockState w2 = isSwne ? _wallSE : _wallNE;

        DiagAgent agNord = scanDiagNord(x, y, z, isSwne);
        DiagAgent agSud  = scanDiagSud(x, y, z, isSwne);

        boolean falseDiag = agNord.type.equals(agSud.type) && !"unknown".equals(agNord.type);

        if (falseDiag) {
            BlockState coral = "ns".equals(agNord.type) ? _coralNS : _coralEW;
            buildCol(r, x,   y, z,   coral);
            buildCol(r, x-1, y, z,   w1);
            buildCol(r, x+1, y, z,   w2);
            buildCol(r, x,   y, z-1, w1);
            buildCol(r, x,   y, z+1, w2);
            return;
        }

        int len = agSud.dist + agNord.dist + 1;
        int mid = len / 2;
        int rel = agSud.dist + 1;

        BlockState coral;
        boolean trans;

        if (len == 1) {
            coral = _coralNS;
            trans = true;
        } else {
            String ext = (rel <= mid) ? agSud.type : agNord.type;
            if      ("oe".equals(ext)) coral = _coralEW;
            else if ("ns".equals(ext)) coral = _coralNS;
            else                       coral = _coralNS;
            trans = (rel == mid) || (rel == mid+1);
        }

        buildCol(r, x, y, z, coral);

        if (trans) {
            buildCol(r, x-1, y, z,   w1);
            buildCol(r, x+1, y, z,   w2);
            buildCol(r, x,   y, z-1, w1);
            buildCol(r, x,   y, z+1, w2);
        } else if (coral == _coralNS) {
            buildCol(r, x-1, y, z, w1);
            buildCol(r, x+1, y, z, w2);
        } else {
            buildCol(r, x, y, z-1, w1);
            buildCol(r, x, y, z+1, w2);
        }
    }

    private static class DiagAgent {
        int dist = 0;
        String type = "unknown";
    }

    private DiagAgent scanDiagNord(int x, int y, int z, boolean isSwne) {
        DiagAgent a = new DiagAgent();
        int curY = y;
        for (int i=1; ; i++) {
            int cx = isSwne ? x+i : x-i;
            int cz = z-i;
            boolean found = false;
            for (int dy : new int[]{0,1,-1}) {
                if (typeMap.getOrDefault(new BlockPos(cx, curY+dy, cz), null)==TrackType.DIAG) {
                    curY += dy; a.dist++; found=true; break;
                }
            }
            if (found) continue;
            for (int dy : new int[]{0,1,-1}) {
                TrackType bt = typeMap.getOrDefault(new BlockPos(cx, curY+dy, cz), null);
                if (bt==TrackType.NS) { a.type="ns"; break; }
                if (bt==TrackType.EW) { a.type="oe"; break; }
            }
            break;
        }
        return a;
    }

    private DiagAgent scanDiagSud(int x, int y, int z, boolean isSwne) {
        DiagAgent a = new DiagAgent();
        int curY = y;
        for (int i=1; ; i++) {
            int cx = isSwne ? x-i : x+i;
            int cz = z+i;
            boolean found = false;
            for (int dy : new int[]{0,1,-1}) {
                if (typeMap.getOrDefault(new BlockPos(cx, curY+dy, cz), null)==TrackType.DIAG) {
                    curY += dy; a.dist++; found=true; break;
                }
            }
            if (found) continue;
            for (int dy : new int[]{0,1,-1}) {
                TrackType bt = typeMap.getOrDefault(new BlockPos(cx, curY+dy, cz), null);
                if (bt==TrackType.NS) { a.type="ns"; break; }
                if (bt==TrackType.EW) { a.type="oe"; break; }
            }
            break;
        }
        return a;
    }

    private void applyNatural(Map<BlockPos,BlockState> r) {
        List<BlockPos> nsB=new ArrayList<>(), ewB=new ArrayList<>(), dgB=new ArrayList<>();
        for (BlockPos p : trackList) {
            TrackType tt = typeMap.get(p);
            if      (tt==TrackType.NS) nsB.add(p);
            else if (tt==TrackType.EW) ewB.add(p);
            else                       dgB.add(p);
        }

        for (BlockPos p : nsB) {
            int x=p.getX(), y=p.getY(), z=p.getZ();
            boolean moss = false;
            for (int[] off : new int[][]{{0,-1,1},{0,-1,-1},{1,-1,1},{1,-1,-1},{-1,-1,1},{-1,-1,-1}})
                if (hasType(x+off[0], y+off[1], z+off[2], TrackType.NS)) { moss=true; break; }
            if (!moss) {
                r.put(p, Blocks.LECTERN.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                        .setValue(LecternBlock.HAS_BOOK, false));
                r.put(new BlockPos(x, y+1, z), Blocks.PALE_MOSS_CARPET.defaultBlockState());
            } else {
                r.put(p, Blocks.PALE_MOSS_BLOCK.defaultBlockState());
                r.put(new BlockPos(x, y+1, z), oakButton(Direction.NORTH));
            }
            r.put(new BlockPos(x+1, y, z), Blocks.GRAVEL.defaultBlockState());
            r.put(new BlockPos(x-1, y, z), Blocks.GRAVEL.defaultBlockState());
            r.put(new BlockPos(x, y-1, z), pickGround());
            for (int dx2 : new int[]{1,-1}) for (int dz2 : new int[]{-1,1}) {
                String face = crossFace(dx2, dz2);
                for (int dy : new int[]{0,1,-1}) {
                    if (hasType(x+dx2, y+dy, z+dz2, TrackType.EW)) {
                        r.put(new BlockPos(x, y+dy, z+dz2), Blocks.GRAVEL.defaultBlockState());
                        r.put(new BlockPos(x, y+dy+1, z+dz2), leafLitter(3, face));
                    }
                }
            }
            List<String> nb = getNeighbors(x, y, z);
            String d1=nb.size()>0?nb.get(0):"", d2=nb.size()>1?nb.get(1):"";
            int eA=2; String eF="north"; int wA=2; String wF="south";
            if      (pm(d1,d2,"N","S"))   { eA=2; eF="north"; wA=2; wF="south"; }
            else if (pm(d1,d2,"N","SE"))  { eA=3; eF="south"; wA=2; wF="south"; }
            else if (pm(d1,d2,"N","SO"))  { eA=2; eF="north"; wA=3; wF="east"; }
            else if (pm(d1,d2,"S","NE"))  { eA=3; eF="west";  wA=2; wF="south"; }
            else if (pm(d1,d2,"S","NO"))  { eA=2; eF="north"; wA=3; wF="north"; }
            else if (pm(d1,d2,"NE","SO")) { eA=3; eF="west";  wA=3; wF="east"; }
            else if (pm(d1,d2,"NO","SE")) { eA=3; eF="south"; wA=3; wF="north"; }
            r.put(new BlockPos(x+1, y+1, z), leafLitter(eA, eF));
            r.put(new BlockPos(x-1, y+1, z), leafLitter(wA, wF));
        }

        for (BlockPos p : ewB) {
            int x=p.getX(), y=p.getY(), z=p.getZ();
            boolean moss = false;
            for (int[] off : new int[][]{{1,-1,0},{-1,-1,0},{1,-1,1},{-1,-1,1},{1,-1,-1},{-1,-1,-1}})
                if (hasType(x+off[0], y+off[1], z+off[2], TrackType.EW)) { moss=true; break; }
            if (!moss) {
                r.put(p, Blocks.LECTERN.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                        .setValue(LecternBlock.HAS_BOOK, false));
                r.put(new BlockPos(x, y+1, z), Blocks.PALE_MOSS_CARPET.defaultBlockState());
            } else {
                r.put(p, Blocks.PALE_MOSS_BLOCK.defaultBlockState());
                r.put(new BlockPos(x, y+1, z), oakButton(Direction.EAST));
            }
            r.put(new BlockPos(x, y, z+1), Blocks.GRAVEL.defaultBlockState());
            r.put(new BlockPos(x, y, z-1), Blocks.GRAVEL.defaultBlockState());
            r.put(new BlockPos(x, y-1, z), pickGround());
            List<String> nb = getNeighbors(x, y, z);
            String d1=nb.size()>0?nb.get(0):"", d2=nb.size()>1?nb.get(1):"";
            int nA=2; String nF="west"; int sA=2; String sF="east";
            if      (pm(d1,d2,"O","E"))   { nA=2; nF="west";  sA=2; sF="east"; }
            else if (pm(d1,d2,"E","NO"))  { nA=3; nF="south"; sA=2; sF="east"; }
            else if (pm(d1,d2,"O","NE"))  { nA=3; nF="east";  sA=2; sF="east"; }
            else if (pm(d1,d2,"O","SE"))  { nA=2; nF="west";  sA=3; sF="north"; }
            else if (pm(d1,d2,"E","SO"))  { nA=2; nF="west";  sA=3; sF="west"; }
            else if (pm(d1,d2,"NE","SO")) { nA=3; nF="east";  sA=3; sF="west"; }
            else if (pm(d1,d2,"NO","SE")) { nA=3; nF="south"; sA=3; sF="north"; }
            r.put(new BlockPos(x, y+1, z-1), leafLitter(nA, nF));
            r.put(new BlockPos(x, y+1, z+1), leafLitter(sA, sF));
        }

        for (BlockPos p : dgB) {
            int x=p.getX(), y=p.getY(), z=p.getZ();
            String diago = "senw";
            if (hasTypeNear(x+1,y,z-1,TrackType.DIAG) || hasTypeNear(x-1,y,z+1,TrackType.DIAG)) diago="swne";
            else {
                TrackType bNe = findTypeNear(x+1,y,z-1);
                TrackType bSo = findTypeNear(x-1,y,z+1);
                if (bNe==TrackType.NS||bNe==TrackType.EW||bSo==TrackType.NS||bSo==TrackType.EW) diago="swne";
            }
            Direction facing = diago.equals("swne") ? Direction.NORTH : Direction.EAST;
            r.put(p, Blocks.LECTERN.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(LecternBlock.HAS_BOOK, false));
            r.put(new BlockPos(x, y+1, z), Blocks.PALE_MOSS_CARPET.defaultBlockState());
            r.put(new BlockPos(x, y-1, z), pickGround());
            String f1 = diago.equals("swne") ? "north" : "east";
            String f2 = diago.equals("swne") ? "south" : "west";
            r.put(new BlockPos(x-1, y+1, z), leafLitter(2, f1));
            r.put(new BlockPos(x+1, y+1, z), leafLitter(2, f2));
            r.put(new BlockPos(x, y+1, z-1), leafLitter(2, f1));
            r.put(new BlockPos(x, y+1, z+1), leafLitter(2, f2));
        }
    }

    private static boolean pm(String a, String b, String x, String y) {
        return (a.equals(x)&&b.equals(y)) || (a.equals(y)&&b.equals(x));
    }

    private static String crossFace(int dx, int dz) {
        if (dx==1  && dz==-1) return "east";
        if (dx==-1 && dz==-1) return "south";
        if (dx==1  && dz==1)  return "north";
        return "west";
    }

    private String dirName(int dx, int dz) {
        String d = "";
        if (dz==-1) d="N"; else if (dz==1) d="S";
        if (dx==1) d+="E"; else if (dx==-1) d+="O";
        return d;
    }

    private List<String> getNeighbors(int x, int y, int z) {
        List<String> nb = new ArrayList<>();
        for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
            if (dx==0 && dz==0) continue;
            for (int dy=-1; dy<=1; dy++) {
                if (typeMap.containsKey(new BlockPos(x+dx, y+dy, z+dz))) { nb.add(dirName(dx,dz)); break; }
            }
        }
        return nb;
    }

    private record Seg(int cx, int cy, int cz, int dx, int dz) {}
    private record Pt(double x, double y, double z, int bx, int by, int bz) {}

    private List<Seg> toSegments(List<Pt> sp) {
        List<Seg> out = new ArrayList<>(); BlockPos last = null;
        for (Pt p : sp) {
            BlockPos bp = new BlockPos(p.bx(), p.by(), p.bz());
            if (bp.equals(last)) continue;
            int dx=0, dz=0;
            if (last!=null) { dx=Integer.compare(bp.getX(),last.getX()); dz=Integer.compare(bp.getZ(),last.getZ()); }
            out.add(new Seg(bp.getX(), bp.getY(), bp.getZ(), dx, dz)); last=bp;
        }
        return out;
    }

    private List<Pt> catmullRom(List<BlockPos> pts, int dens) {
        List<Vec3> v = new ArrayList<>();
        for (BlockPos p : pts) v.add(Vec3.atCenterOf(p));
        List<Vec3> ext = new ArrayList<>();
        ext.add(v.get(0).add(v.get(0).subtract(v.get(1))));
        ext.addAll(v);
        ext.add(v.get(v.size()-1).add(v.get(v.size()-1).subtract(v.get(v.size()-2))));
        List<Vec3> raw = new ArrayList<>();
        for (int i=1; i<ext.size()-2; i++) {
            Vec3 p0=ext.get(i-1), p1=ext.get(i), p2=ext.get(i+1), p3=ext.get(i+2);
            int steps = Math.max(1, (int)Math.ceil(p1.distanceTo(p2)*dens));
            for (int s=0; s<steps; s++) raw.add(crEval(p0,p1,p2,p3,(double)s/steps));
        }
        raw.add(v.get(v.size()-1));
        List<Pt> out = new ArrayList<>(); BlockPos last = null;
        for (Vec3 q : raw) {
            int bx=(int)Math.floor(q.x), by=(int)Math.floor(q.y), bz=(int)Math.floor(q.z);
            BlockPos bp = new BlockPos(bx,by,bz);
            if (!bp.equals(last)) { out.add(new Pt(q.x,q.y,q.z,bx,by,bz)); last=bp; }
        }
        return out;
    }

    private static Vec3 crEval(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2=t*t, t3=t2*t;
        return new Vec3(cr(p0.x,p1.x,p2.x,p3.x,t,t2,t3),
                cr(p0.y,p1.y,p2.y,p3.y,t,t2,t3),
                cr(p0.z,p1.z,p2.z,p3.z,t,t2,t3));
    }

    private static double cr(double a, double b, double c, double d, double t, double t2, double t3) {
        return 0.5*(2*b+(-a+c)*t+(2*a-5*b+4*c-d)*t2+(-a+3*b-3*c+d)*t3);
    }

    private List<Pt> snapDown(List<Pt> pts) {
        var w = Minecraft.getInstance().level; if (w==null) return pts;
        List<Pt> out = new ArrayList<>();
        for (Pt p : pts) { int sy=surf(w,p.bx(),p.by(),p.bz()); out.add(new Pt(p.x(),sy+0.5,p.z(),p.bx(),sy,p.bz())); }
        return out;
    }

    private static int surf(ClientLevel w, int x, int sy, int z) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(x,sy,z);
        for (int dy=0; dy>=-256; dy--) { mp.setY(sy+dy); if (!w.getBlockState(mp).isAir()) return sy+dy+1; }
        for (int dy=1; dy<=256; dy++)  { mp.setY(sy+dy); if (!w.getBlockState(mp).isAir()) return sy+dy+1; }
        return sy;
    }
}