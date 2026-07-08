package loggamja.mcrider.minimap;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.IntIterator;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import loggamja.mcrider.option.MCRiderConfig;
import loggamja.mcrider.MCRiderMain;

import java.io.InputStream;
import java.util.Objects;

import static loggamja.mcrider.minimap.ColorGraph.NO_ID;

final class MinimapRenderer {
    private MinimapRenderer() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("mcrider");

    private static final double LEGACY_GUI_SCALE_BASIS = 4.0;
    private static final int padding = (int) Math.round(10 * LEGACY_GUI_SCALE_BASIS);
    private static final int baseRadius = (int) Math.round(50 * LEGACY_GUI_SCALE_BASIS);

    static final double baseDist = 60;
    private static final double uiScale = 0.75;
    static final double distScale = 1;
    private static final int radius = (int) Math.round(baseRadius * uiScale);
    static final double maxDist = baseDist * distScale;

    private static final int TEX_SIZE = 512;
    private static final double SQRT2 = Math.sqrt(2.0);

    // 마진 16: 재앵커 ~150블록마다, 복사도 예산 큐로 분산되므로 충분함.
    static final int REANCHOR_MARGIN = (int) Math.ceil(maxDist * SQRT2) + 16;
    private static final int VISITED_COLOR = 0xBBCCCCCC;

    private static final float IMAGE_CORRECTION_TRICK = 0.001f;
    private static final double RIDER_ICON_SIZE = 6 * LEGACY_GUI_SCALE_BASIS;
    private static final Identifier SELF_ARROW_ICON = Identifier.of("mcrider-official", "textures/hud/arrow_icon.png");
    private static final int SELF_ARROW_TEX_SIZE = 16;
    private static final float ENEMY_ICON_SCALE = 0.75f;

    private static final float ENEMY_HEAD_OUTLINE_THICKNESS = 0.4f;
    private static final int ENEMY_HEAD_OUTLINE_COLOR = 0xFF000000;

    private static final class TextureBuffer {
        final Identifier id;
        NativeImage image;
        NativeImageBackedTexture texture;
        int originX, originZ;
        boolean originSet = false;
        boolean textureDirty = false;
        final IntOpenHashSet dirtyTiles = new IntOpenHashSet();
        boolean uploadWholeTexture = false;

        TextureBuffer(Identifier id) { this.id = id; }

        void ensure() {
            if (texture != null) return;
            image = new NativeImage(NativeImage.Format.RGBA, TEX_SIZE, TEX_SIZE, false);
            image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
            texture = new NativeImageBackedTexture(() -> "mcrider-minimap-" + id.getPath(), image);
            MCRiderMinimap.client.getTextureManager().registerTexture(id, texture);
        }

        void markPixelDirty(int tx, int tz) {
            if (tx < 0 || tx >= TEX_SIZE || tz < 0 || tz >= TEX_SIZE) return;
            dirtyTiles.add(tileKey(tx / TILE_SIZE, tz / TILE_SIZE));
            textureDirty = true;
        }

        void markAllDirty() {
            dirtyTiles.clear();
            uploadWholeTexture = true;
            textureDirty = true;
        }

        void clearUploadState() {
            dirtyTiles.clear();
            uploadWholeTexture = false;
        }

        void uploadDirtyRegion() {
            if (image == null || texture == null) { clearUploadState(); return; }
            if (!uploadWholeTexture && dirtyTiles.isEmpty()) return;
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            if (uploadWholeTexture) {
                encoder.writeToTexture(texture.getGlTexture(), image, 0, 0, 0, TEX_SIZE, TEX_SIZE, 0, 0);
                clearUploadState();
                return;
            }
            IntIterator it = dirtyTiles.iterator();
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

        void plotColumn(int worldX, int worldZ) {
            if (image == null || !originSet) return;
            int tx = worldX - originX;
            int tz = worldZ - originZ;
            if (tx < 0 || tx >= TEX_SIZE || tz < 0 || tz >= TEX_SIZE) return;
            image.setColorArgb(tx, tz, computeColumnColor(worldX, worldZ));
            markPixelDirty(tx, tz);
        }

        void close() {
            if (image != null) {
                image.close();
                image = null;
            }
            if (texture != null) {
                texture.close();
                texture = null;
            }
            dirtyTiles.clear();
            originSet = false;
        }
    }

