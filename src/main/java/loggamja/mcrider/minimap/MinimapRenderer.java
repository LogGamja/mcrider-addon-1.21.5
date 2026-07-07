package loggamja.mcrider.minimap;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import loggamja.mcrider.option.MCRiderConfig;
import loggamja.mcrider.MCRiderMain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static loggamja.mcrider.minimap.ColorGraph.NO_ID;

/**
 * 미니맵 텍스처 관리(북쪽 고정, 부분 업로드), 화면 그리기, 셀->색상 계산 담당.
 * {@link FrontierSearch}가 채운 visitedColumns/cellColor/activeColor/activeSet을 읽기만
 * 한다(디버그 모드의 즉시 도색 경로에서만 FrontierSearch가 이 클래스를 역호출한다).
 */
final class MinimapRenderer {
    private MinimapRenderer() {}

    // 레이아웃(MCRiderRadar와 동일 스킴)
    private static final double LEGACY_GUI_SCALE_BASIS = 4.0;
    private static final int padding = (int) Math.round(10 * LEGACY_GUI_SCALE_BASIS);
    private static final int baseRadius = (int) Math.round(50 * LEGACY_GUI_SCALE_BASIS);

    static final double baseDist = 60;
    private static final double uiScale = 0.75;
    static final double distScale = 1;
    private static final int radius = (int) Math.round(baseRadius * uiScale);
    static final double maxDist = baseDist * distScale;

    // 북쪽 고정 텍스처
    private static final int TEX_SIZE = 512;
    private static final double SQRT2 = Math.sqrt(2.0);
    static final int REANCHOR_MARGIN = (int) Math.ceil(maxDist * SQRT2) + 8;
    private static final Identifier MINIMAP_ID = Identifier.of("mcrider-official", "minimap");
    private static final int VISITED_COLOR = 0xBBCCCCCC;

    private static final float IMAGE_CORRECTION_TRICK = 0.001f;
    private static final double RIDER_ICON_SIZE = 6 * LEGACY_GUI_SCALE_BASIS;
    private static final Identifier SELF_ARROW_ICON = Identifier.of("mcrider-official", "textures/hud/arrow_icon.png");
    private static final int SELF_ARROW_TEX_SIZE = 16;
    private static final float ENEMY_ICON_SCALE = 0.5f;

    private static final float ENEMY_HEAD_OUTLINE_THICKNESS = 0.4f;
    private static final int ENEMY_HEAD_OUTLINE_COLOR = 0xFF000000;
    private static final int SELF_OVERLAP_ALPHA = 0x80;

    private static NativeImage image;
    private static NativeImageBackedTexture texture;
    private static boolean textureDirty = false;
    private static int originX, originZ;
    private static boolean originSet = false;

    // 텍스처 부분 업로드는 "단일 바운딩 박스"가 아니라 "타일 단위 dirty 집합"으로 관리한다.
    // 멀리 떨어진 두 dirty 덩어리를 하나의 사각형으로 감싸면 사실상 풀업로드가 되므로,
    // TILE_SIZE 단위로 쪼개 실제로 바뀐 타일만 올린다.
    private static final int TILE_SIZE = 32;
    private static final int TILES_PER_ROW = TEX_SIZE / TILE_SIZE;
    private static final IntOpenHashSet dirtyTiles = new IntOpenHashSet();

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

