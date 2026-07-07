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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import loggamja.mcrider.option.MCRiderConfig;
import loggamja.mcrider.MCRiderMain;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static loggamja.mcrider.minimap.ColorGraph.NO_ID;

// 미니맵 텍스처 관리(북쪽 고정, 부분 업로드), 화면 그리기, 셀->색상 계산 담당
// FrontierSearch가 채운 visitedColumns/cellColor/activeColor/activeSet을
// 읽기만 한다(디버그 모드의 즉시 도색 경로에서만 FrontierSearch가 이 클래스를 역호출한다)
final class MinimapRenderer {
    private MinimapRenderer() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("mcrider");

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
    private static final int VISITED_COLOR = 0xBBCCCCCC;

    private static final float IMAGE_CORRECTION_TRICK = 0.001f;
    private static final double RIDER_ICON_SIZE = 6 * LEGACY_GUI_SCALE_BASIS;
    private static final Identifier SELF_ARROW_ICON = Identifier.of("mcrider-official", "textures/hud/arrow_icon.png");
    private static final int SELF_ARROW_TEX_SIZE = 16;
    private static final float ENEMY_ICON_SCALE = 0.5f;

    private static final float ENEMY_HEAD_OUTLINE_THICKNESS = 0.4f;
    private static final int ENEMY_HEAD_OUTLINE_COLOR = 0xFF000000;

    // 텍스처 버퍼. front는 화면에 보이는 텍스처, back은 재앵커 시 뒤에서 새로 준비하는 텍스처다
    // 다 채워지면 swapBuffers로 참조만 바꿔치기해 깜빡임/찢어짐 없이 반영한다
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