    private static final Identifier MINIMAP_ID_A = Identifier.of("mcrider-official", "minimap_a");
    private static final Identifier MINIMAP_ID_B = Identifier.of("mcrider-official", "minimap_b");
    private static TextureBuffer front = new TextureBuffer(MINIMAP_ID_A);
    private static TextureBuffer back = new TextureBuffer(MINIMAP_ID_B);

    private static final int TILE_SIZE = 32;
    private static final int TILES_PER_ROW = TEX_SIZE / TILE_SIZE;

    private static int tileKey(int tileX, int tileZ) {
        return tileX * TILES_PER_ROW + tileZ;
    }

    private static final long REPAINT_TIME_BUDGET_NANOS = 2_000_000L;
    private static final int REPAINT_HARD_CAP_PER_TICK = 262_144;

    private static TextureBuffer rebuildTarget = null;
    private static boolean rebuildInProgress = false;

    // 재앵커 스크롤 재사용: 겹침 영역은 복사, 새 영역은 재계산. 복사도 예산 큐로 여러 틱에 분산.
    private static final class RebuildRect {
        int x, z, w, h;
        boolean isCopy;
        int copyDx, copyDz;  // 복사 사각형 전용: dst -> src 오프셋
    }
    private static final RebuildRect[] rebuildRects = new RebuildRect[3];
    private static int rebuildRectCount = 0;
    private static int rebuildRectCursor = 0;      // 현재 처리 중인 사각형 인덱스
    private static int rebuildRectLocalIndex = 0;  // 그 사각형 안에서의 진행도(픽셀 단위)

    static {
        for (int i = 0; i < rebuildRects.length; i++) {
            rebuildRects[i] = new RebuildRect();
        }
    }

    private static void addRebuildRect(int x, int z, int w, int h, boolean isCopy) {
        if (w <= 0 || h <= 0) return;
        RebuildRect rect = rebuildRects[rebuildRectCount];
        rect.x = x;
        rect.z = z;
        rect.w = w;
        rect.h = h;
        rect.isCopy = isCopy;
        rebuildRectCount++;
    }

    private static void rebuildTexture(BlockPos center) {
        // onTickStart가 floodFill 다음 rebuildTexture를 호출하므로 dirty 마킹 완료.
        // 현재 색이 안 정해지면 상태를 바꾸지 않고 미룬다. originSet 커밋 후 리턴하면 재시도가
        // 끊겨 칠이 안 된 origin이 확정되므로 최상단에서 막아야 한다.
        if (!MCRiderMinimap.isDebugColors() && FrontierSearch.activeColor == NO_ID) {
            return;
        }

        TextureBuffer target = front.originSet ? back : front;
        target.ensure();

        int newOriginX = center.getX() - TEX_SIZE / 2;
        int newOriginZ = center.getZ() - TEX_SIZE / 2;

        // 스크롤 재사용 조건: 소스는 front(최신), 겹침 영역 존재, 디버그 모드 아님.
        // 주의! dirty 마킹 불변식: paintCell/addEdge/absorbInto/activeColor 변경이 모두 거쳐야 함.
        boolean canScroll = !MCRiderMinimap.isDebugColors()
                && target != front
                && front.originSet
                && Math.abs(newOriginX - front.originX) < TEX_SIZE
                && Math.abs(newOriginZ - front.originZ) < TEX_SIZE;

        target.originX = newOriginX;
        target.originZ = newOriginZ;
        target.originSet = true;

        rebuildRectCount = 0;

        if (canScroll) {
            int dx = newOriginX - front.originX;
            int dz = newOriginZ - front.originZ;
            int overlapW = TEX_SIZE - Math.abs(dx);
            int overlapH = TEX_SIZE - Math.abs(dz);
            int destX0 = Math.max(0, -dx);
            int destZ0 = Math.max(0, -dz);

            // 복사 사각형 등록 및 오프셋 저장
            addRebuildRect(destX0, destZ0, overlapW, overlapH, true);
            rebuildRects[rebuildRectCount - 1].copyDx = dx;
            rebuildRects[rebuildRectCount - 1].copyDz = dz;

            // 겹치지 않는 부분만 L자(세로 띠 + 가로 띠)로 정확히, 중복 없이 나눠 계산 대상에 넣는다.
            if (dx > 0) {
                addRebuildRect(TEX_SIZE - dx, 0, dx, TEX_SIZE, false);   // +x로 이동 -> 오른쪽에 새 컬럼 노출
            } else if (dx < 0) {
                addRebuildRect(0, 0, -dx, TEX_SIZE, false);              // -x로 이동 -> 왼쪽에 새 컬럼 노출
            }
            if (dz > 0) {
                addRebuildRect(destX0, TEX_SIZE - dz, overlapW, dz, false);
            } else if (dz < 0) {
                addRebuildRect(destX0, 0, overlapW, -dz, false);
            }
        } else {
            // 겹침이 전혀 없는 경우(순간이동 등)만 여기로 온다 — 어쩔 수 없이 전체를 새로 계산한다.
            target.image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
            addRebuildRect(0, 0, TEX_SIZE, TEX_SIZE, false);
        }

        if (target == front) {
            target.markAllDirty();
            FrontierSearch.clearDirtyColumns();
        }

        rebuildTarget = target;
        rebuildRectCursor = 0;
        rebuildRectLocalIndex = 0;
        rebuildInProgress = true;
    }

