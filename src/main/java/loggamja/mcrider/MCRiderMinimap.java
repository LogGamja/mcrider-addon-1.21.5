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
import com.mojang.blaze3d.systems.RenderSystem;
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
 * 규칙0: 양방향 이동으로 새 칸 진입 → 같은 색 유지.
 * 규칙1: 고아 상태(빈 칸)로 진입 → 새 루트 색(TP/리스폰도 이 범용 동작으로 처리).
 * 규칙2: 단방향 이동으로 새 칸 도달 → 새 색 + 직전 색의 자식.
 * 규칙3: 두 색이 양방향으로 인접하면 병합(단방향 인접은 병합 안 함).
 * 규칙4(표시): 플레이어 위치 색(resolve) + 그 자손만 표시. DEBUG_COLORS=true면 색상별로 구분 표시.
 * 병합은 포인터 딕셔너리(resolve, 경로 압축)로 처리한다.
 */
public class MCRiderMinimap implements ClientModInitializer {

    /** true면 모든 색을 각자 다른 색으로 표시(디버그). 기본 false. */
    public static final boolean DEBUG_COLORS = false;

    static MinecraftClient client = MinecraftClient.getInstance();

    private static final long NO_ID = Long.MIN_VALUE;

    // ── 탐색 상태 ────────────────────────────────────────────────────────
    static boolean needsMoreSearching = false;
    static BlockPos lastPlayerPos;

    /** 활성 프론티어를 청크 단위로 묶어 보관: chunkKey(ChunkPos.toLong) → 대기 셀 목록.
     *  청크별로 몰아 처리하면 stateAt()의 청크 캐시(4슬롯) 히트율이 높아진다.
     *  이 구조는 처리 "순서"만 바꿀 뿐 어떤 셀이 탐색되는지(정확성)에는 영향 없다.
     *  모든 셀은 항상 frontierByChunk 또는 exiledFrontierChunkHash 둘 중 하나에만 존재한다. */
    static Long2ObjectOpenHashMap<LongArrayList> frontierByChunk = new Long2ObjectOpenHashMap<>();
    /** frontierByChunk에 현재 대기 중인 셀이 있는 청크 키 집합(거리순 정렬 스냅샷용). */
    static LongOpenHashSet frontierChunkKeys = new LongOpenHashSet();
    /** 미로딩/범위 밖 청크에 보류된 프론티어: ChunkPos.toLong → 셀 목록. */
    static Long2ObjectOpenHashMap<LongArrayList> exiledFrontierChunkHash = new Long2ObjectOpenHashMap<>();

    /** 방문 셀 → 색 ID. 키 BlockPos.asLong, 값 색 ID(불변). */
    static Long2LongOpenHashMap cellColor = new Long2LongOpenHashMap();
    /** packColumn(x,z) → 방문 Y 집합(텍스처 도색 대상). */
    static Long2ObjectOpenHashMap<IntOpenHashSet> visitedColumns = new Long2ObjectOpenHashMap<>();
    /** 이번 틱(또는 병합/활성색 변경)으로 다시 그려야 할 컬럼 집합.
     *  columnsByRoot 역인덱스 덕분에 "실제로 영향받는 컬럼"만 골라 담을 수 있어,
     *  재도색 비용이 전체 탐색량이 아니라 실제 변경분에 비례한다. */
    static LongOpenHashSet dirtyColumns = new LongOpenHashSet();
    /** 루트 색 → 그 색이 칠한 컬럼들의 역인덱스. 병합/활성색 변경 시 영향받는 컬럼만
     *  dirtyColumns에 추가하기 위해 사용. paintCell에서 채워지고, 병합 시 생존 루트로 이전된다. */
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
    static final double baseDist = 75;
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

    // 텍스처 부분 업로드는 "단일 바운딩 박스"가 아니라 "타일 단위 dirty 집합"으로 관리한다.
    // dirty 픽셀이 멀리 떨어진 두 덩어리면 하나의 사각형은 그 사이 전체(사실상 풀업로드)를
    // 덮어버리므로, TILE_SIZE 단위로 쪼개 실제로 바뀐 타일만 업로드한다.
    // (rebuildTexture/clearAllMap의 전체 클리어 시에는 markAllDirty로 모든 타일을 올린다.)
    private static final int TILE_SIZE = 32; // 512 / 32 = 16×16 = 256개 타일
    private static final int TILES_PER_ROW = TEX_SIZE / TILE_SIZE;
    private static final IntOpenHashSet dirtyTiles = new IntOpenHashSet();

    private static int tileKey(int tileX, int tileZ) {
        return tileX * TILES_PER_ROW + tileZ;
    }

    private static void markPixelDirty(int tx, int tz) {
        if (tx < 0 || tx >= TEX_SIZE || tz < 0 || tz >= TEX_SIZE) return;
        dirtyTiles.add(tileKey(tx / TILE_SIZE, tz / TILE_SIZE));
        textureDirty = true;
    }

    private static void markAllDirty() {
        dirtyTiles.clear();
        for (int tx = 0; tx < TILES_PER_ROW; tx++) {
            for (int tz = 0; tz < TILES_PER_ROW; tz++) {
                dirtyTiles.add(tileKey(tx, tz));
            }
        }
        textureDirty = true;
    }

    private static void resetDirtyRect() {
        dirtyTiles.clear();
    }

    /** dirty 타일들만 각각 NativeImage → GPU 텍스처로 복사(1.21.5 blaze3d GPU 경로). */
    private static void uploadDirtyRegion() {
        if (image == null || texture == null || dirtyTiles.isEmpty()) { dirtyTiles.clear(); return; }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        it.unimi.dsi.fastutil.ints.IntIterator it = dirtyTiles.iterator();
        while (it.hasNext()) {
            int key = it.nextInt();
            int tileX = key / TILES_PER_ROW;
            int tileZ = key % TILES_PER_ROW;
            int minX = tileX * TILE_SIZE;
            int minY = tileZ * TILE_SIZE;
            int w = Math.min(TILE_SIZE, TEX_SIZE - minX);
            int h = Math.min(TILE_SIZE, TEX_SIZE - minY);
            encoder.writeToTexture(texture.getGlTexture(), image, 0, minX, minY, w, h, minX, minY);
        }
        resetDirtyRect();
    }

