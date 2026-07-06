package loggamja.mcrider.minimap;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import loggamja.mcrider.MCRiderConfig;
import loggamja.mcrider.MCRiderMain;

import static loggamja.mcrider.minimap.ColorGraph.NO_ID;

/**
 * 미니맵 텍스처 관리(북쪽 고정, 부분 업로드), 화면 그리기, 셀 → 색상 계산.
 *
 * {@link FrontierSearch}가 채운 visitedColumns/cellColor/activeColor/activeSet을 읽어서
 * 텍스처에 칠하고 화면에 그리는 역할만 한다. 탐색/색 그래프에는 관여하지 않는다.
 * (paintCell의 디버그 모드(isDebugColors()) 즉시-도색 경로에서만 FrontierSearch → 이 클래스 역방향 호출이 있고,
 * 그 외엔 전부 이 클래스가 FrontierSearch를 읽기만 한다.)
 */
final class MinimapRenderer {
    private MinimapRenderer() {}

    // ── 레이아웃 (MCRiderRadar와 동일 스킴) ──────────────────────────────
    private static final double LEGACY_GUI_SCALE_BASIS = 4.0;
    private static final int padding = (int) Math.round(10 * LEGACY_GUI_SCALE_BASIS);
    private static final int baseRadius = (int) Math.round(50 * LEGACY_GUI_SCALE_BASIS);
    static final double baseDist = 75;
    private static final double uiScale = 0.75;
    static final double distScale = 1;
    private static final int radius = (int) Math.round(baseRadius * uiScale);
    static final double maxDist = baseDist * distScale;

    // ── 북쪽 고정 텍스처 ─────────────────────────────────────────────────
    private static final int TEX_SIZE = 512;
    private static final double SQRT2 = Math.sqrt(2.0);
    static final int REANCHOR_MARGIN = (int) Math.ceil(maxDist * SQRT2) + 8;
    private static final Identifier MINIMAP_ID = Identifier.of("mcrider-official", "minimap");
    private static final int VISITED_COLOR = 0xBBFFFFFF;

    private static NativeImage image;
    private static NativeImageBackedTexture texture;
    private static boolean textureDirty = false;
    private static int originX, originZ;
    private static boolean originSet = false;