    private static void continueRebuildIfInProgress() {
        if (!rebuildInProgress) return;
        final long deadline = System.nanoTime() + REPAINT_TIME_BUDGET_NANOS;
        int budget = REPAINT_HARD_CAP_PER_TICK;
        int sinceTimeCheck = 0;

        while (budget > 0 && rebuildRectCursor < rebuildRectCount) {
            RebuildRect rect = rebuildRects[rebuildRectCursor];
            int w = rect.w;
            int total = w * rect.h;

            while (rebuildRectLocalIndex < total && budget > 0) {
                int lx = rebuildRectLocalIndex % w;
                int lz = rebuildRectLocalIndex / w;
                if (rect.isCopy) {
                    int tx = rect.x + lx;
                    int tz = rect.z + lz;
                    // 안전성: onTickStart 순서가 floodFill(dirty마킹) → rebuildTexture이므로 stale 아님.
                    // 재빌드 중 dirty는 mirrorToBack으로 cover. repaintDirtyColumns가 범위 필터링.
                    int argb = front.image.getColorArgb(tx + rect.copyDx, tz + rect.copyDz);
                    rebuildTarget.image.setColorArgb(tx, tz, argb);
                } else {
                    rebuildTarget.plotColumn(rebuildTarget.originX + rect.x + lx, rebuildTarget.originZ + rect.z + lz);
                }
                rebuildRectLocalIndex++;
                budget--;
                if ((++sinceTimeCheck & 0xFF) == 0 && System.nanoTime() >= deadline) return; // 다음 틱에 이어서
            }
            if (rebuildRectLocalIndex >= total) {
                rebuildRectCursor++;
                rebuildRectLocalIndex = 0;
            }
        }

        if (rebuildRectCursor >= rebuildRectCount) {
            rebuildInProgress = false;
            rebuildTarget.markAllDirty();
            if (rebuildTarget == back) {
                swapBuffers();
            }
            rebuildTarget = null;
            rebuildRectCount = 0;
            rebuildRectCursor = 0;
            rebuildRectLocalIndex = 0;
        }
    }

    private static void swapBuffers() {
        TextureBuffer tmp = front;
        front = back;
        back = tmp;
    }

    // 재앵커가 필요하면 텍스처를 재생성한다. 이미 재빌드 중이면 새로 트리거하지 않는다
    // front 원점은 스왑 전까지 안 바뀌므로 안 그러면 플레이어가 계속 움직이는 동안 매 틱 재트리거되어 영원히 안 끝난다.
    static void ensureOriginFor(BlockPos start) {
        front.ensure();
        if (!rebuildInProgress) {
            if (!front.originSet) {
                rebuildTexture(start);
            } else {
                int cx = start.getX() - (front.originX + TEX_SIZE / 2);
                int cz = start.getZ() - (front.originZ + TEX_SIZE / 2);
                if (Math.abs(cx) > TEX_SIZE / 2 - REANCHOR_MARGIN
                        || Math.abs(cz) > TEX_SIZE / 2 - REANCHOR_MARGIN) {
                    rebuildTexture(start);
                }
            }
        }
        continueRebuildIfInProgress();
    }

    private static boolean isInCurrentView(int worldX, int worldZ, int px, int pz) {
        double dx = worldX - px;
        double dz = worldZ - pz;
        double r = maxDist * SQRT2 + 8;
        return dx * dx + dz * dz <= r * r;
    }
    static void repaintDirtyColumns(BlockPos start) {
        if (FrontierSearch.dirtyColumns.isEmpty() || !front.originSet) return;
        boolean mirrorToBack = rebuildInProgress && rebuildTarget == back;

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
                front.plotColumn(wx, wz);
                if (mirrorToBack) back.plotColumn(wx, wz);
                dirtyIt.remove();
                hardCap--;
            }
            if ((++sinceTimeCheck & 0xFF) == 0 && System.nanoTime() >= repaintDeadline) {
                timedOut = true;
            }
        }