    // ── 예산 ─────────────────────────────────────────────────────────────
    static final int STAGING_BUDGET_PER_TICK = 512;
    /** dirtyColumns 재도색(plotColumn) 시간 예산. 컬럼 하나 그리는 비용은 아주 저렴하므로
     *  "개수"가 아니라 "이번 틱에 실제로 쓴 시간"으로 제한한다. 그릴 게 적으면(평소 착지)
     *  이 시간 안에 다 끝나 사실상 즉시 반영되고("먼지처럼 서서히" 보이는 연출이 사라짐),
     *  대용량 트랙이 activeColor 전환 등으로 한꺼번에 dirty해지는 극단적인 경우에만 시간이
     *  다 차서 여러 틱에 나눠 그려진다(프레임 스파이크 방지는 그대로 유지). */
    static final long REPAINT_TIME_BUDGET_NANOS = 2_000_000L; // 2ms (50ms 틱 예산의 4%)
    /** 시간 예산과 별개로 두는 개수 상한(안전장치). 만약 컬럼 하나당 비용이 예상보다 훨씬
     *  커지는 이상 상황(예: GC 일시정지 직후)이 있어도, 단일 틱이 이 개수를 넘겨 무한정
     *  오래 걸리는 최악의 경우를 막는다. 평소엔 시간 예산이 먼저 걸리므로 거의 도달하지 않는다. */
    static final int REPAINT_HARD_CAP_PER_TICK = 200_000;
    /** activeColor 전환 히스테리시스: 같은 후보 색이 이 값만큼 연속으로 나와야 실제 전환한다.
     *  벽 경계처럼 anchor 판정이 매 틱 미세하게 흔들리는 위치에서 activeColor(및 그에 따른
     *  rebuildActiveSet의 대규모 diff)가 잡음성으로 반복 전환되는 것을 막는다. */
    static final int ACTIVE_COLOR_SWITCH_STREAK = 3;
    static final long STAGING_TIME_BUDGET_NANOS = 500_000L; // 0.5ms
    /** 착지/텔레포트 직후처럼 "화면에 보이는 범위" 안에 아직 못 훑은 프론티어가 남아있을 때만
     *  일시적으로 쓰는 확장 예산. 뷰 반경(REANCHOR_MARGIN과 동일 범위) 안이 다 채워지는 순간
     *  다음 틱부터 바로 STAGING_* (보수적 기본값)으로 되돌아간다. "항상 크게"가 아니라
     *  "필요한 순간에만 크게"이므로, 평소 주행 중 새 트랙 탐색으로 인한 스터터는 그대로
     *  방지되면서 착지 직후 화면이 비어 보이는 시간만 줄어든다.
     *  (REPAINT 쪽 프레임드랍은 이미 그려진 셀을 다시 칠하는 문제였고, 이건 아직 탐색조차
     *  안 된 새 땅을 찾는 별개의 예산이라 서로 간섭하지 않는다.) */
    static final int URGENT_SEARCH_RANGE = REANCHOR_MARGIN;
    static final int URGENT_SEARCH_BUDGET_PER_TICK = 6000;
    static final long URGENT_SEARCH_TIME_BUDGET_NANOS = 3_000_000L; // 3ms (그래도 50ms 틱 예산의 6%)
    /** 진단 로그용: 이번 틱 탐색이 urgent 모드였는지 기록(onTickStart 로그가 참조). */
    static boolean lastSearchWasUrgent = false;
    /** 플레이어 앵커 위치를 찾기 위해 아래로 뚫고 내려갈 수 있는 최대 칸 수.
     *  트랙 표면이 ignoreblock 태그로 "공기"처럼 보일 때 그 아래로 무한정 뚫고
     *  내려가 전혀 다른 영역에 도달하지 않도록 막는 안전장치. */
    static final int MAX_ANCHOR_DROP_SEARCH = 64;

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
        markAllDirty(); // fillRect로 전체를 0으로 지웠으므로 이번엔 전체를 올려야 한다.
        dirtyColumns.clear();
    }

    private static void plotColumn(int worldX, int worldZ) {
        if (image == null || !originSet) return;
        int tx = worldX - originX;
        int tz = worldZ - originZ;
        if (tx < 0 || tx >= TEX_SIZE || tz < 0 || tz >= TEX_SIZE) return;
        image.setColorArgb(tx, tz, computeColumnColor(worldX, worldZ));
        markPixelDirty(tx, tz);
    }

    /** (worldX,worldZ)가 지금 화면에 실제로 보이는 원형 시야 범위 안인지 판정한다.
     *  미니맵은 플레이어를 중심으로 회전하므로 방향 무관하게 반경만 본다.
     *  이 범위는 maxDist로 물리적 크기가 고정돼 있어(회전 대각선 포함 여유 SQRT2배),
     *  아무리 dirtyColumns가 커져도 이 범위 안 컬럼 수는 스파이크를 낼 만큼 커지지 않는다. */
    private static boolean isInCurrentView(int worldX, int worldZ, int px, int pz) {
        double dx = worldX - px;
        double dz = worldZ - pz;
        double r = maxDist * SQRT2 + 8;
        return dx * dx + dz * dz <= r * r;
    }

    /** "활성 색 + 그 자손"의 resolve된 집합(비디버그 모드용). 틱당 1회 계산해 캐시. */
    private static final LongOpenHashSet activeSet = new LongOpenHashSet();

    /** color graph 구조가 바뀔 때만(엣지 추가, 병합) 증가하는 버전 번호. activeColor와 이 버전이
     *  지난 rebuildActiveSet 호출 때와 같으면 BFS를 다시 돌 필요가 없다는 뜻이다. */
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

    /** rebuildActiveSet 재계산 시 "직전 activeSet"을 담아두는 diff용 버퍼. */
    private static final LongOpenHashSet prevActiveSetForDiff = new LongOpenHashSet();

    /** "탐색 허용 범위" 전용 activeSet — 표시용 activeSet(히스테리시스가 걸린 activeColor 기반)과
     *  분리한다. 표시는 깜빡임 방지를 위해 같은 후보가 3틱 연속 나와야 activeColor를 전환하지만,
     *  탐색의 containToActive 필터까지 그 표시용 activeColor를 기준으로 쓰면 교착 상태가 생긴다:
     *  빠르게 이동하며 새 트랙에 올라탈 때, 매 틱 다른 위치라 새 색이 계속 시드되는데 그 새 색이
     *  아직 옛 activeColor의 activeSet에 없어 즉시 exile되고 → 프론티어가 안 이어지니 같은 후보가
     *  두 틱 연속 나오지 않고 → 히스테리시스가 영원히 안 채워져 activeColor도 안 바뀌는 순환.
     *  그래서 탐색 쪽은 "지금 이 틱에 실제로 서 있는 셀의 색"을 히스테리시스 없이 즉시 기준으로 삼는
     *  별도 집합을 쓴다. 표시(activeSet/activeColor)는 기존처럼 히스테리시스를 유지해 깜빡이지 않는다. */
    private static final LongOpenHashSet searchActiveSet = new LongOpenHashSet();
    private static long searchActiveSetSnapshotRoot = NO_ID;
    private static long searchActiveSetVersion = -1;

    private static void rebuildSearchActiveSet(long liveRoot) {
        if (searchActiveSetSnapshotRoot == liveRoot && searchActiveSetVersion == colorGraphVersion) {
            return;
        }
        searchActiveSet.clear();
        if (liveRoot != NO_ID) {
            LongArrayFIFOQueue q = new LongArrayFIFOQueue();
            searchActiveSet.add(liveRoot);
            q.enqueue(liveRoot);
            while (!q.isEmpty()) {
                long cur = q.dequeueLong();
                LongOpenHashSet kids = parentToChildren.get(cur);
                if (kids == null) continue;
                LongIterator it = kids.iterator();
                while (it.hasNext()) {
                    long kid = resolve(it.nextLong());
                    if (searchActiveSet.add(kid)) q.enqueue(kid);
                }
            }
        }
        searchActiveSetSnapshotRoot = liveRoot;
        searchActiveSetVersion = colorGraphVersion;
    }

    private static void rebuildActiveSet() {
        if (activeSetSnapshotColor == activeColor && activeSetVersion == colorGraphVersion) {
            return; // 캐시 유효: activeColor도 그래프도 지난 계산 이후 바뀌지 않았다.
        }
        // 재계산 전 이전 activeSet을 보존해, 이후 새 집합과 비교(diff)해서 실제로 활성에
        // 들어오거나 빠진 루트의 컬럼만 dirty로 표시한다(전체 재도색 방지).
        // DEBUG_COLORS 모드는 activeSet과 무관하게 항상 표시하므로 diff가 필요 없다.
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
            // 활성에서 이탈한 루트와 새로 진입한 루트, 각각의 컬럼만 dirty로 표시한다.
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

    /** (x,y,z)의 cellColor를 조회해 resolve까지 마친 root를 돌려준다. 매핑이 없으면 NO_ID. */
    private static long resolvedRootAt(int x, int y, int z) {
        long id = cellColor.get(BlockPos.asLong(x, y, z));
        return id == NO_ID ? NO_ID : resolve(id);
    }

    private static int computeColumnColor(int x, int z) {
        IntOpenHashSet ys = visitedColumns.get(packColumn(x, z));
        if (ys == null || ys.isEmpty()) return 0;

        it.unimi.dsi.fastutil.ints.IntIterator yi = ys.iterator();
        if (DEBUG_COLORS) {
            int repY = Integer.MIN_VALUE;
            long repRoot = NO_ID;
            while (yi.hasNext()) {
                int y = yi.nextInt();
                long root = resolvedRootAt(x, y, z);
                if (root == NO_ID) continue;
                if (y >= repY) { repY = y; repRoot = root; }
            }
            return colorForRoot(repRoot);
        } else {
            if (activeColor == NO_ID) return 0;
            while (yi.hasNext()) {
                int y = yi.nextInt();
                long root = resolvedRootAt(x, y, z);
                if (root != NO_ID && activeSet.contains(root)) return VISITED_COLOR;
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
            uploadDirtyRegion();
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

    // 병합/사이클 재검사에서 쓰는 그래프 BFS 스크래치(GC 압박 방지용 재사용 필드).
    // 역할별로 하나씩만 두며, 각 함수는 호출 시작 시 자기 몫의 필드를 clear()하고 쓴다.
    // 서로 다른 역할은 다른 필드를 쓰고, 같은 필드는 앞선 사용이 끝난 뒤에만 재사용된다.
    private static final LongOpenHashSet scratchGroup = new LongOpenHashSet();
    private static final LongOpenHashSet scratchReachable = new LongOpenHashSet();
    private static final LongArrayFIFOQueue scratchReachQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet scratchAncestorsOfTo = new LongOpenHashSet();
    private static final LongOpenHashSet scratchSeen = new LongOpenHashSet();
    private static final LongArrayFIFOQueue scratchSeenQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet scratchDescendants = new LongOpenHashSet();
    private static final LongOpenHashSet scratchAncestors = new LongOpenHashSet();
    /** handleReach의 단방향-재조우 분기(사이클 방지용 조상 집합 계산) 전용 스크래치. */
    private static final LongOpenHashSet scratchParentAncestors = new LongOpenHashSet();

    static void mergeColors(long aId, long bId) {
        long a = resolve(aId);
        long b = resolve(bId);
        if (a == b) return;

        LongOpenHashSet group = scratchGroup;
        group.clear();
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
                // c에 속한 컬럼을 dirty 표시 후 생존 루트(survivor) 버킷으로 이전한다.
                absorbInto(c, survivor);
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
     * 자식은 항상 부모보다 나중에 태어난다(birth 값이 큼)는 성질로 두 번 가지치기한다:
     *  1) birth(from) >= birth(to)면 from은 애초에 to의 조상일 수 없어 즉시 반환.
     *  2) 정방향 탐색 중 birth가 to보다 큰 노드는 가지치기(그 자손도 to에 닿을 수 없음).
     * 재귀 대신 큐 기반이라 긴 사슬에서도 스택 오버플로가 없다.
     */
    static void collectChainIfAncestor(long from, long to, LongOpenHashSet out) {
        from = resolve(from);
        to = resolve(to);
        if (from == to) return;

        int fromBirth = colorBirth.get(from);
        int toBirth = colorBirth.get(to);
        if (fromBirth >= toBirth) return; // 가지치기1: from이 to보다 늦게(또는 같이) 태어남 → 조상 불가

        // R1: from에서 자식 방향으로 도달 가능한 노드(단, to보다 늦게 태어난 가지는 잘라냄)
        LongOpenHashSet reachable = scratchReachable;
        reachable.clear();
        LongArrayFIFOQueue q = scratchReachQueue;
        q.clear();
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

        // R2: to의 조상 집합(보통 단일 계보라 훨씬 작음).
        LongOpenHashSet ancestorsOfTo = scratchAncestorsOfTo;
        ancestorsOfTo.clear();
        ancestorsOfTo.add(to);
        collectAncestors(to, ancestorsOfTo);

        // 교집합 = from→to 경로 위의 색들.
        LongIterator it = reachable.iterator();
        while (it.hasNext()) {
            long n = it.nextLong();
            if (ancestorsOfTo.contains(n)) out.add(n);
        }
    }

    /** loser에 속한 컬럼들을 survivor로 이전(dirty 표시 포함)하고, loser가 그래프상 자신의
     *  키로 갖던 부모/자식 간선을 survivor로 옮긴 뒤, loser의 부모 포인터를 survivor로
     *  재지정한다. activeColor 갱신은 호출부(mergeColors/rescanCycles)마다 조건이 달라 남겨둔다. */
    static void absorbInto(long loser, long survivor) {
        markColumnsDirtyForRoot(loser);
        LongOpenHashSet cols = columnsByRoot.remove(loser);
        if (cols != null) {
            columnsByRoot.computeIfAbsent(survivor, k -> new LongOpenHashSet()).addAll(cols);
        }
        // parentToChildren/childToParents가 loser 자신을 키로 갖는 버킷은 resolve()로 자동
        // 정규화되지 않으므로, colorParentPtr만 바꾸면 그 간선이 유실된다. survivor 키로 옮긴다.
        migrateAdjacency(parentToChildren, loser, survivor);
        migrateAdjacency(childToParents, loser, survivor);
        colorParentPtr.put(loser, survivor);
    }

    /** adj[loser]의 이웃 전부를 adj[survivor]로 옮기고(합집합) loser 항목은 제거한다.
     *  self-loop는 걸러낸다. absorbInto가 parentToChildren/childToParents 양쪽에 쓰는 공용 헬퍼. */
    private static void migrateAdjacency(Long2ObjectOpenHashMap<LongOpenHashSet> adj, long loser, long survivor) {
        LongOpenHashSet moved = adj.remove(loser);
        if (moved == null || moved.isEmpty()) return;
        LongOpenHashSet target = adj.computeIfAbsent(survivor, k -> new LongOpenHashSet());
        LongIterator it = moved.iterator();
        while (it.hasNext()) {
            long v = it.nextLong();
            if (v != survivor) target.add(v);
        }
    }

    static void rescanCycles(long survivor) {
        survivor = resolve(survivor);
        LongOpenHashSet descendants = scratchDescendants;
        descendants.clear();
        collectDescendants(survivor, descendants);
        LongOpenHashSet ancestors = scratchAncestors;
        ancestors.clear();
        collectAncestors(survivor, ancestors);

        boolean merged = false;
        LongIterator it = descendants.iterator();
        while (it.hasNext()) {
            long d = it.nextLong();
            if (d != survivor && ancestors.contains(d)) {
                absorbInto(d, survivor);
                if (activeColor == d) activeColor = survivor;
                merged = true;
            }
        }
        if (merged) {
            bumpColorGraphVersion();
            rescanCycles(survivor);
        }
    }

    /** start에서 adj(부모→자식 또는 자식→부모) 방향으로 도달 가능한 (resolve된) 노드를 out에
     *  모은다. start 자신은 out에 넣지 않는다. collectDescendants/collectAncestors가 인접 맵만
     *  달리해 공유하는 공용 BFS. */
    static void collectReachable(long start, Long2ObjectOpenHashMap<LongOpenHashSet> adj, LongOpenHashSet out) {
        start = resolve(start);
        LongArrayFIFOQueue q = scratchSeenQueue;
        q.clear();
        LongOpenHashSet seen = scratchSeen;
        seen.clear();
        q.enqueue(start); seen.add(start);
        while (!q.isEmpty()) {
            long cur = q.dequeueLong();
            LongOpenHashSet nbrs = adj.get(cur);
            if (nbrs == null) continue;
            LongIterator it = nbrs.iterator();
            while (it.hasNext()) {
                long nb = resolve(it.nextLong());
                if (seen.add(nb)) { out.add(nb); q.enqueue(nb); }
            }
        }
    }

    static void collectDescendants(long start, LongOpenHashSet out) {
        collectReachable(start, parentToChildren, out);
    }

    static void collectAncestors(long start, LongOpenHashSet out) {
        collectReachable(start, childToParents, out);
    }

    // ═════════════════════════════ 탐색 ═════════════════════════════

    static final TagKey<Block> KART_WALL = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "stones"));
    static final Predicate<BlockState> isWall = state -> state.isIn(KART_WALL);
    static final TagKey<Block> KART_AIR = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "ignoreblock"));
    static final Predicate<BlockState> isAir = state -> state.isIn(KART_AIR);

    /** 4방향 이웃 오프셋. 내용이 불변이므로 매 틱 재할당하지 않고 상수로 공유한다. */
    private static final int[][] DIRS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };

    // 4슬롯을 "0번 = 최근 사용"이 되도록 move-to-front로 유지하는 LRU 청크 캐시.
    private static final int CHUNK_CACHE_SLOTS = 4;
    private static final long[] cacheKeys = new long[CHUNK_CACHE_SLOTS];
    private static final Chunk[] cacheChunks = new Chunk[CHUNK_CACHE_SLOTS];
    private static final BlockPos.Mutable MUTABLE = new BlockPos.Mutable();

    // 프론티어 청크를 거리순 정렬할 때 쓰는 재사용 스크래치. (거리<<32 | 인덱스)로 패킹해
    // Arrays.sort로 O(n log n) 정렬하며, 인덱스를 하위 비트에 실어 동일 거리 청크는 스냅샷
    // 순서(stable)를 유지한다.
    private static long[] frontierSortSnap = new long[0];
    private static long[] frontierSortPacked = new long[0];
    /** exile 부활 셀을 임시로 모으는 재사용 리스트(매 틱 new 방지). floodFill은 재진입하지 않는다. */
    private static final LongArrayList revivedScratch = new LongArrayList();

    private static void invalidateChunkCache() {
        java.util.Arrays.fill(cacheKeys, Long.MIN_VALUE);
        java.util.Arrays.fill(cacheChunks, null);
    }

    private static BlockState stateAt(int x, int y, int z) {
        long key = ChunkPos.toLong(x >> 4, z >> 4);
        for (int i = 0; i < CHUNK_CACHE_SLOTS; i++) {
            if (cacheKeys[i] == key && cacheChunks[i] != null) {
                if (i != 0) {
                    // 히트한 슬롯을 맨 앞(최근 사용)으로 옮긴다.
                    long hitKey = cacheKeys[i];
                    Chunk hitChunk = cacheChunks[i];
                    System.arraycopy(cacheKeys, 0, cacheKeys, 1, i);
                    System.arraycopy(cacheChunks, 0, cacheChunks, 1, i);
                    cacheKeys[0] = hitKey;
                    cacheChunks[0] = hitChunk;
                }
                return cacheChunks[0].getBlockState(MUTABLE.set(x, y, z));
            }
        }
        Chunk chunk = client.world.getChunk(x >> 4, z >> 4);
        // miss: 가장 오래 안 쓰인 마지막 슬롯을 밀어내고 새 청크를 맨 앞에 넣는다.
        System.arraycopy(cacheKeys, 0, cacheKeys, 1, CHUNK_CACHE_SLOTS - 1);
        System.arraycopy(cacheChunks, 0, cacheChunks, 1, CHUNK_CACHE_SLOTS - 1);
        cacheKeys[0] = key;
        cacheChunks[0] = chunk;
        return chunk.getBlockState(MUTABLE.set(x, y, z));
    }

    /** world==null 가드 + 좌표의 BlockState에 predicate 적용을 한데 묶은 헬퍼. */
    private static boolean testAt(Predicate<BlockState> predicate, int x, int y, int z) {
        if (client.world == null) return false;
        return predicate.test(stateAt(x, y, z));
    }

    private static final Predicate<BlockState> isVoid = state -> state.isOf(Blocks.STRUCTURE_VOID);

    static boolean isAirAt(int x, int y, int z) {
        return testAt(isAir, x, y, z);
    }
    static boolean isWallAt(int x, int y, int z) {
        return testAt(isWall, x, y, z);
    }
    static boolean isVoidAt(int x, int y, int z) {
        return testAt(isVoid, x, y, z);
    }
    static boolean isVoidFloorUnder(int x, int y, int z) {
        return isVoidAt(x, y - 1, z);
    }
    static boolean isChunkLoadedAt(int x, int z) {
        if (client.world == null) return false;
        return client.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }

    /** 서 있을 수 있는 칸: 머리(y+1) 공간 + void 바닥 아님 + 발밑 지지(y,y-1 둘 다 공기가 아니어야).
     *  headAlreadyChecked=true면 머리 공간 검사를 건너뛴다(호출자가 이미 같은 좌표를 확인한 경우.
     *  평지/계단은 resolveTargetY가 이미 확인했으므로 생략, 낙하는 착지 높이를 아직 안 봤으므로
     *  반드시 false로 호출). */
    static boolean isStandable(int x, int y, int z, boolean headAlreadyChecked) {
        if (!headAlreadyChecked && !isAirAt(x, y + 1, z)) return false;
        if (isVoidFloorUnder(x, y, z)) return false;
        if (isAirAt(x, y, z) && isAirAt(x, y - 1, z)) return false;
        return true;
    }

    /**
     * (x, y, z)에서 시작해 실제 기준 지점(플레이어가 서 있다고 볼 수 있는 칸)을 찾는다.
     * floodFillWithVertical의 시드 위치 계산과 resolvePlayerCell(활성 영역 판정)이 공유하는 헬퍼.
     *
     * 내려가는 도중 이미 매핑된 칸을 만나면 그게 트랙 표면이므로 거기서 멈춘다(표면 블록이
     * ignoreblock 태그라 공기처럼 보이는 경우 대비). 그런 칸이 없으면 단단한 블록을 만날 때까지
     * 내려가되 MAX_ANCHOR_DROP_SEARCH만큼만 허용한다(무한정 하강 방지).
     *
     * 반환값: 기준 지점의 BlockPos.asLong. 바닥까지 다 공기라 못 찾으면 NO_ID.
     */
    static long findAnchorCell(int x, int y, int z, int bottomY) {
        if (isAirAt(x, y, z)) {
            int startY = y;
            while (y > bottomY) {
                if (cellColor.get(BlockPos.asLong(x, y, z)) != NO_ID) break;
                if (!isAirAt(x, y - 1, z)) break;
                if (startY - y >= MAX_ANCHOR_DROP_SEARCH) break;
                y--;
            }
        }
        if (y <= bottomY) return NO_ID;
        return BlockPos.asLong(x, y, z);
    }

    void onTickStart() {
        if (!MCRiderConfig.INSTANCE.useMinimap) return;
        if (client.player == null || client.world == null) return;

        final int playerMargin = 5;
        BlockPos start = client.player.getBlockPos().up();
        lastPlayerPos = start;

        floodFillWithVertical(start, (int) ((maxDist + playerMargin * 2) * 2), STAGING_BUDGET_PER_TICK);

        // 텍스처를 (재)생성하기 전에 activeColor/activeSet을 먼저 확정한다.
        // rebuildTexture(plotColumn→computeColumnColor)가 activeSet을 읽어 색을 결정하므로,
        // 순서가 바뀌면 새로 생긴 컬럼이 잘못(투명하게) 그려질 수 있다.
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

        // "전체 다시 그리기" 없이, mergeColors/rescanCycles/handleReach/rebuildActiveSet이
        // 각자 넣어둔 dirtyColumns만 다시 그린다. 비용은 실제 변경분에만 비례한다.
        // 시간 예산(REPAINT_TIME_BUDGET_NANOS)으로 한 틱 처리 시간을 제한하며, 못 처리한
        // 항목은 Set에 그대로 남아 다음 틱에 이어서 처리된다(유실 없음).
        //
        // 주의: "화면에 보이는 범위는 무조건 다 그린다"처럼 예산 자체를 없애면 안 된다.
        // 바닥이 전부 탐색된 곳에서 높은 곳으로 올라가 activeColor가 전환되는 경우,
        // isInCurrentView는 Y(높이)를 보지 않고 X/Z 거리만 보므로 뷰 안에 들어오는
        // dirty 컬럼이 수만 개에 달할 수 있다 — 이 경우 예산을 없애면 예산 도입 이전과
        // 똑같은 프레임드랍이 재발한다(실제로 재발했던 문제).
        // 따라서 시간 예산은 그대로 유지하되, 그 예산을 "뷰 안" 컬럼에 먼저 쓰고 남으면
        // "뷰 밖"에 쓰는 순서만 바꾼다. 뷰 안 dirty가 시간 안에 다 그려질 정도로 적을
        // 때만(=평소 착지 시나리오) 즉시 다 그려지고, 뷰 안 dirty 자체가 시간을 넘어설
        // 만큼 큰 경우(=프레임드랍 재발 시나리오)는 예전처럼 여러 틱에 걸쳐 나눠 그려진다.
        // 컬럼 하나 그리는 비용은 원래 저렴하므로, 대부분의 경우 시간 예산 안에서
        // 훨씬 더 많은 개수가 처리돼 "먼지처럼 서서히" 보이는 연출 없이 사실상 즉시
        // 다 그려진다(개수 상한은 극단적 이상 상황을 막는 안전장치일 뿐).
        if (!dirtyColumns.isEmpty() && originSet) {
            int px = start.getX(), pz = start.getZ();
            final long repaintDeadline = System.nanoTime() + REPAINT_TIME_BUDGET_NANOS;
            int hardCap = REPAINT_HARD_CAP_PER_TICK;
            int sinceTimeCheck = 0;

            LongIterator dirtyIt = dirtyColumns.iterator();
            boolean timedOut = false;
            while (dirtyIt.hasNext() && hardCap > 0 && !timedOut) {
                long key = dirtyIt.nextLong();
                int wx = unpackColumnX(key), wz = unpackColumnZ(key);
                if (isInCurrentView(wx, wz, px, pz)) {
                    plotColumn(wx, wz);
                    dirtyIt.remove();
                    hardCap--;
                }
                // nanoTime() 자체도 공짜가 아니므로, 대용량 백로그를 스캔만 할 때(뷰 밖 항목이
                // 대부분일 때) 매번 부르면 그 호출 비용만으로 예산을 넘길 수 있다. 256개마다만 확인.
                if ((++sinceTimeCheck & 0xFF) == 0 && System.nanoTime() >= repaintDeadline) {
                    timedOut = true;
                }
            }

            if (!timedOut) {
                dirtyIt = dirtyColumns.iterator();
                while (dirtyIt.hasNext() && hardCap > 0) {
                    long key = dirtyIt.nextLong();
                    plotColumn(unpackColumnX(key), unpackColumnZ(key));
                    dirtyIt.remove();
                    hardCap--;
                    if ((++sinceTimeCheck & 0xFF) == 0 && System.nanoTime() >= repaintDeadline) break;
                }
            }
        }
    }

    static void updateActiveColor(BlockPos start) {
        updateActiveColorFromCell(resolvePlayerCell(start));
    }

    // activeColor 전환 히스테리시스 상태(ACTIVE_COLOR_SWITCH_STREAK 설명 참고).
    private static long pendingActiveColorCandidate = NO_ID;
    private static int pendingActiveColorStreak = 0;

    /** 이미 구해둔 플레이어 앵커 셀로 activeColor를 갱신한다(resolvePlayerCell 중복 호출 회피용). */
    static void updateActiveColorFromCell(long cell) {
        if (cell == NO_ID) return;
        long id = cellColor.get(cell);
        if (id == NO_ID) return;
        long candidate = resolve(id);

        if (candidate == activeColor) {
            // 이미 활성인 색과 동일 — 전환 대기 상태 리셋.
            pendingActiveColorCandidate = NO_ID;
            pendingActiveColorStreak = 0;
            return;
        }

        if (candidate == pendingActiveColorCandidate) {
            pendingActiveColorStreak++;
        } else {
            pendingActiveColorCandidate = candidate;
            pendingActiveColorStreak = 1;
        }

        if (pendingActiveColorStreak >= ACTIVE_COLOR_SWITCH_STREAK) {
            activeColor = candidate;
            pendingActiveColorCandidate = NO_ID;
            pendingActiveColorStreak = 0;
        }
    }

    static long resolvePlayerCell(BlockPos start) {
        // findAnchorCell로 실제로 아래로 내려가며 확인하므로, 플레이어가 트랙 위로 얼마나
        // 높이 떠 있든 그 아래 트랙을 정확히 찾는다.
        var world = client.world;
        if (world == null) return NO_ID;
        if (!isChunkLoadedAt(start.getX(), start.getZ())) return NO_ID;
        long anchor = findAnchorCell(start.getX(), start.getY(), start.getZ(), world.getBottomY());
        if (anchor == NO_ID) return NO_ID;
        if (cellColor.get(anchor) == NO_ID) return NO_ID; // 아직 매핑 안 된 곳
        return anchor;
    }

    /**
     * 프론티어 셀을 분류한다. 색이나 플레이어와의 거리와 무관하게, 오직 실제 환경적 제약
     * (탐색 범위 안인가)만으로 판단한다. exile은 "지금 당장은 범위 밖"인 셀을 위한 것이며,
     * 매 틱 도는 exile 복귀 루프가 범위 안으로 들어오면 자동으로 frontierByChunk로 되돌린다.
     * 색은 연결 관계를 나타내는 라벨일 뿐, 탐색 진행 여부를 결정하는 조건이 아니다.
     */
    /** map[key]의 LongArrayList 버킷을 가져오거나 없으면 새로 만들어 등록한 뒤 반환한다. */
    private static LongArrayList getOrCreateBucket(Long2ObjectOpenHashMap<LongArrayList> map, long key, int initialCapacity) {
        LongArrayList bucket = map.get(key);
        if (bucket == null) {
            bucket = new LongArrayList(initialCapacity);
            map.put(key, bucket);
        }
        return bucket;
    }

    /** 셀을 활성 프론티어의 청크 버킷에 추가한다. 그 청크가 처음 생기는 것이면
     *  frontierChunkKeys에도 등록해 다음 정렬 스냅샷에 포함되게 한다. */
    static void frontierPush(long cell, int cx, int cz) {
        long chunkKey = ChunkPos.toLong(cx >> 4, cz >> 4);
        getOrCreateBucket(frontierByChunk, chunkKey, 8).add(cell);
        frontierChunkKeys.add(chunkKey); // Set이라 이미 있으면 no-op
    }

    static void enqueueFrontier(long cell, int cx, int cz, int sx, int sz, int maxRange) {
        if (taxiDistance2D(cx, cz, sx, sz) <= maxRange) {
            frontierPush(cell, cx, cz);
        } else {
            parkInExiledFrontier(cell, cx >> 4, cz >> 4);
        }
    }

    /** 뷰 반경(range) 안에 아직 처리 못 한 프론티어 청크가 남아있는지 청크 단위로 싸게 확인한다.
     *  셀 개수가 아니라 "대기 중인 청크 개수"만 훑으므로(보통 수십~수백 개) 매 틱 호출해도
     *  가볍다. true면 이번 틱은 URGENT_SEARCH_* 예산을 쓴다. */
    private static boolean hasPendingFrontierWithin(int range, int sx, int sz) {
        LongIterator it = frontierChunkKeys.iterator();
        while (it.hasNext()) {
            long k = it.nextLong();
            if (taxiDistanceFromChunkToPos(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k), sx, sz) <= range) {
                return true;
            }
        }
        ObjectIterator<Long2ObjectMap.Entry<LongArrayList>> eIt = exiledFrontierChunkHash.long2ObjectEntrySet().iterator();
        while (eIt.hasNext()) {
            long k = eIt.next().getLongKey();
            if (taxiDistanceFromChunkToPos(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k), sx, sz) <= range) {
                return true;
            }
        }
        return false;
    }

    static boolean floodFillWithVertical(BlockPos start, int maxRange, int updatePixel) {
        needsMoreSearching = false;
        var world = client.world;
        if (world == null) return false;

        // 청크 캐시는 틱 사이에 유지한다. stateAt()을 부르는 모든 좌표는 사전에
        // isChunkLoadedAt()으로 로딩 확인을 거치므로 언로드된 청크를 읽을 위험은 없다.

        if (!isChunkLoadedAt(start.getX(), start.getZ())) {
            needsMoreSearching = true;
            return false;
        }

        int sx = start.getX(), sz = start.getZ();
        long anchorCell = findAnchorCell(sx, start.getY(), sz, world.getBottomY());
        if (anchorCell == NO_ID) return true; // 바닥(월드 최저)까지 다 공기 등 — 이번 틱은 스킵
        int sy = BlockPos.unpackLongY(anchorCell);

        // 규칙1: 발밑 셀이 미방문이면 새 루트 색 시드.
        long startCell = BlockPos.asLong(sx, sy, sz);
        if (cellColor.get(startCell) == NO_ID && isStandable(sx, sy, sz, false)) {
            long c = newColor(NO_ID);
            paintCell(sx, sy, sz, c);
            frontierPush(startCell, sx, sz);
        }

        // 오픈 필드 블리딩 억제: 프론티어 확장을 "플레이어가 지금 서 있는 영역 + 그 자손"으로만
        // 제한한다. 서킷 사이 공터를 밟지 않으면 탐색되지 않고, 잠깐 밟아 시드됐더라도 플레이어가
        // 서킷으로 넘어가면 그 영역은 비활성이 되어 확장이 멈춘다(무한 블리딩 차단).
        // debug 모드에서도 탐색 필터는 동일하게 적용한다(표시 방식만 다름).
        // 플레이어 영역을 특정 못 하면(resolvePlayerCell 실패) 필터를 꺼서 안전 우선으로 무제한
        // 확장한다. resolvePlayerCell은 여기서 한 번만 호출해 재사용한다(updateActiveColor와
        // 결과가 같으므로 하강 스캔 중복을 피함).
        long playerCell = resolvePlayerCell(start);
        updateActiveColorFromCell(playerCell); // 표시용(히스테리시스 유지) — activeColor 갱신
        rebuildActiveSet();                     // 표시용 activeSet

        // 탐색용: 히스테리시스 없이 "지금 이 틱에 실제로 서 있는 셀의 색"을 즉시 기준으로 삼는다.
        long liveRoot = (playerCell != NO_ID) ? resolve(cellColor.get(playerCell)) : NO_ID;
        rebuildSearchActiveSet(liveRoot);
        final boolean containToActive = playerCell != NO_ID;

        // deadline은 exile 부활 단계와 메인 탐색 루프가 같은 예산을 공유하도록 앞에서 선언한다.
        // 뷰 반경 안에 아직 처리 못 한 프론티어가 남아있으면(=착지 직후) 이번 틱만 예산을 키운다.
        boolean urgent = hasPendingFrontierWithin(URGENT_SEARCH_RANGE, sx, sz);
        lastSearchWasUrgent = urgent;
        final long deadline = System.nanoTime() + (urgent ? URGENT_SEARCH_TIME_BUDGET_NANOS : STAGING_TIME_BUDGET_NANOS);
        if (urgent && updatePixel < URGENT_SEARCH_BUDGET_PER_TICK) {
            updatePixel = URGENT_SEARCH_BUDGET_PER_TICK;
        }

        // 보류 프론티어 복귀(청크당 목록 전체).
        // exiledFrontierChunkHash를 순회하며 그 맵에 직접 쓰면(enqueueFrontier→parkInExiledFrontier)
        // 항목이 조용히 누락될 수 있으므로, 조건에 맞는 셀은 임시 리스트에 모았다가 순회가 끝난
        // 뒤 안전하게 재분류(enqueueFrontier)한다.
        LongArrayList revivedCells = revivedScratch;
        revivedCells.clear();
        // exile 맵이 아주 커진 상태(텔레포트 등)에서 이 스캔만으로 예산을 다 쓸 수 있으므로
        // deadline을 체크한다. 초과 시 순회를 멈추고, 아직 안 본 항목은 다음 틱에 재평가된다.
        boolean revivalTimedOut = false;
        ObjectIterator<Long2ObjectMap.Entry<LongArrayList>> exiledIt = exiledFrontierChunkHash.long2ObjectEntrySet().iterator();
        while (exiledIt.hasNext()) {
            if (System.nanoTime() >= deadline) {
                needsMoreSearching = true;
                revivalTimedOut = true;
                break;
            }
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
            int cellX = BlockPos.unpackLongX(cell);
            int cellZ = BlockPos.unpackLongZ(cell);
            // 이미 exile 맵에서 꺼내온 셀이므로, deadline 초과 시 그냥 버리면 유실된다.
            // 반드시 parkInExiledFrontier로 되돌려 넣어 다음 틱 재평가로 미룬다.
            if (!revivalTimedOut && System.nanoTime() >= deadline) revivalTimedOut = true;
            if (revivalTimedOut) {
                parkInExiledFrontier(cell, cellX >> 4, cellZ >> 4);
                needsMoreSearching = true;
                continue;
            }
            // 활성 트리 조건(메인 라운드 루프와 동일)을 미리 검사해, 안 맞는 셀은 프론티어
            // 등록→dequeue→재park의 왕복 없이 곧장 exile로 되돌린다.
            long curColor = cellColor.get(cell);
            if (curColor == NO_ID) continue;
            if (containToActive && !searchActiveSet.contains(resolve(curColor))) {
                parkInExiledFrontier(cell, cellX >> 4, cellZ >> 4);
                continue;
            }
            enqueueFrontier(cell, cellX, cellZ, sx, sz, maxRange);
        }

        // 프론티어를 청크 단위로 묶어 처리하되, 매 라운드마다 대기 중인 청크를 플레이어와의
        // 거리순으로 정렬해 가까운 청크부터 비운다. 라운드 도중 새로 생긴 청크는 다음 라운드의
        // 스냅샷에 자연스럽게 포함되므로, 시간/예산이 허용하는 한 유실 없이 계속 처리된다.
        boolean stop = false;
        while (!stop && !frontierChunkKeys.isEmpty()) {
            // 대기 중인 청크 키를 스냅샷 떠서 거리순 정렬한다.
            int n = frontierChunkKeys.size();
            if (frontierSortSnap.length < n) {
                frontierSortSnap = new long[n];
                frontierSortPacked = new long[n];
            }
            long[] snap = frontierSortSnap;
            long[] packed = frontierSortPacked;
            int idx = 0;
            LongIterator keyIt = frontierChunkKeys.iterator();
            while (keyIt.hasNext()) {
                long k = keyIt.nextLong();
                snap[idx] = k;
                int d = taxiDistanceFromChunkToPos(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k), sx, sz);
                packed[idx] = ((long) d << 32) | (idx & 0xFFFFFFFFL);
                idx++;
            }
            java.util.Arrays.sort(packed, 0, n);

            for (int ci = 0; ci < n; ci++) {
                long chunkKey = snap[(int) (packed[ci] & 0xFFFFFFFFL)];
                LongArrayList bucket = frontierByChunk.get(chunkKey);
                if (bucket == null) continue; // 이미 다른 경로로 비워졌을 수 있음(방어적)

                while (!bucket.isEmpty()) {
                    if (System.nanoTime() >= deadline) {
                        needsMoreSearching = true;
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

                    // 플레이어 영역(+자손)이 아니면 exile로 되돌린다. exile은 매 틱 복귀
                    // 루프가 자동 재평가하므로, 다시 활성이 되거나 병합되는 순간 확장이
                    // 재개된다. 비활성인 동안은 실제 확장(블록 조회·페인트) 없이 dequeue→
                    // 검사→재park만 반복해 CPU를 아낀다. debug 모드에서도 탐색 필터는 동일.
                    if (containToActive && !searchActiveSet.contains(resolve(curColor))) {
                        parkInExiledFrontier(curPacked, cx >> 4, cz >> 4);
                        continue;
                    }

                    boolean hasBlockAt2Meter = !isAirAt(cx, cy + 2, cz);

                    for (int[] d : DIRS) {
                        int nx = cx + d[0];
                        int nz = cz + d[1];

                        if (!isChunkLoadedAt(nx, nz)) {
                            // (nx,nz)는 아직 색이 없는 미확정 좌표라 그대로 park하면 exile 복귀 시
                            // curColor==NO_ID로 버려진다. 대신 색이 확정된 부모 셀(curPacked)을
                            // park해, 청크 로딩 시 4방향을 처음부터 재검사하게 한다.
                            parkInExiledFrontier(curPacked, cx >> 4, cz >> 4);
                            continue;
                        }

                        // (nx,cy,nz)의 air/wall을 한 번만 조회해 게이트와 resolveTargetY에 재사용한다.
                        boolean baseIsAir = isAirAt(nx, cy, nz);
                        boolean baseIsWall = !baseIsAir && isWallAt(nx, cy, nz);
                        if (baseIsWall) {
                            // 목적지가 공기가 아니고 그 몸체가 벽일 때만 차단("올라서는 것"만 막고,
                            // 벽 위에서 아래로 "내려가는 것"은 resolveTargetY에서 정상 처리된다).
                            continue;
                        }

                        int ty = resolveTargetY(nx, cy, nz, baseIsAir, baseIsWall, hasBlockAt2Meter, world.getBottomY());
                        if (ty == Integer.MIN_VALUE) continue;

                        boolean twoWay = canMoveBetween(nx, ty, nz, cx, cy, cz, world.getBottomY());
                        handleReach(cx, cy, cz, curColor, nx, ty, nz, twoWay, sx, sz, maxRange);
                    }

                    // 실제 확장 작업(4방향 검사)을 마친 셀만 예산을 소모한다. 재park된 값싼
                    // 셀은 예산을 먹지 않으므로, 비활성 영역의 churn이 활성 트랙 탐색을 굶기지 않는다.
                    if (--updatePixel <= 0) {
                        needsMoreSearching = true;
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

    /** (nx,cy,nz)의 air/wall 판정을 호출부에서 미리 구해 넘겨받아 중복 조회를 피한다
     *  (DIRS 루프 게이트와 canMoveBetween이 이미 같은 좌표를 조회해둔 상태). */
    static int resolveTargetY(int nx, int cy, int nz, boolean baseIsAir, boolean baseIsWall,
                              boolean fromHasBlockAt2Meter, int bottomY) {
        if (!isAirAt(nx, cy + 1, nz)) return Integer.MIN_VALUE;
        if (!baseIsAir) {
            // 기저가 벽 태그면 1칸이라도 절대 올라설 수 없다(단방향 예외 없음).
            if (baseIsWall) return Integer.MIN_VALUE;
            if (isAirAt(nx, cy + 2, nz) && !fromHasBlockAt2Meter) return cy + 1;
            return Integer.MIN_VALUE;
        } else if (isAirAt(nx, cy - 1, nz)) {
            // findAnchorCell과 동일하게 MAX_ANCHOR_DROP_SEARCH로 낙하 깊이를 제한한다.
            // (제한이 없으면 void 위 공중 트랙에서 프론티어 셀 하나가 bottomY까지 훑어
            // 틱 예산을 급격히 소모하는 스파이크가 생긴다.)
            int fy = cy;
            int dropped = 0;
            while (isAirAt(nx, fy - 1, nz) && fy > bottomY) {
                fy--;
                if (++dropped >= MAX_ANCHOR_DROP_SEARCH) return Integer.MIN_VALUE;
            }
            return fy;
        } else {
            return cy;
        }
    }

    /** 목적지에서 출발지로 되돌아가는 이동이 규칙상 성립하면 양방향. */
    static boolean canMoveBetween(int tx, int ty, int tz, int fx, int fy, int fz, int bottomY) {
        // (fx,ty,fz)의 wall/air를 한 번씩만 조회해 resolveTargetY에 재사용한다.
        boolean baseIsWall = isWallAt(fx, ty, fz); // 되돌아갈 칸 기저가 벽이면 역방향 불가
        if (baseIsWall) return false;
        boolean baseIsAir = isAirAt(fx, ty, fz);
        boolean tHasBlockAt2 = !isAirAt(tx, ty + 2, tz);
        int back = resolveTargetY(fx, ty, fz, baseIsAir, baseIsWall, tHasBlockAt2, bottomY);
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
                // 부모가 활성 집합에 있으면 방금 만든 자식도 즉시 activeSet에 넣는다.
                // 안 그러면 다음 프론티어 처리 때 곧바로 걸러져 확장이 멈춘다.
                if (activeSet.contains(resolve(curColor))) activeSet.add(resolve(color));
            }
            paintCell(tx, ty, tz, color);
            enqueueFrontier(targetCell, tx, tz, sx, sz, maxRange);
        } else {
            if (twoWay) {
                mergeColors(curColor, existing);
            } else {
                // 단방향으로 이미 다른 경로에서 칠해진 칸에 도달한 경우. 색은 합치지 않되
                // (대등하지 않음, 규칙3 유지) 부모→자식 엣지만 추가해 표시 판정(활성+자손)에
                // 반영한다. 이 관계를 안 남기면, 활성 트랙 아래로 이미 칠해진 저지대가
                // "자손"으로 인정되지 않아 화면에 표시되지 않는다.
                long parentRoot = resolve(curColor);
                long childRoot = resolve(existing);
                if (parentRoot != childRoot) {
                    // 사이클 방지: 자식이 보통 부모보다 나중에 태어나므로 birth 비교로 대부분
                    // 해결되고, 드문 경우(이미 반대 방향 조상-자손 관계 존재 가능성)에만 조상
                    // 집합을 실제 검사해 사이클(A→B가 있는데 B→A 추가)을 막는다.
                    boolean safeToAdd = colorBirth.get(childRoot) > colorBirth.get(parentRoot);
                    if (!safeToAdd) {
                        LongOpenHashSet parentAncestors = scratchParentAncestors;
                        parentAncestors.clear();
                        collectAncestors(parentRoot, parentAncestors);
                        safeToAdd = !parentAncestors.contains(childRoot);
                    }
                    if (safeToAdd) {
                        addEdge(parentRoot, childRoot);
                        if (activeSet.contains(parentRoot)) {
                            activeSet.add(childRoot);
                            // childRoot는 오래전 칠해져 dirtyColumns에서 빠졌으므로,
                            // 지금 활성 편입된 컬럼만 다시 표시 대상으로 표시한다.
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
        // 이 컬럼을 방금 칠한 색의 루트 아래 역인덱스에도 등록한다(나중에 병합되면 함께 이전됨).
        long root = resolve(color);
        columnsByRoot.computeIfAbsent(root, k -> new LongOpenHashSet()).add(colKey);
        if (DEBUG_COLORS) {
            plotColumn(x, z); // 즉시 그 컬럼만 도색
        } else {
            dirtyColumns.add(colKey); // 새로 칠해진 컬럼만 추가(기존 컬럼 재도색은 markColumnsDirtyForRoot가 담당)
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
    /** 청크가 차지하는 16×16 영역 중 플레이어에 가장 가까운 점까지의 택시 거리.
     *  청크 코너 좌표만으로 재면 특정 방향에서 거리가 과대평가되어 exile 복귀가
     *  지연되는 문제가 있어, 가까운 변까지의 거리를 계산한다. */
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
        searchActiveSet.clear();
        searchActiveSetVersion = -1;
        searchActiveSetSnapshotRoot = NO_ID;
        pendingActiveColorCandidate = NO_ID;
        pendingActiveColorStreak = 0;
        invalidateChunkCache();
        if (image != null) {
            image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
            markAllDirty();
        }
        originSet = false;
    }

    static void parkInExiledFrontier(long packedPos, int chunkX, int chunkZ) {
        long key = ChunkPos.toLong(chunkX, chunkZ);
        getOrCreateBucket(exiledFrontierChunkHash, key, 4).add(packedPos);
    }
}