    /** dirty 영역을 NativeImage에서 GPU 텍스처로 복사(1.21.5 blaze3d GPU 경로). 전체 업로드
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

    // 재도색 예산
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

    // 재빌드를 여러 틱에 나눠 이어그릴 때 쓰는 상태. 재앵커 직후 visitedColumns 전체를 한
    // 프레임에 순회하면 오래 탐색된 대용량 트랙에서 프레임이 오래 걸릴 수 있어, 스냅샷을 떠
    // repaintDirtyColumns와 같은 예산으로 여러 틱에 걸쳐 이어 그린다.
    private static long[] rebuildKeys = null;
    private static int rebuildIndex = 0;
    private static boolean rebuildInProgress = false;

    private static void rebuildTexture(BlockPos center) {
        ensureTexture();
        originX = center.getX() - TEX_SIZE / 2;
        originZ = center.getZ() - TEX_SIZE / 2;
        originSet = true;
        image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
        markAllDirty(); // 지운 화면을 즉시 반영. 재도색 자체는 아래에서 여러 틱에 걸쳐 이어진다.
        FrontierSearch.dirtyColumns.clear();
        rebuildKeys = FrontierSearch.visitedColumns.keySet().toLongArray();
        rebuildIndex = 0;
        rebuildInProgress = true;
    }

    /** rebuildTexture가 시작한 재도색을 이번 틱 예산만큼 이어 그린다(repaintDirtyColumns와
     *  같은 예산 상수 공유). 한 틱에 다 못 그리면 다음 틱에 이어서 계속되고, 그동안 미니맵은
     *  방금 지워진 상태에서 서서히 다시 채워진다 — 재앵커 직후 한 프레임이 통째로 오래
     *  걸리는 것보다는 여러 틱에 나눠 자연스럽게 다시 그려지는 편이 낫다는 판단. */
    private static void continueRebuildIfInProgress() {
        if (!rebuildInProgress) return;
        final long deadline = System.nanoTime() + REPAINT_TIME_BUDGET_NANOS;
        int budget = REPAINT_HARD_CAP_PER_TICK;
        int sinceTimeCheck = 0;
        while (rebuildIndex < rebuildKeys.length && budget > 0) {
            long key = rebuildKeys[rebuildIndex++];
            plotColumn(FrontierSearch.unpackColumnX(key), FrontierSearch.unpackColumnZ(key));
            budget--;
            if ((++sinceTimeCheck & 0xFF) == 0 && System.nanoTime() >= deadline) break;
        }
        if (rebuildIndex >= rebuildKeys.length) {
            rebuildInProgress = false;
            rebuildKeys = null;
        }
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
        continueRebuildIfInProgress();
    }

    static void plotColumn(int worldX, int worldZ) {
        if (image == null || !originSet) return;
        int tx = worldX - originX;
        int tz = worldZ - originZ;
        if (tx < 0 || tx >= TEX_SIZE || tz < 0 || tz >= TEX_SIZE) return;
        image.setColorArgb(tx, tz, computeColumnColor(worldX, worldZ));
        markPixelDirty(tx, tz);
    }
    private static boolean isInCurrentView(int worldX, int worldZ, int px, int pz) {
        double dx = worldX - px;
        double dz = worldZ - pz;
        double r = maxDist * SQRT2 + 8;
        return dx * dx + dz * dz <= r * r;
    }
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

        final boolean debug = MCRiderMinimap.isDebugColors();
        if (!debug && FrontierSearch.activeColor == NO_ID) return 0;

        int repY = Integer.MIN_VALUE;
        long repRoot = NO_ID;
        it.unimi.dsi.fastutil.ints.IntIterator yi = ys.iterator();
        while (yi.hasNext()) {
            int y = yi.nextInt();
            long root = FrontierSearch.resolvedRootAt(x, y, z);
            if (root == NO_ID) continue;
            if (!debug && !FrontierSearch.activeSet.contains(root)) continue;

            if (y >= repY) { repY = y; repRoot = root; }
        }
        if (debug) return colorForRoot(repRoot);
        return (repRoot != NO_ID) ? VISITED_COLOR : 0;
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