        if (!timedOut) {
            dirtyIt = FrontierSearch.dirtyColumns.iterator();
            while (dirtyIt.hasNext() && hardCap > 0) {
                long key = dirtyIt.nextLong();
                int wx = FrontierSearch.unpackColumnX(key), wz = FrontierSearch.unpackColumnZ(key);
                front.plotColumn(wx, wz);
                if (mirrorToBack) back.plotColumn(wx, wz);
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
        IntIterator yi = ys.iterator();
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
        if (FrontierSearch.visitedColumns.isEmpty() || !front.originSet) return;
        if (!MCRiderMain.isPlayingInGame()) return;

        MinecraftClient client = MCRiderMinimap.client;

        front.ensure();
        if (front.textureDirty) {
            front.uploadDirtyRegion();
            front.textureDirty = false;
        }

        final double sizeFactor = getSizeFactor(client);
        final int screenWidth = client.getWindow().getScaledWidth();
        final int screenHeight = client.getWindow().getScaledHeight();
        final double scaledPadding = padding * sizeFactor;
        final double scaledRadius = radius * sizeFactor;

        // 미니맵 위치 옵션에 따른 렌더링
        final int position = MCRiderConfig.INSTANCE.useMinimap;
        final boolean isRight = position == 2 || position == 4 || position == 6;
        final int centerX = (int) Math.round(isRight
                ? screenWidth - scaledPadding - scaledRadius
                : scaledPadding + scaledRadius);
        final int centerY = switch (position) {
            case 3, 4 -> screenHeight / 2;
            case 5, 6 -> (int) Math.round(scaledPadding + scaledRadius);
            default -> (int) Math.round(screenHeight - scaledPadding - scaledRadius);
        };

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
        final float u0 = (float) Math.max(0, Math.min(TEX_SIZE - texRegion, p.x - front.originX - texRegion / 2.0));
        final float v0 = (float) Math.max(0, Math.min(TEX_SIZE - texRegion, p.z - front.originZ - texRegion / 2.0));

        // try/finally로 disableScissor를 보장한다
        // pop이 누락되면 GL scissor가 다음 프레임까지 새어나가 화면 전체(엔티티 포함)가 클리핑된다(실제 발생 이력 있음)
        context.enableScissor(viewX1, viewY1, viewX2, viewY2);
        try {
            MatrixStack matrices = context.getMatrices();
            matrices.push();
            try {
                matrices.translate(centerX, centerY, 0);
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180f - yawDeg));
                matrices.translate(-drawSize / 2f, -drawSize / 2f, 0);
                context.drawTexture(
                        RenderLayer::getGuiTextured, front.id,
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

        final double yawRad = Math.toRadians(yawDeg);
        final double fx = -Math.sin(yawRad);
        final double fz = Math.cos(yawRad);
        final double rx = Math.cos(yawRad);
        final double rz = Math.sin(yawRad);

        // 내 카트 아이콘 회전 보간용
        final float myKartYaw = MCRiderMain.hasTrackedSelfKartYaw()
                ? MCRiderMain.getInterpolatedSelfKartYaw(tickDelta)
                : getKartBodyYaw(MCRiderMain.getRidingPlayer(), tickDelta);
        final float delta = (myKartYaw - yawDeg) + IMAGE_CORRECTION_TRICK;

        // 렌더 순서: 몸체(흰색), 적, 윤곽선. 몸체는 적에게 가려질 수 있지만
        // 윤곽선은 항상 맨 위라 겹쳐도 테두리는 보인다
        drawSelfMarker(context, centerX, centerY, selfIconSize, delta);

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

            final float enemyKartYaw = getKartBodyYaw(other, tickDelta);
            final float relativeYaw = (enemyKartYaw - yawDeg) + IMAGE_CORRECTION_TRICK;
            drawEnemyHead(context, other, dotX, dotY, enemyIconSize, relativeYaw);
        }

        drawSelfMarkerOutlined(context, centerX, centerY, selfIconSize, delta);
    }

    private static float getKartBodyYaw(PlayerEntity player, float tickDelta) {
        Entity kart = player.getRootVehicle();
        if (kart != null && kart != player) {
            for (Entity passenger : kart.getPassengerList()) {
                if (MCRiderMain.hasCertainName(passenger, "mcrider-modelsaddle")) {
                    return passenger.getYaw(tickDelta);
                }
            }
        }
        return player.getYaw(tickDelta);
    }
    private static void drawSelfMarker(DrawContext context, float cx, float cy, float size, float rotationDeg) {
        // try / finally로 pop을 보장한다 — 누락되면 이후 HUD 요소들이 잘못된 위치/회전으로 그려진다
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        try {
            matrices.translate(cx, cy, 0);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotationDeg));
            int roundedSize = Math.round(size);
            matrices.translate(-roundedSize / 2f, -roundedSize / 2f, 0);
            drawArrowIcon(context, size, 0xFFFFFFFF);
        } finally {
            matrices.pop();
        }
    }

    // 윤곽선 전용 텍스쳐
    private static final int SELF_MARKER_RING_PAD = 1;
    private static final int SELF_MARKER_RING_RADIUS = 1;
    private static Identifier selfMarkerRingIcon;
    private static int selfMarkerRingTexSize;
    private static float selfMarkerRingScale;
    private static boolean selfMarkerRingReady = false;

    private static void ensureSelfMarkerRing() {
        if (selfMarkerRingReady) return;
        selfMarkerRingReady = true;
        try (InputStream in = MinimapRenderer.class.getResourceAsStream("/assets/mcrider-official/textures/hud/arrow_icon.png")) {
            if (in == null) return;
            NativeImage src = NativeImage.read(in);
            final int sw = src.getWidth();
            final int sh = src.getHeight();
            final int pad = SELF_MARKER_RING_PAD;
            final int pw = sw + pad * 2;
            final int ph = sh + pad * 2;
            final int r = SELF_MARKER_RING_RADIUS;

            NativeImage ring = new NativeImage(NativeImage.Format.RGBA, pw, ph, false);
            ring.fillRect(0, 0, pw, ph, 0);
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < pw; x++) {
                    if (isOpaque(src, x - pad, y - pad, sw, sh)) {
                        continue;
                    }
                    boolean dilated = false;
                    outer:
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dx = -r; dx <= r; dx++) {
                            if (dx * dx + dy * dy > r * r + 0.5) continue;
                            if (isOpaque(src, x - pad + dx, y - pad + dy, sw, sh)) {
                                dilated = true;
                                break outer;
                            }
                        }
                    }
                    if (dilated) ring.setColorArgb(x, y, 0xFF000000);
                }
            }
            src.close();

            selfMarkerRingTexSize = pw;
            selfMarkerRingScale = (float) pw / sw;
            selfMarkerRingIcon = Identifier.of("mcrider-official", "generated/self_marker_ring");
            NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "mcrider-self-marker-ring", ring);
            MinecraftClient.getInstance().getTextureManager().registerTexture(selfMarkerRingIcon, tex);
        } catch (Exception e) {
            LOGGER.error("[MCRider] 자기 마커 윤곽선 텍스처 생성에 실패했습니다.", e);
        }
    }

    private static boolean isOpaque(NativeImage img, int x, int y, int w, int h) {
        if (x < 0 || x >= w || y < 0 || y >= h) return false;
        return (img.getColorArgb(x, y) >>> 24) > 10;
    }

    private static void drawSelfMarkerOutlined(DrawContext context, float cx, float cy, float size, float rotationDeg) {
        ensureSelfMarkerRing();
        if (selfMarkerRingIcon == null) return;

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        try {
            matrices.translate(cx, cy, 0);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotationDeg));
            final float ringSize = size * selfMarkerRingScale;
            int roundedRingSize = Math.round(ringSize);
            matrices.translate(-roundedRingSize / 2f, -roundedRingSize / 2f, 0);

            context.drawTexture(
                    RenderLayer::getGuiTextured,
                    selfMarkerRingIcon,
                    0, 0,
                    0f, 0f,
                    roundedRingSize, roundedRingSize,
                    selfMarkerRingTexSize, selfMarkerRingTexSize,
                    selfMarkerRingTexSize, selfMarkerRingTexSize,
                    0xFFFFFFFF
            );
        } finally {
            matrices.pop();
        }
    }

    private static void drawArrowIcon(DrawContext context, float size, int color) {
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

            // 중심 기준으로 살짝 확대해, 삐져나오는 폭이 정수 1이 아니라
            // ENEMY_HEAD_OUTLINE_THICKNESS만큼만 되게 한다.
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
        front.close();
        back.close();
        rebuildInProgress = false;
        rebuildTarget = null;
        rebuildRectCount = 0;
        rebuildRectCursor = 0;
        rebuildRectLocalIndex = 0;
    }
}