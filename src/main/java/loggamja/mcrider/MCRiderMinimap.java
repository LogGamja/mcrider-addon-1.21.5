package loggamja.mcrider;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;

import java.util.function.Predicate;

/**
 * (WIP) 플러드필 기반 트랙 미니맵 — "색깔 관계 그래프" 방식.
 *
 * 이론 요약:
 *  · 규칙0(암묵): 확장 중인 색이 "양방향" 이동으로 새 칸 진입 → 같은 색 유지.
 *  · 규칙1: 고아(어떤 색 확장도 아님) 상태로 빈 칸 진입 → 새 루트 색.
 *           TP/리스폰을 명시 감지하지 않고 "빈 칸에 시드가 놓이는" 범용 동작으로 처리.
 *  · 규칙2: "단방향" 이동으로 새 칸 도달 → 새 색 + 직전 색의 자식으로 종속.
 *  · 규칙3: 두 색이 "양방향"으로 인접하면 병합. 단방향 인접은 병합하지 않음.
 *  · 규칙4(표시): resolve(플레이어 위치 색)과 그 자손 전체만 표시.
 *           DEBUG_COLORS=true면 모든 색을 각자 다른 색으로 표시.
 *  병합은 포인터 딕셔너리(resolve)만 갱신(경로 압축). 조상-자손 조우 시 사이 사슬 전체 붕괴.
 */
public class MCRiderMinimap implements ClientModInitializer {

    /** true면 모든 색을 각자 다른 색으로 표시(디버그). 기본 true. */
    public static final boolean DEBUG_COLORS = true;

    static MinecraftClient client = MinecraftClient.getInstance();

    private static final long NO_ID = Long.MIN_VALUE;

    // ── 탐색 상태 ────────────────────────────────────────────────────────
    static boolean isRequireKeepSearching = false;
    static BlockPos lastPlayerPos;

    static LongArrayFIFOQueue frontierQueue = new LongArrayFIFOQueue();
    /** 미로딩/범위 밖 청크에 보류된 프론티어: ChunkPos.toLong → 셀 목록(청크당 여러 개, 유실 방지) */
    static Long2ObjectOpenHashMap<LongArrayList> exiledFrontierChunkHash = new Long2ObjectOpenHashMap<>();

    /** 방문 셀 → 색 ID. 키 BlockPos.asLong, 값 색 ID(불변). */
    static Long2LongOpenHashMap cellColor = new Long2LongOpenHashMap();
    /** packColumn(x,z) → 방문 Y 집합(텍스처 도색 대상). */
    static Long2ObjectOpenHashMap<IntOpenHashSet> visitedColumns = new Long2ObjectOpenHashMap<>();

    // ── 색 관계 그래프 & 포인터 딕셔너리 ─────────────────────────────────
    static Long2LongOpenHashMap colorParentPtr = new Long2LongOpenHashMap();
    static Long2ObjectOpenHashMap<LongOpenHashSet> childToParents = new Long2ObjectOpenHashMap<>();
    static Long2ObjectOpenHashMap<LongOpenHashSet> parentToChildren = new Long2ObjectOpenHashMap<>();
    static Long2IntOpenHashMap colorBirth = new Long2IntOpenHashMap();

    static long nextColorId = 0;
    static int birthCounter = 0;

    static long activeColor = NO_ID;
    static boolean needsFullRepaint = false;

    // ── 레이아웃 (MCRiderRadar와 동일 스킴) ──────────────────────────────
    private static final double LEGACY_GUI_SCALE_BASIS = 4.0;
    final int padding = (int) Math.round(10 * LEGACY_GUI_SCALE_BASIS);
    final int baseRadius = (int) Math.round(50 * LEGACY_GUI_SCALE_BASIS);
    static final double baseDist = 100;
    final double uiScale = 0.75;
    static final double distScale = 1;
    final int radius = (int) Math.round(baseRadius * uiScale);
    static final double maxDist = baseDist * distScale;

    // ── 북쪽 고정 텍스처 ─────────────────────────────────────────────────
    private static final int TEX_SIZE = 512;
    private static final double SQRT2 = Math.sqrt(2.0);
    private static final int REANCHOR_MARGIN = (int) Math.ceil(maxDist * SQRT2) + 8;
    private static final Identifier MINIMAP_ID = Identifier.of("mcrider-official", "minimap");
    private static final int VISITED_COLOR = 0xBBFFFFFF;

    private static NativeImage image;
    private static NativeImageBackedTexture texture;
    private static boolean textureDirty = false;
    private static int originX, originZ;
    private static boolean originSet = false;