        // dirty 영역만 NativeImage에서 GPU 텍스처로 복사한다(전체 갱신이면 한 번에, 아니면 타일별로)
        void uploadDirtyRegion() {
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

        void plotColumn(int worldX, int worldZ) {
            if (image == null || !originSet) return;
            int tx = worldX - originX;
            int tz = worldZ - originZ;
            if (tx < 0 || tx >= TEX_SIZE || tz < 0 || tz >= TEX_SIZE) return;
            image.setColorArgb(tx, tz, computeColumnColor(worldX, worldZ));
            markPixelDirty(tx, tz);
        }

        void reset() {
            if (image != null) {
                image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);
                markAllDirty();
            }
            originSet = false;
        }
    }

    private static final Identifier MINIMAP_ID_A = Identifier.of("mcrider-official", "minimap_a");
    private static final Identifier MINIMAP_ID_B = Identifier.of("mcrider-official", "minimap_b");
    private static TextureBuffer front = new TextureBuffer(MINIMAP_ID_A);
    private static TextureBuffer back = new TextureBuffer(MINIMAP_ID_B);

    // 바운딩 박스 대신 타일 단위 dirty 집합을 쓴다 — 멀리 떨어진 dirty 두 덩어리를 하나의
    // 사각형으로 감싸면 사실상 풀업로드가 되기 때문
    private static final int TILE_SIZE = 32;
    private static final int TILES_PER_ROW = TEX_SIZE / TILE_SIZE;

    private static int tileKey(int tileX, int tileZ) {
        return tileX * TILES_PER_ROW + tileZ;
    }

    // 재도색 예산
    // 재도색 시간 예산. 컬럼 하나는 저렴하니 개수 대신 시간으로 제한한다
    // 평소엔 안에서 끝나고 대량 dirty가 몰릴 때만 여러 틱에 나눠 그려진다
    private static final long REPAINT_TIME_BUDGET_NANOS = 2_000_000L; // 2ms (50ms 틱의 4%)
    // 시간 예산과 별개인 개수 안전장치. 컬럼당 비용이 비정상적으로 커져도 한 틱이 무한정
    // 길어지지 않게 한다(평소엔 시간 예산이 먼저 걸려 도달하지 않음)
    private static final int REPAINT_HARD_CAP_PER_TICK = 200_000;

    // 재앵커 시 새 화면을 여러 틱에 걸쳐 준비하는 상태. front가 이미 표시 중이면 back에 준비 후 swapBuffers
    // 처음이면 back 없이 front에 바로 채운다
    private static TextureBuffer rebuildTarget = null;
    private static int rebuildPixelIndex = 0;
    private static boolean rebuildInProgress = false;

    private static void rebuildTexture(BlockPos center) {
        TextureBuffer target = front.originSet ? back : front;
        target.ensure();
        target.originX = center.getX() - TEX_SIZE / 2;
        target.originZ = center.getZ() - TEX_SIZE / 2;
        target.originSet = true;
        target.image.fillRect(0, 0, TEX_SIZE, TEX_SIZE, 0);

        if (target == front) {
            // 화면에 아직 아무것도 안 보였으므로 지운 상태를 바로 반영해도 문제없다
            target.markAllDirty();
            FrontierSearch.dirtyColumns.clear();
        }
        // target이 back이면 dirtyColumns를 비우지 않는다 — 스왑 후 남은 dirty는 새 front가
        // 된 그 버퍼에 repaintDirtyColumns가 이어서 반영해야 하기 때문

        rebuildTarget = target;
        rebuildPixelIndex = 0;
        rebuildInProgress = true;
    }

    // rebuildTexture가 시작한 재빌드를 예산만큼 이어 그린다(repaintDirtyColumns와 예산 공유)
    // 텍스처 크기에만 비례하는 고정 비용이라 트랙 크기와 무관하다. target이 back이면 완료 시 swap한다
    private static void continueRebuildIfInProgress() {
        if (!rebuildInProgress) return;
        final long deadline = System.nanoTime() + REPAINT_TIME_BUDGET_NANOS;
        int budget = REPAINT_HARD_CAP_PER_TICK;
        int sinceTimeCheck = 0;
        final int total = TEX_SIZE * TEX_SIZE;
        while (rebuildPixelIndex < total && budget > 0) {
            int px = rebuildPixelIndex % TEX_SIZE;
            int pz = rebuildPixelIndex / TEX_SIZE;
            rebuildTarget.plotColumn(rebuildTarget.originX + px, rebuildTarget.originZ + pz);
            rebuildPixelIndex++;
            budget--;
            if ((++sinceTimeCheck & 0xFF) == 0 && System.nanoTime() >= deadline) break;
        }
        if (rebuildPixelIndex >= total) {
            rebuildInProgress = false;
            // 빌드 중엔 타일 단위로만 dirty 표시됐으므로, 완료 시 markAllDirty로 갈아치워
            // 스왑 직후 업로드가 한 번의 통짜 업로드로 끝나게 한다
            rebuildTarget.markAllDirty();
            if (rebuildTarget == back) {
                swapBuffers();
            }
            rebuildTarget = null;
            rebuildPixelIndex = 0;
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

    static void plotColumn(int worldX, int worldZ) {
        front.plotColumn(worldX, worldZ);
    }
    private static boolean isInCurrentView(int worldX, int worldZ, int px, int pz) {
        double dx = worldX - px;
        double dz = worldZ - pz;
        double r = maxDist * SQRT2 + 8;
        return dx * dx + dz * dz <= r * r;
    }
    static void repaintDirtyColumns(BlockPos start) {
        if (FrontierSearch.dirtyColumns.isEmpty() || !front.originSet) return;
        // back이 재빌드 중이면 같은 변경을 back에도 반영한다 — 안 그러면 스왑 후 그 사이
        // 칠해진 컬럼이 back에서 누락된다.
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
            // nanoTime() 호출도 공짜가 아니므로 대용량 백로그 스캔 시 256개마다만 확인한다
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
        if (FrontierSearch.visitedColumns.isEmpty() || !front.originSet) return;
        if (!MCRiderMain.isPlayingInGame()) return;

        MinecraftClient client = MCRiderMinimap.client;

        front.ensure();
        if (front.textureDirty) {
            front.uploadDirtyRegion();
            front.textureDirty = false;
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
        final float u0 = (float) (p.x - front.originX - texRegion / 2.0);
        final float v0 = (float) (p.z - front.originZ - texRegion / 2.0);

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

        final float myKartYaw = getKartBodyYaw(MCRiderMain.getRidingPlayer());
        final float delta = (myKartYaw - yawDeg) + IMAGE_CORRECTION_TRICK;

        // 렌더 순서: 몸체(흰색) -> 적 -> 윤곽선. 몸체는 적에게 가려질 수 있지만, 윤곽선은
        // 항상 맨 위라 겹쳐도 테두리는 보인다.
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

            final float enemyKartYaw = getKartBodyYaw(other);
            final float relativeYaw = (enemyKartYaw - yawDeg) + IMAGE_CORRECTION_TRICK;
            drawEnemyHead(context, other, dotX, dotY, enemyIconSize, relativeYaw);
        }

        drawSelfMarkerOutlined(context, centerX, centerY, selfIconSize, delta);
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
    private static void drawSelfMarker(DrawContext context, float cx, float cy, float size, float rotationDeg) {
        // try/finally로 pop을 보장한다 — 누락되면 이후 HUD 요소들이 잘못된 위치/회전으로 그려진다.
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        try {
            matrices.translate(cx, cy, 0);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotationDeg));
            matrices.translate(-size / 2f, -size / 2f, 0);
            drawArrowIcon(context, size, 0xFFFFFFFF);
        } finally {
            matrices.pop();
        }
    }

    // 윤곽선 전용 텍스처: 원본 아이콘을 그대로 확대해 얹으면 안쪽까지 덮여 몸체가 적 위로
    // 올라오므로, 테두리만 불투명하고 안쪽은 투명한 별도 텍스처가 필요하다. arrow_icon.png의
    // 알파를 팽창시킨 뒤 원본 영역을 빼서 첫 사용 시 코드로 생성한다.
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
            // NativeImage는 새로 할당해도 메모리가 0으로 안 채워진다 — 전체를 투명으로 먼저
            // 채우지 않으면 잔여 메모리가 잡음으로 보인다.
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
        } catch (IOException e) {
            // 렌더 경로에서 예외를 던지면 렌더 스레드가 죽는다 — 로그만 남기고 null로 남겨
            // 호출부(drawSelfMarkerOutlined)가 조용히 스킵하게 한다.
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
            matrices.translate(-ringSize / 2f, -ringSize / 2f, 0);

            context.drawTexture(
                    RenderLayer::getGuiTextured,
                    selfMarkerRingIcon,
                    0, 0,
                    0f, 0f,
                    Math.round(ringSize), Math.round(ringSize),
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
        front.reset();
        back.reset();
        rebuildInProgress = false;
        rebuildTarget = null;
        rebuildPixelIndex = 0;
    }
}
