package loggamja.mcrider;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
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
 * (WIP) 플러드필 기반 트랙 미니맵.
 *
 * 성능 설계:
 *  - 방문/프론티어/보류 자료구조는 전부 fastutil(long 패킹) 기반. Pair 박싱 없음.
 *  - 블록 접근은 마지막 청크를 캐시해서 청크 룩업을 줄이고, BlockPos.Mutable을 재사용.
 *  - 미니맵 텍스처는 "북쪽 고정(world-aligned)"으로 유지하며,
 *    플러드필이 새 컬럼을 발견한 순간에만 픽셀을 찍는다(증분 갱신).
 *    아무것도 탐색되지 않은 프레임에는 GPU 업로드가 발생하지 않는다.
 *  - 회전·확대·스크롤은 렌더 시점에 MatrixStack + UV 오프셋으로 처리하므로
 *    프레임당 비용은 쿼드 몇 개 수준(GPU 정점 변환)이다.
 */
public class MCRiderMinimap implements ClientModInitializer {
    static MinecraftClient client = MinecraftClient.getInstance();

    // ── 탐색 상태 ────────────────────────────────────────────────────────
    static boolean isRequireKeepSearching = false;
    static BlockPos lastPlayerPos;

    /** 프론티어 큐 (BlockPos.asLong 패킹, FIFO) */
    static LongArrayFIFOQueue frontierQueue = new LongArrayFIFOQueue();
    /** 미로딩/범위 밖 청크에 보류된 프론티어: ChunkPos.toLong → BlockPos.asLong */
    static Long2LongOpenHashMap exiledFrontierChunkHash = new Long2LongOpenHashMap();
    /** 방문 기록: packColumn(x,z) → 방문한 Y 집합 */
    static Long2ObjectOpenHashMap<IntOpenHashSet> visited = new Long2ObjectOpenHashMap<>();

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
    /** 텍스처 한 변(px). 1px = 1블록. 표시에 필요한 지름(2·maxDist·√2 ≈ 284)보다 넉넉하게. */
    private static final int TEX_SIZE = 512;
    /** 회전해도 정사각 뷰포트가 항상 채워지도록 하는 대각선 여유 계수 */
    private static final double SQRT2 = Math.sqrt(2.0);
    /** 플레이어가 텍스처 가장자리에 이만큼 가까워지면 원점을 재설정하고 다시 그린다 */
    private static final int REANCHOR_MARGIN = (int) Math.ceil(maxDist * SQRT2) + 8;

    private static final Identifier MINIMAP_ID = Identifier.of("mcrider-official", "minimap");
    private static final int VISITED_COLOR = 0xBBFFFFFF;