    // ── 예산 ─────────────────────────────────────────────────────────────
    static final int STAGING_BUDGET_PER_TICK = 512;
    static final long STAGING_TIME_BUDGET_NANOS = 500_000L; // 0.5ms

    static { colorParentPtr.defaultReturnValue(NO_ID); cellColor.defaultReturnValue(NO_ID); }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> onTickStart());
        HudRenderCallback.EVENT.register((context, context2) -> renderMinimap(context, context2.getTickProgress(false)));
    }

    // ═════════════════════════════ 렌더링 ═════════════════════════════

    private static void ensureTexture() {
        if (texture != null) return;
        image = new NativeImage(NativeImage.Format.RGBA, TEX_SIZE, TEX_SIZE, false);
        image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
        texture = new NativeImageBackedTexture(() -> "mcrider-minimap", image);
        client.getTextureManager().registerTexture(MINIMAP_ID, texture);
    }

    private static void rebuildTexture(BlockPos center) {
        ensureTexture();
        originX = center.getX() - TEX_SIZE / 2;
        originZ = center.getZ() - TEX_SIZE / 2;
        originSet = true;
        image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
        LongIterator it = visitedColumns.keySet().longIterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            plotColumn(unpackColumnX(key), unpackColumnZ(key));
        }
        textureDirty = true;
    }

    private static void plotColumn(int worldX, int worldZ) {
        if (image == null || !originSet) return;
        int tx = worldX - originX;
        int tz = worldZ - originZ;
        if (tx < 0 || tx >= TEX_SIZE || tz < 0 || tz >= TEX_SIZE) return;
        image.setColorArgb(tx, tz, computeColumnColor(worldX, worldZ));
        textureDirty = true;
    }

    /** 비디버그 모드에서 "활성 색 + 그 자손"의 resolve된 집합. 재도색 시작 시 1회 계산해 캐시.
     *  (컬럼마다 BFS하면 O(컬럼수 × 그래프크기)로 폭발하므로, 틱당 1회로 상각.) */
    private static final LongOpenHashSet activeSet = new LongOpenHashSet();

    private static void rebuildActiveSet() {
        activeSet.clear();
        if (activeColor == NO_ID) return;
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        activeSet.add(activeColor);
        q.enqueue(activeColor);
        while (!q.isEmpty()) {
            long cur = q.dequeueLong();
            LongOpenHashSet kids = parentToChildren.get(cur);
            if (kids == null) continue;
            LongIterator it = kids.iterator();
            while (it.hasNext()) {
                long kid = resolve(it.nextLong());
                if (activeSet.add(kid)) q.enqueue(kid);
            }
        }
    }

    private static int computeColumnColor(int x, int z) {
        IntOpenHashSet ys = visitedColumns.get(packColumn(x, z));
        if (ys == null || ys.isEmpty()) return 0;

        if (DEBUG_COLORS) {
            int repY = Integer.MIN_VALUE;
            long repRoot = NO_ID;
            it.unimi.dsi.fastutil.ints.IntIterator yi = ys.iterator();
            while (yi.hasNext()) {
                int y = yi.nextInt();
                long id = cellColor.get(BlockPos.asLong(x, y, z));
                if (id == NO_ID) continue;
                long root = resolve(id);
                if (y >= repY) { repY = y; repRoot = root; }
            }
            return colorForRoot(repRoot);
        } else {
            if (activeColor == NO_ID) return 0;
            it.unimi.dsi.fastutil.ints.IntIterator yi = ys.iterator();
            while (yi.hasNext()) {
                int y = yi.nextInt();
                long id = cellColor.get(BlockPos.asLong(x, y, z));
                if (id == NO_ID) continue;
                if (activeSet.contains(resolve(id))) return VISITED_COLOR;
            }
            return 0;
        }
    }

    private static int colorForRoot(long root) {
        if (root == NO_ID) return 0;
        long h = splitMix64(root);
        float hue = ((h >>> 40) & 0xFFFF) / 65535f;
        return 0xCC000000 | hsvToRgb(hue, 0.75f, 1.0f);
    }

    private static long splitMix64(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    private static int hsvToRgb(float hue, float sat, float val) {
        float h6 = (hue - (float) Math.floor(hue)) * 6f;
        int i = (int) h6;
        float f = h6 - i;
        float p = val * (1f - sat);
        float q = val * (1f - sat * f);
        float t = val * (1f - sat * (1f - f));
        float r, g, b;
        switch (i) {
            case 0:  r = val; g = t;   b = p;   break;
            case 1:  r = q;   g = val; b = p;   break;
            case 2:  r = p;   g = val; b = t;   break;
            case 3:  r = p;   g = q;   b = val; break;
            case 4:  r = t;   g = p;   b = val; break;
            default: r = val; g = p;   b = q;   break;
        }
        return (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }

    private void renderMinimap(DrawContext context, float tickDelta) {
        if (!MCRiderConfig.INSTANCE.useMinimap) return;
        if (visitedColumns.isEmpty() || !originSet) return;
        if (!MCRiderMain.isPlayingInGame()) return;

        ensureTexture();
        if (textureDirty) {
            texture.upload();
            textureDirty = false;
        }

        final double sizeFactor = getSizeFactor(client);
        final int screenHeight = client.getWindow().getScaledHeight();
        final double scaledPadding = padding * sizeFactor;
        final double scaledRadius = radius * sizeFactor;
        final int centerX = (int) Math.round(scaledPadding + scaledRadius);
        final int centerY = (int) Math.round(screenHeight - scaledPadding - scaledRadius);
        final int viewX1 = (int) Math.round(centerX - scaledRadius);
        final int viewY1 = (int) Math.round(centerY - scaledRadius);
        final int viewX2 = (int) Math.round(centerX + scaledRadius);
        final int viewY2 = (int) Math.round(centerY + scaledRadius);

        context.fill(viewX1, viewY1, viewX2, viewY2, 0x88000000);

        final float yawDeg = client.gameRenderer.getCamera().getYaw();
        final Vec3d p = MCRiderMain.getRidingPlayer().getCameraPosVec(tickDelta);

        final double blockToScreen = scaledRadius / maxDist;
        final int texRegion = 2 * (int) Math.ceil(maxDist * SQRT2);
        final int drawSize = (int) Math.round(texRegion * blockToScreen);
        final float u0 = (float) (p.x - originX - texRegion / 2.0);
        final float v0 = (float) (p.z - originZ - texRegion / 2.0);

        context.enableScissor(viewX1, viewY1, viewX2, viewY2);

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(centerX, centerY, 0);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180f - yawDeg));
        matrices.translate(-drawSize / 2f, -drawSize / 2f, 0);
        context.drawTexture(
                RenderLayer::getGuiTextured, MINIMAP_ID,
                0, 0, u0, v0, drawSize, drawSize, texRegion, texRegion, TEX_SIZE, TEX_SIZE);
        matrices.pop();
        context.disableScissor();
    }

    private double getSizeFactor(MinecraftClient client) {
        final double physicalScale = client.getWindow().getHeight() / 1080.0;
        final double scaleFactor = client.getWindow().getScaleFactor();
        return physicalScale / scaleFactor;
    }

    // ═══════════════════════════ 포인터 딕셔너리(resolve) ═══════════════════════════

    static void ensureColor(long id) {
        if (!colorParentPtr.containsKey(id)) {
            colorParentPtr.put(id, id);
            colorBirth.put(id, birthCounter++);
        }
    }

    static long resolve(long id) {
        long root = id;
        long p;
        while ((p = colorParentPtr.get(root)) != root) {
            if (p == NO_ID) return id;
            root = p;
        }
        long cur = id;
        while (cur != root) {
            long next = colorParentPtr.get(cur);
            colorParentPtr.put(cur, root);
            cur = next;
        }
        return root;
    }

    static long newColor(long parentId) {
        long id = nextColorId++;
        ensureColor(id);
        if (parentId != NO_ID) {
            long pr = resolve(parentId);
            addEdge(pr, id);
        }
        return id;
    }

    static void addEdge(long parent, long child) {
        if (parent == child) return;
        parentToChildren.computeIfAbsent(parent, k -> new LongOpenHashSet()).add(child);
        childToParents.computeIfAbsent(child, k -> new LongOpenHashSet()).add(parent);
    }

    // ═══════════════════════════ 병합 ═══════════════════════════

    static void mergeColors(long aId, long bId) {
        long a = resolve(aId);
        long b = resolve(bId);
        if (a == b) return;

        LongOpenHashSet group = new LongOpenHashSet();
        group.add(a);
        group.add(b);
        collectChainIfAncestor(a, b, group);
        collectChainIfAncestor(b, a, group);

        long survivor = NO_ID;
        int bestBirth = Integer.MAX_VALUE;
        LongIterator git = group.iterator();
        while (git.hasNext()) {
            long c = git.nextLong();
            int birth = colorBirth.get(c);
            if (birth < bestBirth) { bestBirth = birth; survivor = c; }
        }

        git = group.iterator();
        while (git.hasNext()) {
            long c = git.nextLong();
            if (c != survivor) colorParentPtr.put(c, survivor);
        }

        if (group.contains(activeColor) && activeColor != survivor) {
            activeColor = survivor;
        }

        rescanCycles(survivor);
        needsFullRepaint = true;
    }

    /**
     * from이 to의 조상이면, 그 사이 경로 위의 모든 색을 out에 추가한다.
     *
     * 최적화: 자식은 항상 부모보다 나중에 태어난다(birth 값이 큼)는 성질을 이용해 두 번 가지치기한다.
     *  1) birth(from) >= birth(to)면 from은 애초에 to의 조상일 수 없으므로 즉시 반환(O(1)).
     *     — 무관한 두 색(사촌)이 만날 때, 호출 두 번(a→b, b→a) 중 하나는 항상 이걸로 즉시 끝난다.
     *  2) 정방향 도달 탐색 중, birth가 이미 to보다 큰 노드는 가지치기(그 아래 자손은 birth가
     *     더 커질 뿐이므로 to에 닿을 수 없다) — 그래프 전체가 아니라 "to보다 먼저 태어난 부분"만 훑는다.
     * 재귀 대신 반복문(큐) 기반이라 아주 긴 사슬에서도 스택 오버플로 위험이 없다.
     */
    static void collectChainIfAncestor(long from, long to, LongOpenHashSet out) {
        from = resolve(from);
        to = resolve(to);
        if (from == to) return;

        int fromBirth = colorBirth.get(from);
        int toBirth = colorBirth.get(to);
        if (fromBirth >= toBirth) return; // 가지치기1: from이 to보다 늦게(또는 같이) 태어남 → 조상 불가

        // R1: from에서 자식 방향으로 도달 가능한 노드(단, to보다 늦게 태어난 가지는 잘라냄)
        LongOpenHashSet reachable = new LongOpenHashSet();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        reachable.add(from);
        q.enqueue(from);
        while (!q.isEmpty()) {
            long cur = q.dequeueLong();
            LongOpenHashSet kids = parentToChildren.get(cur);
            if (kids == null) continue;
            LongIterator kit = kids.iterator();
            while (kit.hasNext()) {
                long kid = resolve(kit.nextLong());
                if (kid == cur) continue;
                if (colorBirth.get(kid) > toBirth) continue; // 가지치기2
                if (reachable.add(kid)) q.enqueue(kid);
            }
        }
        if (!reachable.contains(to)) return; // to에 도달 불가 → 조상 아님

        // R2: to의 조상 집합(보통 단일 계보라 훨씬 작음) — 기존 함수 재사용.
        LongOpenHashSet ancestorsOfTo = new LongOpenHashSet();
        ancestorsOfTo.add(to);
        collectAncestors(to, ancestorsOfTo);

        // 교집합 = from→to 경로 위의 색들.
        LongIterator it = reachable.iterator();
        while (it.hasNext()) {
            long n = it.nextLong();
            if (ancestorsOfTo.contains(n)) out.add(n);
        }
    }

    static void rescanCycles(long survivor) {
        survivor = resolve(survivor);
        LongOpenHashSet descendants = new LongOpenHashSet();
        collectDescendants(survivor, descendants);
        LongOpenHashSet ancestors = new LongOpenHashSet();
        collectAncestors(survivor, ancestors);

        boolean merged = false;
        LongIterator it = descendants.iterator();
        while (it.hasNext()) {
            long d = it.nextLong();
            if (d != survivor && ancestors.contains(d)) {
                colorParentPtr.put(d, survivor);
                if (activeColor == d) activeColor = survivor;
                merged = true;
            }
        }
        if (merged) rescanCycles(survivor);
    }

    static void collectDescendants(long start, LongOpenHashSet out) {
        start = resolve(start);
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        LongOpenHashSet seen = new LongOpenHashSet();
        q.enqueue(start); seen.add(start);
        while (!q.isEmpty()) {
            long cur = q.dequeueLong();
            LongOpenHashSet kids = parentToChildren.get(cur);
            if (kids == null) continue;
            LongIterator it = kids.iterator();
            while (it.hasNext()) {
                long kid = resolve(it.nextLong());
                if (seen.add(kid)) { out.add(kid); q.enqueue(kid); }
            }
        }
    }

    static void collectAncestors(long start, LongOpenHashSet out) {
        start = resolve(start);
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        LongOpenHashSet seen = new LongOpenHashSet();
        q.enqueue(start); seen.add(start);
        while (!q.isEmpty()) {
            long cur = q.dequeueLong();
            LongOpenHashSet pars = childToParents.get(cur);
            if (pars == null) continue;
            LongIterator it = pars.iterator();
            while (it.hasNext()) {
                long par = resolve(it.nextLong());
                if (seen.add(par)) { out.add(par); q.enqueue(par); }
            }
        }
    }

    // ═════════════════════════════ 탐색 ═════════════════════════════

    static final TagKey<Block> KART_WALL = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "stones"));
    static final Predicate<BlockState> isWall = state -> state.isIn(KART_WALL);
    static final TagKey<Block> KART_AIR = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "ignoreblock"));
    static final Predicate<BlockState> isAir = state -> state.isIn(KART_AIR);

    private static final int CHUNK_CACHE_SLOTS = 4;
    private static final long[] cacheKeys = new long[CHUNK_CACHE_SLOTS];
    private static final Chunk[] cacheChunks = new Chunk[CHUNK_CACHE_SLOTS];
    private static int cacheNextSlot = 0;
    private static final BlockPos.Mutable MUTABLE = new BlockPos.Mutable();

    private static void invalidateChunkCache() {
        java.util.Arrays.fill(cacheKeys, Long.MIN_VALUE);
        java.util.Arrays.fill(cacheChunks, null);
        cacheNextSlot = 0;
    }

    private static BlockState stateAt(int x, int y, int z) {
        long key = ChunkPos.toLong(x >> 4, z >> 4);
        for (int i = 0; i < CHUNK_CACHE_SLOTS; i++) {
            if (cacheKeys[i] == key && cacheChunks[i] != null) {
                return cacheChunks[i].getBlockState(MUTABLE.set(x, y, z));
            }
        }
        Chunk chunk = client.world.getChunk(x >> 4, z >> 4);
        cacheKeys[cacheNextSlot] = key;
        cacheChunks[cacheNextSlot] = chunk;
        cacheNextSlot = (cacheNextSlot + 1) % CHUNK_CACHE_SLOTS;
        return chunk.getBlockState(MUTABLE.set(x, y, z));
    }

    static boolean isAirAt(int x, int y, int z) {
        if (client.world == null) return false;
        return isAir.test(stateAt(x, y, z));
    }
    static boolean isWallAt(int x, int y, int z) {
        if (client.world == null) return false;
        return isWall.test(stateAt(x, y, z));
    }
    static boolean isVoidAt(int x, int y, int z) {
        if (client.world == null) return false;
        return stateAt(x, y, z).isOf(Blocks.STRUCTURE_VOID);
    }
    static boolean isVoidFloorUnder(int x, int y, int z) {
        return isVoidAt(x, y - 1, z);
    }
    static boolean isChunkLoadedAt(int x, int z) {
        if (client.world == null) return false;
        return client.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }

    /** 서 있을 수 있는 칸: 머리(y+1) 공간 + void 바닥 아님 + 발밑 지지(y,y-1 둘 다 공기가 아니어야). */
    /** 서 있을 수 있는 칸: 머리(y+1) 공간 + void 바닥 아님 + 발밑 지지.
     *  headAlreadyChecked=true면 머리 공간 검사를 건너뛴다(호출자가 이미 같은 좌표를 확인한 경우 —
     *  평지/계단 이동은 resolveTargetY가 이미 같은 좌표의 머리 공간을 검사했으므로 중복 조회를 피함.
     *  낙하는 최종 착지 높이의 머리 공간을 resolveTargetY가 검사하지 않으므로 반드시 false로 호출). */
    static boolean isStandable(int x, int y, int z, boolean headAlreadyChecked) {
        if (!headAlreadyChecked && !isAirAt(x, y + 1, z)) return false;
        if (isVoidFloorUnder(x, y, z)) return false;
        if (isAirAt(x, y, z) && isAirAt(x, y - 1, z)) return false;
        return true;
    }

    void onTickStart() {
        if (!MCRiderConfig.INSTANCE.useMinimap) return;
        if (client.player == null || client.world == null) return;

        final int playerMargin = 5;
        BlockPos start = client.player.getBlockPos().up();
        lastPlayerPos = start;

        floodFillWithVertical(start, (int) ((maxDist + playerMargin * 2) * 2), STAGING_BUDGET_PER_TICK);

        if (!originSet) {
            rebuildTexture(start);
        } else {
            int cx = start.getX() - (originX + TEX_SIZE / 2);
            int cz = start.getZ() - (originZ + TEX_SIZE / 2);
            if (Math.abs(cx) > TEX_SIZE / 2 - REANCHOR_MARGIN
                    || Math.abs(cz) > TEX_SIZE / 2 - REANCHOR_MARGIN) {
                rebuildTexture(start);
                needsFullRepaint = false;
            }
        }

        updateActiveColor(start);

        // 비디버그 모드: 이번 틱의 "활성+자손" 집합을 미리 계산(컬럼마다 BFS 방지).
        if (!DEBUG_COLORS) rebuildActiveSet();

        if (needsFullRepaint && originSet) {
            repaintAll();
            needsFullRepaint = false;
        }
    }

    static void updateActiveColor(BlockPos start) {
        long cell = resolvePlayerCell(start);
        if (cell == NO_ID) return;
        long id = cellColor.get(cell);
        if (id == NO_ID) return;
        long root = resolve(id);
        if (root != activeColor) {
            activeColor = root;
            if (!DEBUG_COLORS) needsFullRepaint = true;
        }
    }

    static long resolvePlayerCell(BlockPos start) {
        IntOpenHashSet ys = visitedColumns.get(packColumn(start.getX(), start.getZ()));
        if (ys == null || ys.isEmpty()) return NO_ID;
        int best = Integer.MIN_VALUE;
        int bestDist = Integer.MAX_VALUE;
        it.unimi.dsi.fastutil.ints.IntIterator yi = ys.iterator();
        while (yi.hasNext()) {
            int y = yi.nextInt();
            int dist = Math.abs(y - start.getY());
            if (dist < bestDist) { bestDist = dist; best = y; }
        }
        if (bestDist > 4) return NO_ID;
        return BlockPos.asLong(start.getX(), best, start.getZ());
    }

    static void repaintAll() {
        if (image == null || !originSet) return;
        image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
        LongIterator it = visitedColumns.keySet().longIterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            plotColumn(unpackColumnX(key), unpackColumnZ(key));
        }
        textureDirty = true;
    }

    /**
     * 프론티어 셀을 분류한다. 색이나 플레이어와의 거리와는 무관하게, 오직 실제 환경적
     * 제약(탐색 범위 안인가)만으로 판단한다. exile은 어디까지나 "지금 당장은 범위 밖"인
     * 셀을 위한 것이고, 매 틱 도는 exile 복귀 루프가 범위 안으로 들어오면 자동으로
     * frontierQueue로 되돌린다.
     *
     * (이전에는 색이 활성 색과 다르면 플레이어와 아무리 가까워도 사실상 영원히 exile에
     * 갇히는 버그가 있었다 — 좁은 외길에서는 경로가 죄다 같은 색이라 안 드러났지만,
     * 넓은 도로에서 옆 차선이 지형 조건 때문에 다른 색으로 갈라지면 그 차선 전체가
     * 체스판처럼 듬성듬성 비어버렸다. 색은 순전히 "어느 구간이 연결돼 있는지"를 나타내는
     * 라벨일 뿐, 탐색을 진행할지 말지를 결정하는 조건이 되면 안 된다.)
     */
    static void enqueueFrontier(long cell, int cx, int cz, int sx, int sz, int maxRange) {
        if (taxiDistance2D(cx, cz, sx, sz) <= maxRange) {
            frontierQueue.enqueue(cell);
        } else {
            parkInExiledFrontier(cell, cx >> 4, cz >> 4);
        }
    }

    static boolean floodFillWithVertical(BlockPos start, int maxRange, int updatePixel) {
        isRequireKeepSearching = false;
        var world = client.world;
        if (world == null) return false;

        // 청크 캐시는 틱 사이에 유지한다(더 이상 매 틱 초기화하지 않음). 플레이어가 크게 안 움직이면
        // 직전 틱에 캐싱된 청크가 여전히 유효해서 재조회를 줄인다. 안전성: 이 코드가 stateAt()을
        // 부르는 모든 좌표는 사전에 isChunkLoadedAt()으로 로딩 확인을 거치므로, 언로드된 청크의
        // 낡은 데이터를 읽을 위험은 없다(단, 극히 드물게 청크가 언로드 후 재로드되며 블록이 그
        // 사이에 바뀐 경우, 캐시가 그 변경 전 스냅샷을 잠깐 들고 있을 수는 있다 — 트랙이 대체로
        // 정적이라 실질적 영향은 미미하다고 보고 성능을 우선함).

        if (!isChunkLoadedAt(start.getX(), start.getZ())) {
            isRequireKeepSearching = true;
            return false;
        }

        int sx = start.getX(), sy = start.getY(), sz = start.getZ();
        if (isAirAt(sx, sy, sz)) {
            while (isAirAt(sx, sy - 1, sz) && sy > world.getBottomY()) sy--;
        }
        if (sy <= world.getBottomY()) return true;

        // 규칙1: 발밑 셀이 미방문이면 새 루트 색 시드.
        long startCell = BlockPos.asLong(sx, sy, sz);
        if (cellColor.get(startCell) == NO_ID && isStandable(sx, sy, sz, false)) {
            long c = newColor(NO_ID);
            paintCell(sx, sy, sz, c);
            frontierQueue.enqueue(startCell);
        }

        final int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };

        // 보류 프론티어 복귀(청크당 목록 전체).
        ObjectIterator<Long2ObjectMap.Entry<LongArrayList>> exiledIt = exiledFrontierChunkHash.long2ObjectEntrySet().iterator();
        while (exiledIt.hasNext()) {
            Long2ObjectMap.Entry<LongArrayList> e = exiledIt.next();
            long chunkKey = e.getLongKey();
            int chunkX = ChunkPos.getPackedX(chunkKey);
            int chunkZ = ChunkPos.getPackedZ(chunkKey);
            if (taxiDistanceFromChunkToPos(chunkX, chunkZ, sx, sz) < maxRange
                    && world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                LongArrayList pending = e.getValue();
                for (int i = 0, n = pending.size(); i < n; i++) {
                    long cell = pending.getLong(i);
                    enqueueFrontier(cell, BlockPos.unpackLongX(cell), BlockPos.unpackLongZ(cell), sx, sz, maxRange);
                }
                exiledIt.remove();
            }
        }

        final long deadline = System.nanoTime() + STAGING_TIME_BUDGET_NANOS;

        while (!frontierQueue.isEmpty()) {
            updatePixel--;
            if (updatePixel <= 0 || System.nanoTime() >= deadline) {
                isRequireKeepSearching = true;
                break;
            }

            long curPacked = frontierQueue.dequeueLong();
            int cx = BlockPos.unpackLongX(curPacked);
            int cy = BlockPos.unpackLongY(curPacked);
            int cz = BlockPos.unpackLongZ(curPacked);

            if (maxRange < taxiDistance2D(cx, cz, sx, sz)) {
                parkInExiledFrontier(curPacked, cx >> 4, cz >> 4);
                continue;
            }
            if (!isChunkLoadedAt(cx, cz)) {
                parkInExiledFrontier(curPacked, cx >> 4, cz >> 4);
                continue;
            }

            long curColor = cellColor.get(curPacked);
            if (curColor == NO_ID) continue;

            boolean hasBlockAt2Meter = !isAirAt(cx, cy + 2, cz);

            for (int[] d : dirs) {
                int nx = cx + d[0];
                int nz = cz + d[1];

                if (!isChunkLoadedAt(nx, nz)) {
                    // 미로딩 이웃 좌표(nx,nz)가 아니라, 이미 색이 확정된 부모 셀(curPacked)을
                    // 그 부모가 속한 청크(cx,cz)에 park해야 한다. nx,nz는 아직 cellColor에
                    // 등록되지 않은 미확정 좌표라, 나중에 이 값이 그대로 exile 복귀 → frontierQueue로
                    // 들어가도 메인 루프의 `if (curColor == NO_ID) continue;`에서 즉시 버려져
                    // 이 방향의 확장이 영구히 유실된다(플레이어가 직접 그 칸을 밟아 규칙1로
                    // 새로 시드하기 전까지 재시도되지 않음 — 보고된 "막다른 길처럼 보이는" 버그의 원인).
                    // curPacked를 park하면, 청크가 로딩되는 즉시 curPacked가 프론티어로 복귀해
                    // 4방향을 처음부터 다시 검사하므로 실패했던 방향도 재시도된다.
                    parkInExiledFrontier(curPacked, cx >> 4, cz >> 4);
                    continue;
                }

                if (isWallAt(nx, cy, nz)) {
                    // 1칸 벽이라도 땅에서 절대 올라설 수 없다(단방향 예외 없음).
                    // 벽 꼭대기는 오직 카트가 날아서 우연히 착지할 때(고아 시드, 규칙1)만
                    // 그래프에 들어오고, 거기서 서킷 쪽으로 내려가는 것만 별도로 단방향 성립한다.
                    continue;
                }

                int ty = resolveTargetY(nx, cy, nz, hasBlockAt2Meter, world.getBottomY());
                if (ty == Integer.MIN_VALUE) continue;

                boolean twoWay = canMoveBetween(nx, ty, nz, cx, cy, cz, world.getBottomY());
                handleReach(cx, cy, cz, curColor, nx, ty, nz, twoWay, sx, sz, maxRange);
            }
        }

        return true;
    }

    static int resolveTargetY(int nx, int cy, int nz, boolean fromHasBlockAt2Meter, int bottomY) {
        if (!isAirAt(nx, cy + 1, nz)) return Integer.MIN_VALUE;
        if (!isAirAt(nx, cy, nz)) {
            // 기저가 벽 태그면 1칸이라도 절대 올라설 수 없다(단방향 예외 없음).
            if (isWallAt(nx, cy, nz)) return Integer.MIN_VALUE;
            if (isAirAt(nx, cy + 2, nz) && !fromHasBlockAt2Meter) return cy + 1;
            return Integer.MIN_VALUE;
        } else if (isAirAt(nx, cy - 1, nz)) {
            int fy = cy;
            while (isAirAt(nx, fy - 1, nz) && fy > bottomY) fy--;
            return fy;
        } else {
            return cy;
        }
    }

    /** 목적지에서 출발지로 되돌아가는 이동이 규칙상 성립하면 양방향. */
    static boolean canMoveBetween(int tx, int ty, int tz, int fx, int fy, int fz, int bottomY) {
        if (isWallAt(fx, ty, fz)) return false; // 되돌아갈 칸 기저가 벽이면 역방향 불가
        boolean tHasBlockAt2 = !isAirAt(tx, ty + 2, tz);
        int back = resolveTargetY(fx, ty, fz, tHasBlockAt2, bottomY);
        return back == fy;
    }

    static void handleReach(int cx, int cy, int cz, long curColor, int tx, int ty, int tz, boolean twoWay,
                            int sx, int sz, int maxRange) {
        long targetCell = BlockPos.asLong(tx, ty, tz);
        long existing = cellColor.get(targetCell);
        if (existing == NO_ID) {
            // 평지(ty==cy)·계단(ty==cy+1)은 resolveTargetY가 이미 같은 좌표의 머리 공간을
            // 확인했으므로 재조회하지 않는다. 낙하(ty<cy)는 최종 착지 높이를 아직 안 봤으므로 재확인.
            boolean headAlreadyChecked = (ty >= cy);
            if (!isStandable(tx, ty, tz, headAlreadyChecked)) return;
            long color = twoWay ? curColor : newColor(curColor);
            paintCell(tx, ty, tz, color);
            enqueueFrontier(targetCell, tx, tz, sx, sz, maxRange);
        } else {
            if (twoWay) mergeColors(curColor, existing);
            // 단방향이면 병합하지 않음.
        }
    }

    static void paintCell(int x, int y, int z, long color) {
        long cell = BlockPos.asLong(x, y, z);
        cellColor.put(cell, color);
        long colKey = packColumn(x, z);
        IntOpenHashSet ys = visitedColumns.get(colKey);
        if (ys == null) {
            ys = new IntOpenHashSet(4);
            visitedColumns.put(colKey, ys);
        }
        ys.add(y);
        // DEBUG: 즉시 그 컬럼만 도색(가벼움, resolve만).
        // 비디버그: activeSet 기반 판정이라, 이번 틱 끝에 repaintAll로 일괄 반영.
        if (DEBUG_COLORS) plotColumn(x, z);
        else needsFullRepaint = true;
    }

    // ── 좌표 유틸 ──────────────────────────────────────────────────────

    static long packColumn(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
    static int unpackColumnX(long key) { return (int) (key >> 32); }
    static int unpackColumnZ(long key) { return (int) key; }

    static int taxiDistance2D(int ax, int az, int bx, int bz) {
        return Math.abs(ax - bx) + Math.abs(az - bz);
    }
    static int taxiDistanceFromChunkToPos(int chunkX, int chunkZ, int bx, int bz) {
        return Math.abs(chunkX * 16 - bx) + Math.abs(chunkZ * 16 - bz);
    }

    static void clearAllMap() {
        cellColor.clear();
        visitedColumns.clear();
        frontierQueue.clear();
        exiledFrontierChunkHash.clear();
        colorParentPtr.clear();
        childToParents.clear();
        parentToChildren.clear();
        colorBirth.clear();
        nextColorId = 0;
        birthCounter = 0;
        activeColor = NO_ID;
        needsFullRepaint = false;
        invalidateChunkCache();
        if (image != null) {
            image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
            textureDirty = true;
        }
        originSet = false;
    }

    static void parkInExiledFrontier(long packedPos, int chunkX, int chunkZ) {
        long key = ChunkPos.toLong(chunkX, chunkZ);
        LongArrayList pending = exiledFrontierChunkHash.get(key);
        if (pending == null) {
            pending = new LongArrayList(4);
            exiledFrontierChunkHash.put(key, pending);
        }
        pending.add(packedPos);
    }
}