    // 텍스처 부분 업로드는 "단일 바운딩 박스"가 아니라 "타일 단위 dirty 집합"으로 관리한다.
    // 멀리 떨어진 두 dirty 덩어리를 하나의 사각형으로 감싸면 사실상 풀업로드가 되므로,
    // TILE_SIZE 단위로 쪼개 실제로 바뀐 타일만 올린다.
    private static final int TILE_SIZE = 32; // 512 / 32 = 16×16 = 256개 타일
    private static final int TILES_PER_ROW = TEX_SIZE / TILE_SIZE;
    private static final IntOpenHashSet dirtyTiles = new IntOpenHashSet();
    /** 전체 텍스처를 통째로 올려야 하는 상황(재앵커/reset)이면 true. 이땐 타일 집합을 무시하고
     *  한 번의 writeToTexture로 올린다 — markAllDirty가 256개 타일을 개별 업로드하며 GPU 호출이
     *  한 프레임에 몰리던 스파이크를 없앤다. */
    private static boolean uploadWholeTexture = false;

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
        uploadWholeTexture = true;
        textureDirty = true;
    }

    private static void clearUploadState() {
        dirtyTiles.clear();
        uploadWholeTexture = false;
    }

    /** dirty 영역을 NativeImage → GPU 텍스처로 복사(1.21.5 blaze3d GPU 경로). 전체 업로드
     *  상황이면 한 번에, 아니면 dirty 타일만 개별로 올린다. */
    private static void uploadDirtyRegion() {
        if (image == null || texture == null) { clearUploadState(); return; }
        if (!uploadWholeTexture && dirtyTiles.isEmpty()) return;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (uploadWholeTexture) {
            encoder.writeToTexture(texture.getGlTexture(), image, 0, 0, 0, TEX_SIZE, TEX_SIZE, 0, 0);
            clearUploadState();
            return;
        }
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
        clearUploadState();
    }

    // ── 재도색 예산 ──────────────────────────────────────────────────────
    /** dirtyColumns 재도색(plotColumn) 시간 예산. 컬럼 하나는 저렴하므로 "개수"가 아니라
     *  "이번 틱에 실제로 쓴 시간"으로 제한한다. 평소엔 이 안에 다 끝나 즉시 반영되고,
     *  대용량 트랙이 한꺼번에 dirty해지는 극단적 경우만 여러 틱에 나눠 그려진다. */
    private static final long REPAINT_TIME_BUDGET_NANOS = 2_000_000L; // 2ms (50ms 틱의 4%)
    /** 시간 예산과 별개인 개수 상한(안전장치). 컬럼당 비용이 비정상적으로 커지는 상황에서도
     *  한 틱이 무한정 길어지는 것을 막는다. 평소엔 시간 예산이 먼저 걸려 거의 도달하지 않는다. */
    private static final int REPAINT_HARD_CAP_PER_TICK = 200_000;

    private static void ensureTexture() {
        if (texture != null) return;
        image = new NativeImage(NativeImage.Format.RGBA, TEX_SIZE, TEX_SIZE, false);
        image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
        texture = new NativeImageBackedTexture(() -> "mcrider-minimap", image);
        MCRiderMinimap.client.getTextureManager().registerTexture(MINIMAP_ID, texture);
    }

    private static void rebuildTexture(BlockPos center) {
        ensureTexture();
        originX = center.getX() - TEX_SIZE / 2;
        originZ = center.getZ() - TEX_SIZE / 2;
        originSet = true;
        image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
        LongIterator it = FrontierSearch.visitedColumns.keySet().longIterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            plotColumn(FrontierSearch.unpackColumnX(key), FrontierSearch.unpackColumnZ(key));
        }
        markAllDirty(); // fillRect로 전체를 지웠으므로 전체를 다시 올려야 한다.
        FrontierSearch.dirtyColumns.clear();
    }

    /** originSet이 아니거나 플레이어가 재앵커 여유 범위를 벗어났으면 텍스처를 재생성한다.
     *  onTickStart에서 탐색 직후, 재도색 직전에 호출된다. */
    static void ensureOriginFor(BlockPos start) {
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
    }

    static void plotColumn(int worldX, int worldZ) {
        if (image == null || !originSet) return;
        int tx = worldX - originX;
        int tz = worldZ - originZ;
        if (tx < 0 || tx >= TEX_SIZE || tz < 0 || tz >= TEX_SIZE) return;
        image.setColorArgb(tx, tz, computeColumnColor(worldX, worldZ));
        markPixelDirty(tx, tz);
    }

    /** (worldX,worldZ)가 지금 화면에 보이는 원형 시야 범위 안인지 판정한다. 미니맵이 플레이어
     *  중심으로 회전하므로 방향 무관 반경만 본다. maxDist로 크기가 고정돼(회전 대각선 여유
     *  SQRT2배 포함) dirtyColumns가 커져도 이 범위 안 컬럼 수는 스파이크를 내지 않는다. */
    private static boolean isInCurrentView(int worldX, int worldZ, int px, int pz) {
        double dx = worldX - px;
        double dz = worldZ - pz;
        double r = maxDist * SQRT2 + 8;
        return dx * dx + dz * dz <= r * r;
    }

    /** 전체 재도색 없이 dirtyColumns만 다시 그린다. 비용은 실제 변경분에 비례하며, 시간
     *  예산으로 한 틱을 제한하고 못 그린 항목은 다음 틱으로 넘어간다(유실 없음).
     *
     *  주의: 예산 자체를 없애면 안 된다. activeColor 전환 시 isInCurrentView가 Y는 안 보고
     *  X/Z만 보므로 뷰 안 dirty 컬럼이 수만 개일 수 있어, 예산 없이는 프레임드랍이 재발한다
     *  (실제로 재발했던 문제). 대신 예산을 "뷰 안" 컬럼에 먼저 쓰고 남으면 "뷰 밖"에 쓴다. */
    static void repaintDirtyColumns(BlockPos start) {
        if (FrontierSearch.dirtyColumns.isEmpty() || !originSet) return;

        int px = start.getX(), pz = start.getZ();
        final long repaintDeadline = System.nanoTime() + REPAINT_TIME_BUDGET_NANOS;
        int hardCap = REPAINT_HARD_CAP_PER_TICK;
        int sinceTimeCheck = 0;

        LongIterator dirtyIt = FrontierSearch.dirtyColumns.iterator();
        boolean timedOut = false;
        while (dirtyIt.hasNext() && hardCap > 0 && !timedOut) {
            long key = dirtyIt.nextLong();
            int wx = FrontierSearch.unpackColumnX(key), wz = FrontierSearch.unpackColumnZ(key);
            if (isInCurrentView(wx, wz, px, pz)) {
                plotColumn(wx, wz);
                dirtyIt.remove();
                hardCap--;
            }
            // nanoTime() 호출도 공짜가 아니므로 대용량 백로그 스캔 시 256개마다만 확인한다.
            if ((++sinceTimeCheck & 0xFF) == 0 && System.nanoTime() >= repaintDeadline) {
                timedOut = true;
            }
        }

        if (!timedOut) {
            dirtyIt = FrontierSearch.dirtyColumns.iterator();
            while (dirtyIt.hasNext() && hardCap > 0) {
                long key = dirtyIt.nextLong();
                plotColumn(FrontierSearch.unpackColumnX(key), FrontierSearch.unpackColumnZ(key));
                dirtyIt.remove();
                hardCap--;
                if ((++sinceTimeCheck & 0xFF) == 0 && System.nanoTime() >= repaintDeadline) break;
            }
        }
    }

    private static int computeColumnColor(int x, int z) {
        IntOpenHashSet ys = FrontierSearch.visitedColumns.get(FrontierSearch.packColumn(x, z));
        if (ys == null || ys.isEmpty()) return 0;

        it.unimi.dsi.fastutil.ints.IntIterator yi = ys.iterator();
        if (MCRiderMinimap.isDebugColors()) {
            int repY = Integer.MIN_VALUE;
            long repRoot = NO_ID;
            while (yi.hasNext()) {
                int y = yi.nextInt();
                long root = FrontierSearch.resolvedRootAt(x, y, z);
                if (root == NO_ID) continue;
                if (y >= repY) { repY = y; repRoot = root; }
            }
            return colorForRoot(repRoot);
        } else {
            if (FrontierSearch.activeColor == NO_ID) return 0;
            // DEBUG 분기와 동일하게 "가장 높은 y"(=위에서 내려다볼 때 실제로 보이는 표면)만
            // 대표로 삼는다. 아무 y나 activeSet에 걸리면 칠하던 예전 방식은, 다리가 활성
            // 터널 위를 지나가는 경우처럼 물리적으로 안 이어진(그래프상 무관한) 위쪽 트랙까지
            // "활성"으로 잘못 표시하는 문제가 있었다.
            int repY = Integer.MIN_VALUE;
            long repRoot = NO_ID;
            while (yi.hasNext()) {
                int y = yi.nextInt();
                long root = FrontierSearch.resolvedRootAt(x, y, z);
                if (root == NO_ID) continue;
                if (y >= repY) { repY = y; repRoot = root; }
            }
            return (repRoot != NO_ID && FrontierSearch.activeSet.contains(repRoot)) ? VISITED_COLOR : 0;
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

    static void renderMinimap(DrawContext context, float tickDelta) {
        if (MCRiderConfig.INSTANCE.useMinimap == 0) return;
        if (!MCRiderMain.isRidingKart) return;
        if (FrontierSearch.visitedColumns.isEmpty() || !originSet) return;
        if (!MCRiderMain.isPlayingInGame()) return;

        MinecraftClient client = MCRiderMinimap.client;

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

    private static double getSizeFactor(MinecraftClient client) {
        final double physicalScale = client.getWindow().getHeight() / 1080.0;
        final double scaleFactor = client.getWindow().getScaleFactor();
        return physicalScale / scaleFactor;
    }

    static void reset() {
        if (image != null) {
            image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
            markAllDirty();
        }
        originSet = false;
    }
}