    private static NativeImage image;
    private static NativeImageBackedTexture texture;
    private static boolean textureDirty = false;
    /** 텍스처 (0,0) 픽셀이 가리키는 월드 좌표 */
    private static int originX, originZ;
    private static boolean originSet = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            onTickStart();
        });

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

    /** 원점을 플레이어 중심으로 잡고 visited 전체를 다시 그린다 (재앵커 시 1회성 비용) */
    private static void rebuildTexture(BlockPos center) {
        ensureTexture();

        originX = center.getX() - TEX_SIZE / 2;
        originZ = center.getZ() - TEX_SIZE / 2;
        originSet = true;

        image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);

        LongIterator it = visited.keySet().longIterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            plotColumn(unpackColumnX(key), unpackColumnZ(key));
        }
        textureDirty = true;
    }

    /** 방문 컬럼 1개를 텍스처에 기록. 새 컬럼 발견 시에만 호출된다. */
    private static void plotColumn(int worldX, int worldZ) {
        if (image == null || !originSet) return;

        int tx = worldX - originX;
        int tz = worldZ - originZ;
        if (tx < 0 || tx >= TEX_SIZE || tz < 0 || tz >= TEX_SIZE) return;

        image.setColorArgb(tx, tz, VISITED_COLOR);
        textureDirty = true;
    }

    private void renderMinimap(DrawContext context, float tickDelta) {
        if (!MCRiderConfig.INSTANCE.useMinimap) return;
        if (visited.isEmpty() || !originSet) return;
        if (!MCRiderMain.isPlayingInGame()) return;

        ensureTexture();

        // 탐색으로 픽셀이 바뀐 프레임에만 GPU 업로드
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

        // 반투명 검은색 배경
        context.fill(viewX1, viewY1, viewX2, viewY2, 0x88000000);

        final float yawDeg = client.gameRenderer.getCamera().getYaw();
        final Vec3d p = MCRiderMain.getRidingPlayer().getCameraPosVec(tickDelta);

        // 회전한 쿼드의 모서리가 잘려 보이지 않도록 √2 만큼 크게 그리고,
        // 정사각 뷰포트는 scissor로 자른다.
        final double blockToScreen = scaledRadius / maxDist;
        final int texRegion = 2 * (int) Math.ceil(maxDist * SQRT2);            // 텍스처에서 잘라올 영역(px = 블록)
        final int drawSize = (int) Math.round(texRegion * blockToScreen);       // 화면에 그릴 크기
        final float u0 = (float) (p.x - originX - texRegion / 2.0);
        final float v0 = (float) (p.z - originZ - texRegion / 2.0);

        context.enableScissor(viewX1, viewY1, viewX2, viewY2);

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        // 1) 미니맵 중앙으로 이동
        matrices.translate(centerX, centerY, 0);
        // 2) "플레이어 진행 방향 = 화면 위"가 되도록 회전.
        //    북쪽 고정 텍스처(u=+X, v=+Z)를 기존 CPU 샘플링과 동일한 방향으로 매핑하면 180° - yaw.
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180f - yawDeg));
        // 3) 플레이어(=텍스처 영역 중심)를 회전 피벗에 정렬
        matrices.translate(-drawSize / 2f, -drawSize / 2f, 0);

        context.drawTexture(
                RenderLayer::getGuiTextured,
                MINIMAP_ID,
                0, 0,
                u0, v0,                 // 소수 UV → 블록 단위 이하의 부드러운 스크롤
                drawSize, drawSize,
                texRegion, texRegion,
                TEX_SIZE, TEX_SIZE
        );

        matrices.pop();
        context.disableScissor();

        // 플레이어 위치 표시 (중앙 점)
        context.fill(centerX - 2, centerY - 2, centerX + 2, centerY + 2, 0xFFFFFFFF);
    }

    /** MCRiderRadar.getSizeFactor와 동일: GUI 배율/해상도 무관하게 동일한 물리 크기 유지 */
    private double getSizeFactor(MinecraftClient client) {
        final double physicalScale = client.getWindow().getHeight() / 1080.0;
        final double scaleFactor = client.getWindow().getScaleFactor();
        return physicalScale / scaleFactor;
    }

    // ═════════════════════════════ 탐색 ═════════════════════════════

    static final TagKey<Block> KART_WALL = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "stones"));
    static final Predicate<BlockState> isWall = state -> state.isIn(KART_WALL);

    static final TagKey<Block> KART_AIR = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "ignoreblock"));
    static final Predicate<BlockState> isAir = state -> state.isIn(KART_AIR);

    // 블록 접근 최적화: 마지막 청크 캐시 + Mutable 재사용
    private static Chunk cachedChunk;
    private static long cachedChunkKey = Long.MIN_VALUE;
    private static final BlockPos.Mutable MUTABLE = new BlockPos.Mutable();

    private static void invalidateChunkCache() {
        cachedChunk = null;
        cachedChunkKey = Long.MIN_VALUE;
    }

    private static BlockState stateAt(int x, int y, int z) {
        long key = ChunkPos.toLong(x >> 4, z >> 4);
        if (key != cachedChunkKey || cachedChunk == null) {
            cachedChunk = client.world.getChunk(x >> 4, z >> 4);
            cachedChunkKey = key;
        }
        return cachedChunk.getBlockState(MUTABLE.set(x, y, z));
    }

    static boolean isAirAt(int x, int y, int z) {
        if (client.world == null) return false;
        return isAir.test(stateAt(x, y, z));
    }

    /**
     * 해당 좌표의 청크가 클라이언트에 실제로 로딩되어 있는지 확인한다.
     * 언로드된 청크의 getBlockState()는 예외 없이 공기를 반환하므로,
     * 이 검사 없이 확장하면 미탐색 영역이 전부 뚫린 것으로 오인되어 블리딩이 발생한다.
     */
    static boolean isChunkLoadedAt(int x, int z) {
        if (client.world == null) return false;
        return client.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }

    static int tickCounter = 0;
    static final int REDRAW_INTERVAL_TICKS = 20; // 1초 (20 TPS 기준)

    void onTickStart() {
        if (!MCRiderConfig.INSTANCE.useMinimap) return;
        if (client.player == null || client.world == null) return;

        tickCounter++;
        if (tickCounter < REDRAW_INTERVAL_TICKS) return;
        tickCounter = 0;

        final int playerMargin = 5;

        BlockPos start = client.player.getBlockPos();
        lastPlayerPos = start;

        // 1초에 한 번, visited/텍스처를 통째로 비우고 현재 위치 기준으로
        // 처음부터 다시 플러드필한다. 이전 탐색 결과를 재사용하지 않으므로
        // "이탈 구간이 누적되는" 문제는 애초에 발생할 수 없다.
        clearAllMap();
        rebuildTexture(start);

        // updatePixel 예산을 사실상 무제한으로 줘서 이번 호출 안에 끝까지 그린다.
        floodFillWithVertical(start, (int) ((maxDist + playerMargin * 2) * 2), Integer.MAX_VALUE);
    }


    static void floodFillWithVertical(BlockPos start, int maxRange, int updatePixel) {
        isRequireKeepSearching = false;

        var world = client.world;
        if (world == null) return;

        invalidateChunkCache();

        // 시작 청크가 아직 로딩 전이면 이번 틱은 건너뛰고 다음 틱에 재시도
        if (!isChunkLoadedAt(start.getX(), start.getZ())) {
            isRequireKeepSearching = true;
            return;
        }

        int sx = start.getX(), sy = start.getY(), sz = start.getZ();
        if (isAirAt(sx, sy, sz)) {
            while (isAirAt(sx, sy - 1, sz) && sy > world.getBottomY()) {
                sy--;
            }
        }
        if (sy <= world.getBottomY()) {
            return;
        }

        if (frontierQueue.isEmpty()) {
            appendToQueueAndVisited(sx, sy, sz);
        }
        final int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };

        // 보류된 프론티어 복귀: 범위 안 + 대상 청크가 로딩 완료된 경우에만
        ObjectIterator<Long2LongMap.Entry> exiledIt = exiledFrontierChunkHash.long2LongEntrySet().iterator();
        while (exiledIt.hasNext()) {
            Long2LongMap.Entry e = exiledIt.next();
            long chunkKey = e.getLongKey();
            int chunkX = ChunkPos.getPackedX(chunkKey);
            int chunkZ = ChunkPos.getPackedZ(chunkKey);

            if (taxiDistanceFromChunkToPos(chunkX, chunkZ, sx, sz) < maxRange
                    && client.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                frontierQueue.enqueue(e.getLongValue());
                exiledIt.remove();
            }
        }

        while (!frontierQueue.isEmpty()) {
            updatePixel--;
            if (updatePixel <= 0) {
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

            // 큐에 있는 동안 청크가 언로드된 경우: 판정하지 말고 보류
            if (!isChunkLoadedAt(cx, cz)) {
                parkInExiledFrontier(curPacked, cx >> 4, cz >> 4);
                continue;
            }

            boolean hasBlockAt2Meter = !isAirAt(cx, cy + 2, cz);

            for (int[] d : dirs) {
                int nx = cx + d[0];
                int nz = cz + d[1];

                // 이웃 칸이 미로딩 청크에 속하면 확장하지 않고,
                // 현재 경계 셀(cur)을 그 청크 키로 보류해 두었다가 로딩되면 재개한다.
                if (!isChunkLoadedAt(nx, nz)) {
                    parkInExiledFrontier(curPacked, nx >> 4, nz >> 4);
                    continue;
                }

                if (isWall.test(stateAt(nx, cy, nz))) continue;

                if (isPosVisited(nx, cy, nz)) continue;
                if (isPosVisited(nx, cy + 1, nz)) continue;
                if (!isAirAt(nx, cy + 1, nz)) continue;

                if (!isAirAt(nx, cy, nz)) {
                    if (isAirAt(nx, cy + 2, nz) && !hasBlockAt2Meter) {
                        appendToQueueAndVisited(nx, cy + 1, nz);
                    }
                }
                else if (isAirAt(nx, cy - 1, nz)) {
                    int fy = cy;
                    while (isAirAt(nx, fy - 1, nz) && fy > world.getBottomY()) {
                        fy--;
                    }
                    if (!isPosVisited(nx, fy, nz)) {
                        appendToQueueAndVisited(nx, fy, nz);
                    }
                }
                else {
                    appendToQueueAndVisited(nx, cy, nz);
                }
            }
        }
    }

    static void appendToQueueAndVisited(int x, int y, int z) {
        if (isPosVisited(x, y, z)) return;
        if (!isAirAt(x, y + 1, z)) return;

        addToVisited(x, y, z);
        frontierQueue.enqueue(BlockPos.asLong(x, y, z));
    }

    // ── 방문 기록 (fastutil, long 패킹) ─────────────────────────────────

    static long packColumn(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
    static int unpackColumnX(long key) {
        return (int) (key >> 32);
    }
    static int unpackColumnZ(long key) {
        return (int) key;
    }

    static void addToVisited(int x, int y, int z) {
        long key = packColumn(x, z);
        IntOpenHashSet ys = visited.get(key);
        if (ys == null) {
            ys = new IntOpenHashSet(4);
            visited.put(key, ys);
            // 새 컬럼 → 미니맵 텍스처에 증분 기록
            plotColumn(x, z);
        }
        ys.add(y);
    }
    static boolean isPosVisited(int x, int y, int z) {
        IntOpenHashSet ys = visited.get(packColumn(x, z));
        return ys != null && ys.contains(y);
    }
    static boolean isColumnVisited(int x, int z) {
        return visited.containsKey(packColumn(x, z));
    }

    static int taxiDistance2D(int ax, int az, int bx, int bz) {
        return Math.abs(ax - bx) + Math.abs(az - bz);
    }
    static int taxiDistanceFromChunkToPos(int chunkX, int chunkZ, int bx, int bz) {
        return Math.abs(chunkX * 16 - bx) + Math.abs(chunkZ * 16 - bz);
    }

    static void clearAllMap() {
        visited.clear();
        frontierQueue.clear();
        exiledFrontierChunkHash.clear();

        if (image != null) {
            image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
            textureDirty = true;
        }
        originSet = false;
    }

    static void parkInExiledFrontier(long packedPos, int chunkX, int chunkZ) {
        exiledFrontierChunkHash.put(ChunkPos.toLong(chunkX, chunkZ), packedPos);
    }
}