        // enableScissor/disableScissor는 DrawContext 내부의 진짜 push/pop 스택이라, 그 사이에서
        // 어떤 예외가 나든 disableScissor가 실행되도록 try/finally로 짝을 보장한다. 한 번이라도
        // pop이 누락되면 GL scissor가 좁은 사각형에 낀 채 다음 프레임까지 새어나가 화면 전체가
        // (엔티티 포함) 클리핑되는 사고로 이어진다(실제로 한 번 겪었음).
        context.enableScissor(viewX1, viewY1, viewX2, viewY2);
        try {
            MatrixStack matrices = context.getMatrices();
            matrices.push();
            try {
                matrices.translate(centerX, centerY, 0);
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180f - yawDeg));
                matrices.translate(-drawSize / 2f, -drawSize / 2f, 0);
                context.drawTexture(
                        RenderLayer::getGuiTextured, MINIMAP_ID,
                        0, 0, u0, v0, drawSize, drawSize, texRegion, texRegion, TEX_SIZE, TEX_SIZE);
            } finally {
                matrices.pop();
            }

            drawRiderIcons(context, tickDelta, centerX, centerY, yawDeg, p, blockToScreen, sizeFactor);
        } finally {
            context.disableScissor();
        }
    }
    private static void drawRiderIcons(DrawContext context, float tickDelta, int centerX, int centerY,
                                        float yawDeg, Vec3d p, double scale, double sizeFactor) {
        MinecraftClient client = MCRiderMinimap.client;

        final float iconSize = (float) (RIDER_ICON_SIZE * sizeFactor);
        final float enemyIconSize = iconSize * ENEMY_ICON_SCALE;
        final float selfIconSize = iconSize;
        // 자기/상대 아이콘 둘 다 카트 헤딩에 따라 회전해서 그려지므로, 축 정렬 겹침 판정에는
        // 회전 안 된 절반 크기가 아니라 45도 회전 시의 최악값(대각선 절반 = size/2 * √2)을 써야
        // 한다. 그렇지 않으면 대각선 방향 헤딩에서 실제로는 겹치는데도 놓칠 수 있다.
        final float enemyHalfExtent = (float) (enemyIconSize / 2f * SQRT2) + ENEMY_HEAD_OUTLINE_THICKNESS;
        final float selfHalfExtent = (float) (selfIconSize / 2f * SQRT2);

        final double yawRad = Math.toRadians(yawDeg);
        final double fx = -Math.sin(yawRad);
        final double fz = Math.cos(yawRad);
        final double rx = Math.cos(yawRad);
        final double rz = Math.sin(yawRad);

        // 자기 마커에 가려질 상대(자기 마커 영역과 겹치는 상대)는 나중에 반투명 재도색이
        // 필요하므로 따로 모아둔다. 상대방끼리 겹치는 건 신경 쓰지 않는다.
        final List<AbstractClientPlayerEntity> overlappingPlayers = new ArrayList<>();
        final List<float[]> overlappingIcons = new ArrayList<>(); // {dotX, dotY, relativeYaw}

        for (AbstractClientPlayerEntity other : Objects.requireNonNull(client.world).getPlayers()) {
            if (other == MCRiderMain.getRidingPlayer()) continue;
            if (other == other.getRootVehicle()) continue;

            final Vec3d q = other.getCameraPosVec(tickDelta);
            final double dx = q.x - p.x;
            final double dz = q.z - p.z;

            final double localForward = dx * fx + dz * fz;
            final double localRight = dx * rx + dz * rz;

            if (Math.abs(localForward) > maxDist || Math.abs(localRight) > maxDist) continue;

            final float dotX = (float) (centerX - localRight * scale);
            final float dotY = (float) (centerY - localForward * scale);

            final float enemyKartYaw = getKartBodyYaw(other);
            final float relativeYaw = (enemyKartYaw - yawDeg) + IMAGE_CORRECTION_TRICK;
            drawEnemyHead(context, other, dotX, dotY, enemyIconSize, relativeYaw);

            if (Math.abs(dotX - centerX) < selfHalfExtent + enemyHalfExtent
                    && Math.abs(dotY - centerY) < selfHalfExtent + enemyHalfExtent) {
                overlappingPlayers.add(other);
                overlappingIcons.add(new float[]{dotX, dotY, relativeYaw});
            }
        }

        // 자기 마커는 맨 마지막에 그려서, 겹치는 적 마커에 가려지지 않고 항상 위에 보이게 한다.
        final float myKartYaw = getKartBodyYaw(MCRiderMain.getRidingPlayer());
        final float delta = (myKartYaw - yawDeg) + IMAGE_CORRECTION_TRICK;
        drawSelfMarker(context, centerX, centerY, selfIconSize, delta, 0xFF);

        // 자기 마커가 가린 상대만, 겹친 영역에 한해 상대를 다시 그리고 그 위에 반투명한
        // 자기 마커를 덧그려 둘 다 보이게 한다.
        for (int i = 0; i < overlappingPlayers.size(); i++) {
            final float[] icon = overlappingIcons.get(i);
            final float dotX = icon[0], dotY = icon[1], relativeYaw = icon[2];

            final int left = (int) Math.floor(Math.max(centerX - selfHalfExtent, dotX - enemyHalfExtent));
            final int top = (int) Math.floor(Math.max(centerY - selfHalfExtent, dotY - enemyHalfExtent));
            final int right = (int) Math.ceil(Math.min(centerX + selfHalfExtent, dotX + enemyHalfExtent));
            final int bottom = (int) Math.ceil(Math.min(centerY + selfHalfExtent, dotY + enemyHalfExtent));
            if (right <= left || bottom <= top) continue;

            // scissor는 push/pop 스택이라(위 renderMinimap 주석 참고) 반드시 disableScissor로
            // 짝을 맞춰야 하며, try/finally로 감싸 중간에 예외가 나도 pop이 보장되게 한다.
            context.enableScissor(left, top, right, bottom);
            try {
                drawEnemyHead(context, overlappingPlayers.get(i), dotX, dotY, enemyIconSize, relativeYaw);
                drawSelfMarker(context, centerX, centerY, selfIconSize, delta, SELF_OVERLAP_ALPHA);
            } finally {
                context.disableScissor();
            }
        }
    }

    private static float getKartBodyYaw(PlayerEntity player) {
        Entity kart = player.getRootVehicle();
        if (kart != null && kart != player) {
            for (Entity passenger : kart.getPassengerList()) {
                if (MCRiderMain.hasCertainName(passenger, "mcrider-modelsaddle")) {
                    return passenger.getYaw();
                }
            }
        }
        return player.getYaw();
    }
    private static void drawSelfMarker(DrawContext context, float cx, float cy, float size, float rotationDeg, int alpha) {
        // MatrixStack도 scissor와 마찬가지로 push/pop 스택이라, 그 사이에서 예외가 나면 pop이
        // 스킵되어 이번 프레임에 이후 그려지는 다른 HUD 요소들이 어긋난 위치/회전으로 밀려
        // 그려질 수 있다. try/finally로 pop을 보장한다.
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        try {
            matrices.translate(cx, cy, 0);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotationDeg));
            matrices.translate(-size / 2f, -size / 2f, 0);

            final int color = (alpha << 24) | 0xFFFFFF;

            context.drawTexture(
                    RenderLayer::getGuiTextured,
                    SELF_ARROW_ICON,
                    0, 0,
                    0f, 0f,
                    Math.round(size), Math.round(size),
                    SELF_ARROW_TEX_SIZE, SELF_ARROW_TEX_SIZE,
                    SELF_ARROW_TEX_SIZE, SELF_ARROW_TEX_SIZE,
                    color
            );
        } finally {
            matrices.pop();
        }
    }
    private static void drawEnemyHead(DrawContext context, AbstractClientPlayerEntity player,
                                       float cx, float cy, float size, float rotationDeg) {
        int isize = Math.round(size);

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        try {
            matrices.translate(cx, cy, 0);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotationDeg));
            matrices.translate(-size / 2f, -size / 2f, 0);

            // 테두리 사각형을 (isize+2) 정수 크기로 그리되, 얼굴 중심을 기준으로 살짝만 확대해
            // 실제로 삐져나오는 폭이 정수 1이 아니라 ENEMY_HEAD_OUTLINE_THICKNESS만큼만 되게 한다.
            matrices.push();
            try {
                float outlineScale = (isize + 2f * ENEMY_HEAD_OUTLINE_THICKNESS) / (isize + 2f);
                matrices.translate(isize / 2f, isize / 2f, 0);
                matrices.scale(outlineScale, outlineScale, 1f);
                matrices.translate(-(isize + 2f) / 2f, -(isize + 2f) / 2f, 0);
                context.fill(0, 0, isize + 2, isize + 2, ENEMY_HEAD_OUTLINE_COLOR);
            } finally {
                matrices.pop();
            }

            PlayerSkinDrawer.draw(context, player.getSkinTextures(), 0, 0, isize);
        } finally {
            matrices.pop();
        }
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
