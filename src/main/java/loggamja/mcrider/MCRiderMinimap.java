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
    public static final boolean DEBUG_COLORS = false;

    static MinecraftClient client = MinecraftClient.getInstance();

    private static final long NO_ID = Long.MIN_VALUE;

    // ── 탐색 상태 ────────────────────────────────────────────────────────
    static boolean isRequireKeepSearching = false;
    static BlockPos lastPlayerPos;

    /** [개선] 활성 프론티어를 청크 단위로 묶어서 보관한다: chunkKey(ChunkPos.toLong) → 그
     *  청크 안의 대기 중인 셀 목록. 예전의 단일 FIFO 큐(frontierQueue)는 인접한 셀이라도
     *  서로 다른 청크에서 뒤섞여 나올 수 있어, 매번 청크 캐시(4슬롯)를 갈아치우게 만들었다.
     *  청크별로 묶어서 "한 청크를 다 처리하고 다음 청크로" 넘어가면 stateAt() 호출이 대부분
     *  캐시에 걸린다.
     *  주의(과거에 프론티어 관련 버그가 많았던 부분): 이 구조는 오직 "어떤 순서로 처리
     *  하느냐"만 바꾼다. 각 셀에 적용되는 검사(범위 안인가, 청크 로딩됐는가, 활성 영역인가,
     *  벽인가 등)는 이전 코드와 한 글자도 다르지 않다. 즉 "최종적으로 어떤 셀이 결국
     *  탐색되는가"라는 정확성에는 전혀 영향을 주지 않는다 — 오직 처리 순서(와 그에 따른
     *  캐시 효율)만 바뀐다. 유실 방지를 위해, 어떤 셀이든 반드시 frontierByChunk 아니면
     *  exiledFrontierChunkHash 둘 중 하나에는 항상 들어있다(두 구조 다 청크별 리스트라
     *  형태가 동일 — enqueueFrontier가 조건에 따라 둘 중 하나로만 보낸다). */
    static Long2ObjectOpenHashMap<LongArrayList> frontierByChunk = new Long2ObjectOpenHashMap<>();
    /** frontierByChunk에 현재 대기 중인 셀이 있는 청크 키 집합(거리순 정렬 대상 스냅샷용). */
    static LongOpenHashSet frontierChunkKeys = new LongOpenHashSet();
    /** 미로딩/범위 밖 청크에 보류된 프론티어: ChunkPos.toLong → 셀 목록(청크당 여러 개, 유실 방지) */
    static Long2ObjectOpenHashMap<LongArrayList> exiledFrontierChunkHash = new Long2ObjectOpenHashMap<>();

    /** 방문 셀 → 색 ID. 키 BlockPos.asLong, 값 색 ID(불변). */
    static Long2LongOpenHashMap cellColor = new Long2LongOpenHashMap();
    /** packColumn(x,z) → 방문 Y 집합(텍스처 도색 대상). */
    static Long2ObjectOpenHashMap<IntOpenHashSet> visitedColumns = new Long2ObjectOpenHashMap<>();
    /** [개선] 이번 틱(또는 병합/활성색 변경으로 인해) 다시 그려야 할 컬럼만 모아두는 집합.
     *  예전에는 새 칸이 하나라도 칠해지면 needsFullRepaint 플래그를 세워 visitedColumns
     *  전체(지금까지 탐색한 모든 컬럼)를 다시 그렸다. 지금은 columnsByRoot 역인덱스 덕분에
     *  병합/활성색 변경이 일어나도 "실제로 영향받는 색의 컬럼"만 정확히 골라 여기 추가할 수
     *  있어, 매 틱 비용이 "지금까지 탐색한 총량"이 아니라 "이번에 실제로 바뀐 컬럼 수"에
     *  비례하게 된다. */
    static LongOpenHashSet dirtyColumns = new LongOpenHashSet();
    /** [개선] 루트 색 → 그 색이 칠해진 컬럼들의 역인덱스. 병합/활성색 변경이 일어났을 때
     *  "영향받는 색의 컬럼만" 정확히 찾아 dirtyColumns에 넣기 위한 것으로, 이게 있어야
     *  전체 visitedColumns를 훑지 않고도 정확한 부분 재도색이 가능하다.
     *  paintCell에서 채워지고, mergeColors/rescanCycles에서 색이 합쳐질 때 살아남은
     *  루트 쪽으로 이전(migrate)된다. */
    static Long2ObjectOpenHashMap<LongOpenHashSet> columnsByRoot = new Long2ObjectOpenHashMap<>();

    // ── 색 관계 그래프 & 포인터 딕셔너리 ─────────────────────────────────
    static Long2LongOpenHashMap colorParentPtr = new Long2LongOpenHashMap();
    static Long2ObjectOpenHashMap<LongOpenHashSet> childToParents = new Long2ObjectOpenHashMap<>();
    static Long2ObjectOpenHashMap<LongOpenHashSet> parentToChildren = new Long2ObjectOpenHashMap<>();
    static Long2IntOpenHashMap colorBirth = new Long2IntOpenHashMap();

    static long nextColorId = 0;
    static int birthCounter = 0;

    static long activeColor = NO_ID;

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
        dirtyColumns.clear();
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

    /** [개선] color graph 구조가 실제로 바뀔 때만(새 엣지 추가, 병합) 증가하는 버전 번호.
     *  activeColor와 이 버전이 지난 rebuildActiveSet 호출 때와 동일하면, 그래프 상으로
     *  아무 것도 바뀌지 않았다는 뜻이므로 BFS를 다시 돌 필요가 없다. 이전에는 이 캐시가
     *  없어서 activeColor/그래프가 그대로여도 매 틱 무조건 BFS를 다시 수행했다. */
    private static long colorGraphVersion = 0;
    private static long activeSetVersion = -1;
    private static long activeSetSnapshotColor = NO_ID;

    static void bumpColorGraphVersion() {
        colorGraphVersion++;
    }

    /** root 색에 속한 컬럼들을 전부 dirtyColumns에 추가한다(부분 재도색 대상 지정).
     *  visitedColumns 전체가 아니라 이 root의 columnsByRoot 버킷만 훑으므로, 맵 전체
     *  크기가 아니라 "이 색이 실제로 칠한 컬럼 수"에 비례하는 저렴한 연산이다. */
    static void markColumnsDirtyForRoot(long root) {
        LongOpenHashSet cols = columnsByRoot.get(root);
        if (cols != null) dirtyColumns.addAll(cols);
    }

    /** rebuildActiveSet이 재계산할 때 "직전 activeSet"을 잠깐 담아두는 diff용 버퍼. */
    private static final LongOpenHashSet prevActiveSetForDiff = new LongOpenHashSet();

    private static void rebuildActiveSet() {
        if (activeSetSnapshotColor == activeColor && activeSetVersion == colorGraphVersion) {
            return; // 캐시 유효: activeColor도 그래프도 지난 계산 이후 바뀌지 않았다.
        }
        // [개선] 다시 계산하기 전에 이전 activeSet 내용을 보존해둔다. 재계산이 끝난 뒤
        // 이 이전 집합과 새 집합을 비교해서, "활성에 새로 들어오거나 빠진 루트"만 골라
        // 그 루트의 컬럼만 dirty로 표시한다. 이렇게 하면 activeColor가 바뀌거나 병합으로
        // 활성 집합 구성이 바뀌어도, 예전처럼 visitedColumns 전체를 훑는 repaintAll() 없이
        // "실제로 화면에 나타나거나 사라져야 하는 컬럼"만 정확히 다시 그리게 된다.
        // DEBUG_COLORS 모드는 activeSet과 무관하게 항상 고유 색으로 그리므로(표시가
        // activeColor에 의존하지 않음) 이 diff가 필요 없다.
        prevActiveSetForDiff.clear();
        if (!DEBUG_COLORS) prevActiveSetForDiff.addAll(activeSet);

        activeSet.clear();
        if (activeColor != NO_ID) {
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
        activeSetSnapshotColor = activeColor;
        activeSetVersion = colorGraphVersion;

        if (!DEBUG_COLORS) {
            // 이탈한 루트(이전엔 활성, 지금은 아님)와 진입한 루트(이전엔 비활성, 지금은 활성)
            // 각각의 컬럼만 dirty로 표시한다.
            LongIterator it = prevActiveSetForDiff.iterator();
            while (it.hasNext()) {
                long r = it.nextLong();
                if (!activeSet.contains(r)) markColumnsDirtyForRoot(r);
            }
            it = activeSet.iterator();
            while (it.hasNext()) {
                long r = it.nextLong();
                if (!prevActiveSetForDiff.contains(r)) markColumnsDirtyForRoot(r);
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
        bumpColorGraphVersion();
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
            if (c != survivor) {
                // [개선] 이 색(c)에 속한 컬럼들을 dirty로 표시(색 정체성이 바뀌므로 debug
                // 모드의 색상이 달라질 수 있고, 화이트 모드에서도 활성 여부가 바뀔 수 있다)
                // 한 뒤, 그 컬럼들을 생존 루트(survivor) 쪽 버킷으로 이전한다. 이 비용은
                // "이 병합에 실제로 관련된 컬럼 수"에만 비례하므로, visitedColumns 전체를
                // 훑는 것보다 훨씬 저렴하다.
                markColumnsDirtyForRoot(c);
                LongOpenHashSet cols = columnsByRoot.remove(c);
                if (cols != null) {
                    columnsByRoot.computeIfAbsent(survivor, k -> new LongOpenHashSet()).addAll(cols);
                }
                colorParentPtr.put(c, survivor);
            }
        }
        bumpColorGraphVersion();

        if (group.contains(activeColor) && activeColor != survivor) {
            activeColor = survivor;
        }

        rescanCycles(survivor);
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
                markColumnsDirtyForRoot(d);
                LongOpenHashSet cols = columnsByRoot.remove(d);
                if (cols != null) {
                    columnsByRoot.computeIfAbsent(survivor, k -> new LongOpenHashSet()).addAll(cols);
                }
                colorParentPtr.put(d, survivor);
                if (activeColor == d) activeColor = survivor;
                merged = true;
            }
        }
        if (merged) {
            bumpColorGraphVersion();
            rescanCycles(survivor);
        }
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

        // [순서 수정] 텍스처를 (재)생성하기 전에 activeColor와 activeSet을 먼저 확정한다.
        // rebuildTexture는 plotColumn → computeColumnColor에서 activeSet을 읽어 색을
        // 결정하므로, 이 계산이 최신이 아니면 이번 틱에 새로 생긴 색이 담긴 컬럼이
        // 잘못(투명하게) 그려진다. 특히 re-anchor 틱에서는 rebuildTexture가 dirtyColumns까지
        // 비우기 때문에, 뒤늦게 activeSet을 갱신해도 그 컬럼을 다시 그릴 기회가 사라져
        // 새로 탐색한 트랙이 한동안 안 보이는 문제가 생겼었다.
        updateActiveColor(start);
        rebuildActiveSet();

        if (!originSet) {
            rebuildTexture(start);
        } else {
            int cx = start.getX() - (originX + TEX_SIZE / 2);
            int cz = start.getZ() - (originZ + TEX_SIZE / 2);
            if (Math.abs(cx) > TEX_SIZE / 2 - REANCHOR_MARGIN
                    || Math.abs(cz) > TEX_SIZE / 2 - REANCHOR_MARGIN) {
                rebuildTexture(start);
            }
        }

        // [개선] "전체 다시 그리기"라는 개념 자체를 없앴다. mergeColors/rescanCycles/
        // handleReach(기존 칸 편입)/rebuildActiveSet(활성색 diff)이 각자 "영향받는 색의
        // 컬럼"만 정확히 dirtyColumns에 넣어두므로, 여기서는 그 목록만 다시 그리면 된다.
        // 비용이 항상 "실제로 바뀐 컬럼 수"에 비례하고, visitedColumns 전체 크기(그동안
        // 탐색한 총량)와는 무관해졌다 — 공터로 탐색이 새어나가 지도가 아무리 커져도,
        // 그와 무관한 곳에서 일어난 병합의 재도색 비용은 늘지 않는다.
        if (!dirtyColumns.isEmpty() && originSet) {
            LongIterator dirtyIt = dirtyColumns.iterator();
            while (dirtyIt.hasNext()) {
                long key = dirtyIt.nextLong();
                plotColumn(unpackColumnX(key), unpackColumnZ(key));
            }
            dirtyColumns.clear();
        }
    }

    static void updateActiveColor(BlockPos start) {
        long cell = resolvePlayerCell(start);
        if (cell == NO_ID) return;
        long id = cellColor.get(cell);
        if (id == NO_ID) return;
        long root = resolve(id);
        activeColor = root;
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
     * 활성 프론티어(frontierByChunk)로 되돌린다.
     *
     * (이전에는 색이 활성 색과 다르면 플레이어와 아무리 가까워도 사실상 영원히 exile에
     * 갇히는 버그가 있었다 — 좁은 외길에서는 경로가 죄다 같은 색이라 안 드러났지만,
     * 넓은 도로에서 옆 차선이 지형 조건 때문에 다른 색으로 갈라지면 그 차선 전체가
     * 체스판처럼 듬성듬성 비어버렸다. 색은 순전히 "어느 구간이 연결돼 있는지"를 나타내는
     * 라벨일 뿐, 탐색을 진행할지 말지를 결정하는 조건이 되면 안 된다.)
     */
    /** 셀을 활성 프론티어의 청크 버킷에 추가한다. 그 청크가 처음 생기는 것이면
     *  frontierChunkKeys에도 등록해 다음 정렬 스냅샷에 포함되게 한다. */
    static void frontierPush(long cell, int cx, int cz) {
        long chunkKey = ChunkPos.toLong(cx >> 4, cz >> 4);
        LongArrayList bucket = frontierByChunk.get(chunkKey);
        if (bucket == null) {
            bucket = new LongArrayList(8);
            frontierByChunk.put(chunkKey, bucket);
            frontierChunkKeys.add(chunkKey);
        }
        bucket.add(cell);
    }

    static void enqueueFrontier(long cell, int cx, int cz, int sx, int sz, int maxRange) {
        if (taxiDistance2D(cx, cz, sx, sz) <= maxRange) {
            frontierPush(cell, cx, cz);
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
            frontierPush(startCell, sx, sz);
        }

        // [개선] 오픈 필드 블리딩 억제.
        // 시드까지 끝난 "지금 이 순간"의 플레이어 영역과 활성셋을 fresh하게 확정한다.
        // (시드가 방금 visitedColumns에 들어갔으므로 resolvePlayerCell이 새 영역도 바로 찾는다.
        //  이 순서 덕분에 예전처럼 "갓 태어난 색이 stale activeSet에 없어 확장이 막히는" 회귀가
        //  생기지 않는다.)
        // 그리고 프론티어 확장을 "플레이어가 지금 서 있는 영역 + 그 자손"으로만 제한한다.
        //  · 서킷과 서킷 사이 공터를 점프로 건너뛰면(공터 땅을 밟지 않으면) 공터는 시드조차
        //    안 되므로 아예 탐색되지 않는다.
        //  · 잠깐 공터에 발을 디뎌 시드됐더라도, 플레이어가 서킷으로 넘어가는 순간 공터 영역은
        //    더 이상 활성이 아니라 그쪽 프론티어 확장이 즉시 멈춘다(무한 블리딩 차단).
        // 안전장치: 이번 틱에 플레이어 영역을 확실히 특정하지 못하면(resolvePlayerCell 실패)
        //  필터를 아예 끄고 종전처럼 무제한 확장한다. 이렇게 하면 영역 판정 실패가 "서킷 위에
        //  있는데 탐색이 끊기는" 상황을 새로 만들지 않는다(안전 우선).
        // [수정] activeSet은 이제 표시(비디버그)뿐 아니라 "탐색을 활성 영역+자손으로
        // 제한하는 필터"의 기준이기도 하므로, debug 모드에서도 항상 계산한다.
        updateActiveColor(start);
        rebuildActiveSet();
        // [수정] 필터를 debug 모드에서도 적용한다(공중으로 날아간 영역 등 비활성 영역이
        // 계속 탐색되던 문제 차단). 활성 영역을 확실히 특정하지 못한 틱에만 필터를 꺼서
        // "서킷 위에 있는데 탐색이 끊기는" 상황을 새로 만들지 않는다(안전 우선).
        final boolean containToActive = resolvePlayerCell(start) != NO_ID;

        final int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };

        // 보류 프론티어 복귀(청크당 목록 전체).
        // [버그 수정] 예전에는 이 순회 도중 enqueueFrontier → (범위 밖이면) parkInExiledFrontier를
        // 호출해서, 지금 순회 중인 바로 그 exiledFrontierChunkHash 맵에 새 항목을 추가했다.
        // 맵을 순회하면서 동시에 그 맵에 쓰는 것은 위험하다 — 예외 없이도 일부 항목이 이번
        // 순회에서 조용히 누락될 수 있고, 그러면 그 항목은 실제로는 로딩·범위 조건을 만족해도
        // 다시는 검사되지 않고 영구히 갇힌다("청크 로드 경계에서 exile된 셀이 재활성화 안 됨"의
        // 실제 원인). 그래서 순회 중에는 조건에 맞는 청크의 셀들을 임시 리스트에 모아 맵에서만
        // 제거하고, 맵 순회가 완전히 끝난 뒤에 그 셀들을 안전하게 재분류(enqueueFrontier)한다.
        LongArrayList revivedCells = new LongArrayList();
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
                    revivedCells.add(pending.getLong(i));
                }
                exiledIt.remove();
            }
        }
        for (int i = 0, n = revivedCells.size(); i < n; i++) {
            long cell = revivedCells.getLong(i);
            enqueueFrontier(cell, BlockPos.unpackLongX(cell), BlockPos.unpackLongZ(cell), sx, sz, maxRange);
        }

        final long deadline = System.nanoTime() + STAGING_TIME_BUDGET_NANOS;

        // [개선] 프론티어를 청크 단위로 묶어 처리하되, 매 "라운드"마다 현재 대기 중인
        // 청크들을 플레이어와의 거리순으로 정렬해 가까운 청크부터 완전히 비운다.
        // 라운드를 반복하는 이유: 한 라운드를 도는 도중 발견된 새 청크(이웃 셀 탐색이
        // 경계를 넘어가며 생김)는 이번 라운드의 정렬 스냅샷에는 없었으므로, 다음 라운드
        // 시작 시 frontierChunkKeys를 다시 스냅샷 뜰 때 자연스럽게 포함된다. 즉 시간/예산이
        // 허용하는 한 새로 생긴 청크도 같은 틱 안에서 계속 이어서 처리되고, 유실되는
        // 셀은 없다(budget/deadline이 다 되면 그 시점 그대로 다음 틱에 이어서 처리됨 —
        // 이전과 동일한 안전성).
        boolean stop = false;
        while (!stop && !frontierChunkKeys.isEmpty()) {
            // 현재 대기 중인 청크 키들을 배열로 스냅샷 뜬 뒤, 거리 기준으로 직접 삽입정렬한다.
            // (LongArrayList.sort(람다)는 java.util.List의 Comparator<Long> 오버로드와
            // fastutil의 LongComparator 오버로드가 동시에 맞아떨어져 컴파일이 모호해질 수
            // 있어, 그런 위험이 전혀 없는 순수 배열 삽입정렬을 쓴다. 청크 개수는 보통
            // 수십~수백 개 수준이라 O(n²)이어도 충분히 저렴하다.)
            int n = frontierChunkKeys.size();
            long[] keys = new long[n];
            int[] dist = new int[n];
            int idx = 0;
            LongIterator keyIt = frontierChunkKeys.iterator();
            while (keyIt.hasNext()) {
                long k = keyIt.nextLong();
                keys[idx] = k;
                dist[idx] = taxiDistanceFromChunkToPos(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k), sx, sz);
                idx++;
            }
            for (int i = 1; i < n; i++) {
                long k = keys[i];
                int dk = dist[i];
                int j = i - 1;
                while (j >= 0 && dist[j] > dk) {
                    keys[j + 1] = keys[j];
                    dist[j + 1] = dist[j];
                    j--;
                }
                keys[j + 1] = k;
                dist[j + 1] = dk;
            }

            for (int ci = 0; ci < n; ci++) {
                long chunkKey = keys[ci];
                LongArrayList bucket = frontierByChunk.get(chunkKey);
                if (bucket == null) continue; // 이미 다른 경로로 비워졌을 수 있음(방어적)

                while (!bucket.isEmpty()) {
                    if (System.nanoTime() >= deadline) {
                        isRequireKeepSearching = true;
                        stop = true;
                        break;
                    }

                    // 청크 버킷은 스택처럼 뒤에서 꺼낸다(배열 기반이라 앞에서 꺼내면 매번
                    // 전체가 한 칸씩 밀려 O(n)이 된다 — 순서 자체는 정확성에 영향 없음).
                    long curPacked = bucket.removeLong(bucket.size() - 1);
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

                    // [수정] 플레이어가 지금 있는 영역(+자손)이 아니면 확장하지 않는다.
                    // 예전에는 dormant 집합에 넣고 "활성색이 바뀔 때만" 깨웠는데, 정지 상태에서는
                    // 활성색이 안 바뀌어 영영 안 깨어나 탐색이 끊긴 채 유지되는 문제가 있었다.
                    // 이제는 exile(보류)로 되돌린다. exile은 매 틱 복귀 루프가 자동으로 재평가하므로,
                    // 그 영역이 다시 활성이 되거나 병합으로 활성 트리에 편입되는 순간 자연히 확장이
                    // 재개된다(정지 상태에서도, 이동 없이). 비활성인 동안에는 dequeue→검사→재park만
                    // 반복하고 실제 확장(블록 조회·페인트)은 하지 않으므로 CPU도 아낀다.
                    // debug 모드에서도 "탐색은 활성 영역+자손만" 제한한다(표시는 모드별로 다름).
                    if (containToActive && !activeSet.contains(resolve(curColor))) {
                        parkInExiledFrontier(curPacked, cx >> 4, cz >> 4);
                        continue;
                    }

                    boolean hasBlockAt2Meter = !isAirAt(cx, cy + 2, cz);

                    for (int[] d : dirs) {
                        int nx = cx + d[0];
                        int nz = cz + d[1];

                        if (!isChunkLoadedAt(nx, nz)) {
                            // 미로딩 이웃 좌표(nx,nz)가 아니라, 이미 색이 확정된 부모 셀(curPacked)을
                            // 그 부모가 속한 청크(cx,cz)에 park해야 한다. nx,nz는 아직 cellColor에
                            // 등록되지 않은 미확정 좌표라, 나중에 이 값이 그대로 exile 복귀 → 프론티어로
                            // 들어가도 메인 루프의 `if (curColor == NO_ID) continue;`에서 즉시 버려져
                            // 이 방향의 확장이 영구히 유실된다(플레이어가 직접 그 칸을 밟아 규칙1로
                            // 새로 시드하기 전까지 재시도되지 않음 — 보고된 "막다른 길처럼 보이는" 버그의 원인).
                            // curPacked를 park하면, 청크가 로딩되는 즉시 curPacked가 프론티어로 복귀해
                            // 4방향을 처음부터 다시 검사하므로 실패했던 방향도 재시도된다.
                            parkInExiledFrontier(curPacked, cx >> 4, cz >> 4);
                            continue;
                        }

                        if (!isAirAt(nx, cy, nz) && isWallAt(nx, cy, nz)) {
                            // [버그 수정] 이전 코드는 목적지가 공기인지 먼저 보지 않고 isWallAt만으로
                            // 곧장 continue 했다. 그 결과 "벽 위에 서 있다가(고아 시드) 서킷 쪽으로
                            // 내려가는" 이동까지 이 시점에서 걸러져 resolveTargetY까지 가보지도 못하고
                            // 막혀버렸다(사실상 벽 위가 항상 막다른 길처럼 보이는 원인).
                            // resolveTargetY도 "같은 높이에 벽이 있으면 그 위로 못 올라선다"는 조건을
                            // 이미 정확히 처리하므로(!isAirAt(nx,cy,nz) 분기 안에서 isWallAt 검사),
                            // 여기서는 그 조건과 완전히 동일하게 "목적지가 공기가 아니고, 그 몸체가
                            // 벽일 때"만 차단한다. 목적지가 공기라면(벽 위에서 아래 서킷으로 내려가는
                            // 낙하/평지 이동 포함) 이 검사에 걸리지 않고 resolveTargetY로 넘어가
                            // 정상적으로 낙하/평지 이동이 계산된다 — 즉 "올라서는 것"만 막고
                            // "내려가는 것"은 더 이상 여기서 막히지 않는다.
                            continue;
                        }

                        int ty = resolveTargetY(nx, cy, nz, hasBlockAt2Meter, world.getBottomY());
                        if (ty == Integer.MIN_VALUE) continue;

                        boolean twoWay = canMoveBetween(nx, ty, nz, cx, cy, cz, world.getBottomY());
                        handleReach(cx, cy, cz, curColor, nx, ty, nz, twoWay, sx, sz, maxRange);
                    }

                    // 실제 확장 작업(4방향 검사)을 마친 셀에 대해서만 예산을 소모한다. 범위 밖·미로딩·
                    // 비활성으로 재park되어 continue된 값싼 셀은 예산을 먹지 않으므로, 비활성 영역의
                    // 재분류 churn이 활성 트랙의 탐색 예산을 굶기지 않는다(시간 상한 deadline은 위에서
                    // 매 iteration 확인하므로 전체 소요 시간은 여전히 0.5ms로 제한된다).
                    if (--updatePixel <= 0) {
                        isRequireKeepSearching = true;
                        stop = true;
                        break;
                    }
                }

                if (bucket.isEmpty()) {
                    frontierByChunk.remove(chunkKey);
                    frontierChunkKeys.remove(chunkKey);
                }
                if (stop) break;
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
            long color;
            if (twoWay) {
                color = curColor;
            } else {
                color = newColor(curColor);
                // [수정] 방금 만든 자식 색을, 부모가 활성 집합(활성 영역+자손)에 있으면
                // 즉시 activeSet에도 넣는다. 이렇게 하지 않으면 이 자식은 "틱 시작 때 계산된
                // activeSet"에 없어서, 다음 프론티어 처리 때 곧바로 걸러져(확장 중단) 씨앗 한
                // 칸만 남는다. 부모가 활성이면 그 자식/자손도 당연히 활성 영역의 일부이므로
                // 여기서 바로 포함시켜 계속 확장·표시되게 한다.
                if (activeSet.contains(resolve(curColor))) activeSet.add(resolve(color));
            }
            paintCell(tx, ty, tz, color);
            enqueueFrontier(targetCell, tx, tz, sx, sz, maxRange);
        } else {
            if (twoWay) {
                mergeColors(curColor, existing);
            } else {
                // [버그 수정] 단방향으로 "이미 다른 경로에서 먼저 칠해진 칸"에 도달한
                // 경우. 예전에는 여기서 아무 관계도 기록하지 않았다. 그 결과 예를 들어
                // 위쪽 트랙(활성)에서 아래로 내려가는 단차가 있는데, 아래쪽 저지대가
                // 이미 다른 진입로로 먼저 탐색·색칠돼 있으면(existing != NO_ID) 부모-자식
                // 관계가 전혀 남지 않아 activeColor(위)가 활성이어도 아래는 "자손"으로
                // 인정되지 않아 화면에 표시되지 않았다. 탐색/색칠 자체는 정상이고, 표시
                // 판정(활성+자손)에 쓰이는 그래프 관계만 누락됐던 것.
                // 색은 합치지 않는다(양방향이 아니므로 서로 대등하지 않다 — 규칙3 유지).
                // 대신 부모→자식 방향 엣지만 추가해 표시 판정에 반영한다.
                long parentRoot = resolve(curColor);
                long childRoot = resolve(existing);
                if (parentRoot != childRoot) {
                    // 사이클 방지: 보통은 자식(child)이 부모보다 나중에 태어나므로 birth
                    // 비교만으로 안전하다고 바로 판단할 수 있다. 드물게 이미 존재하던 색이
                    // 더 먼저 태어난 경우(=지금 막 추가하려는 방향과 반대로 이미 조상-자손
                    // 관계가 성립해 있을 가능성)에만 조상 집합을 실제로 검사해 진짜 사이클
                    // (A→B가 이미 있는데 B→A를 또 추가)이 생기지 않는지 확인한다.
                    boolean safeToAdd = colorBirth.get(childRoot) > colorBirth.get(parentRoot);
                    if (!safeToAdd) {
                        LongOpenHashSet parentAncestors = new LongOpenHashSet();
                        collectAncestors(parentRoot, parentAncestors);
                        safeToAdd = !parentAncestors.contains(childRoot);
                    }
                    if (safeToAdd) {
                        addEdge(parentRoot, childRoot);
                        if (activeSet.contains(parentRoot)) {
                            activeSet.add(childRoot);
                            // childRoot에 속한 컬럼들은 이미 예전에 칠해져 dirtyColumns에서
                            // 빠진 지 오래다. 지금 막 활성으로 편입됐으므로, 그 컬럼들만
                            // 다시 평가되도록 표시한다(전체 재도색 불필요).
                            markColumnsDirtyForRoot(childRoot);
                        }
                    }
                }
            }
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
        // [개선] 이 컬럼을 방금 칠한 색의 (현재) 루트 아래 역인덱스에도 등록한다.
        // 나중에 이 루트가 다른 색과 병합되면, 이 컬럼은 mergeColors에서 생존 루트
        // 쪽으로 함께 이전(migrate)된다.
        long root = resolve(color);
        columnsByRoot.computeIfAbsent(root, k -> new LongOpenHashSet()).add(colKey);
        // DEBUG: 즉시 그 컬럼만 도색(가벼움, resolve만).
        // 비디버그: 이번 틱에 새로 칠해진 컬럼만 dirtyColumns에 쌓아둔다. 병합/활성색
        // 변경으로 인한 기존 컬럼 재도색은 이제 markColumnsDirtyForRoot로 별도 처리되므로,
        // 여기서는 항상 "새로 칠해진" 이 컬럼만 추가하면 된다.
        if (DEBUG_COLORS) {
            plotColumn(x, z);
        } else {
            dirtyColumns.add(colKey);
        }
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
    /** [버그 수정] 이전에는 청크의 (0,0) 로컬 코너 좌표(chunkX*16, chunkZ*16)만으로 거리를
     *  쟀다. 플레이어보다 서/북쪽에 있는 청크는 이 코너가 "가장 먼 변"이라 거리가 과대평가되어,
     *  청크의 가까운 변이 이미 탐색 범위 안에 들어왔는데도 exile에서 복귀하지 못하고 갇히는
     *  경우가 있었다(플레이어가 그 방향으로 더 다가가기 전까지 탐색이 재개되지 않음 →
     *  "서킷 위에 멀쩡히 있는데 탐색이 끊긴 채 유지"되는 증상의 한 원인). 여기서는 청크가
     *  차지하는 16×16 영역 중 플레이어에 가장 가까운 점까지의 택시 거리를 계산한다. */
    static int taxiDistanceFromChunkToPos(int chunkX, int chunkZ, int bx, int bz) {
        int minX = chunkX << 4, maxX = minX + 15;
        int minZ = chunkZ << 4, maxZ = minZ + 15;
        int dx = bx < minX ? minX - bx : (bx > maxX ? bx - maxX : 0);
        int dz = bz < minZ ? minZ - bz : (bz > maxZ ? bz - maxZ : 0);
        return dx + dz;
    }

    static void clearAllMap() {
        cellColor.clear();
        visitedColumns.clear();
        dirtyColumns.clear();
        columnsByRoot.clear();
        frontierByChunk.clear();
        frontierChunkKeys.clear();
        exiledFrontierChunkHash.clear();
        colorParentPtr.clear();
        childToParents.clear();
        parentToChildren.clear();
        colorBirth.clear();
        nextColorId = 0;
        birthCounter = 0;
        activeColor = NO_ID;
        activeSet.clear();
        colorGraphVersion = 0;
        activeSetVersion = -1;
        activeSetSnapshotColor = NO_ID;
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