package loggamja.mcrider;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

public class MCRiderRadar implements ClientModInitializer {
    // 원본 코드가 "GUI 배율 4" 환경에서 보기 좋게 튜닝되어 있었으므로,
    // 그 시각적 크기를 기준(1920x1080, 배율무관 기준값)으로 그대로 가져오기 위한 보정 배수.
    private static final double LEGACY_GUI_SCALE_BASIS = 4.0;

    final int padding = (int) Math.round(10 * LEGACY_GUI_SCALE_BASIS);
    final int baseRadius = (int) Math.round(50 * LEGACY_GUI_SCALE_BASIS);
    final double baseDist = 25.0;

    final double uiScale = 0.75;
    final double distScale = 0.75;

    final int radius = (int) Math.round(baseRadius * uiScale);
    final double maxDist = baseDist * distScale;

    private static final Identifier KART_ICON =
            Identifier.of("mcrider-official", "textures/hud/kart_icon.png");
    private static final Identifier KART_ICON_ENEMY =
            Identifier.of("mcrider-official", "textures/hud/kart_icon_enemy.png");

    private static final double KART_WIDTH  = 1.4;
    private static final double KART_HEIGHT = 1.7;

    private static final double KART_ICON_DISPLAY_SCALE = 2;
    private static final float IMAGE_CORRECTION_TRICK  = 0.001f;

    private static final Identifier ARROW_ICON =
            Identifier.of("mcrider-official", "textures/hud/arrow_icon.png");

    private static final int TEX_SIZE = 16;
    private static final float ARROW_SIZE = (float) (10f * LEGACY_GUI_SCALE_BASIS);
    private static final double ARC_RADIUS_BASE = 46.0 * LEGACY_GUI_SCALE_BASIS;


    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register((context, context2) -> renderRadar(context, context2.getTickProgress(false)));
    }

    private void renderRadar(DrawContext context, float tickDelta) {
        if (!MCRiderMain.isRidingKart) return;

        if (MCRiderConfig.INSTANCE.MCRiderRadarOption == 1)
            renderRadarMode2(context, tickDelta);
        else if (MCRiderConfig.INSTANCE.MCRiderRadarOption == 2)
            renderRadarMode1(context, tickDelta);
        else if (MCRiderConfig.INSTANCE.MCRiderRadarOption == 3) {
            renderRadarMode2(context, tickDelta);
            renderRadarMode1(context, tickDelta);
        }
    }

    /**
     * GUI 배율과 물리 해상도에 무관하게, "물리적으로 동일한 화면상 크기"를 만들기 위한 보정값.
     *
     * - physicalScale: 물리적 세로 해상도(window.getHeight())가 1080 기준 몇 배인지.
     *   세로 해상도가 같으면(가로/화면비 무관) 항상 같은 값이 나온다.
     * - scaleFactor: GUI 좌표계(scaledHeight) = 물리 해상도(getHeight()) / scaleFactor 관계이므로,
     *   여기서 나눠주면 "GUI 좌표계에서 그려야 할 크기"가 되어 GUI 배율을 바꿔도
     *   물리적으로 보이는 크기는 동일하게 유지된다.
     *
     * 즉 finalSize(GUI좌표) = baseSize(1920x1080, 배율무관 기준) * sizeFactor
     */
    private double getSizeFactor(MinecraftClient client) {
        final double physicalScale = client.getWindow().getHeight() / 1080.0;
        final double scaleFactor = client.getWindow().getScaleFactor();
        return physicalScale / scaleFactor;
    }

    private void renderRadarMode1(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!MCRiderMain.isPlayingInGame()) return;

        final double sizeFactor = getSizeFactor(client);

        final int screenHeight = client.getWindow().getScaledHeight();

        final double scaledPadding = padding * sizeFactor;
        final double scaledRadius = radius * sizeFactor;

        final int centerX = (int) Math.round(scaledPadding + scaledRadius);
        final int centerY = (int) Math.round(screenHeight - scaledPadding - scaledRadius);

        // 반투명 검은색 배경
        context.fill((int) Math.round(centerX - scaledRadius), (int) Math.round(centerY - scaledRadius),
                (int) Math.round(centerX + scaledRadius), (int) Math.round(centerY + scaledRadius),
                0x88000000);

        // 플레이어 yaw 기반 방향 벡터
        //final float yawDeg = client.gameRenderer.getCamera().getYaw();
        final float yawDeg = MCRiderMain.getRidingPlayer().getYaw(tickDelta);
        final double yawRad = Math.toRadians(yawDeg);

        final double fx = -Math.sin(yawRad);
        final double fz =  Math.cos(yawRad);
        final double rx =  Math.cos(yawRad);
        final double rz =  Math.sin(yawRad);

        final Vec3d p = MCRiderMain.getRidingPlayer().getCameraPosVec(tickDelta);

        final double scale = scaledRadius / maxDist;
        final float kartW = (float) Math.max(2, KART_WIDTH  * scale * KART_ICON_DISPLAY_SCALE);
        final float kartH = (float) Math.max(2, KART_HEIGHT * scale * KART_ICON_DISPLAY_SCALE);

        // 내 카트 (중앙)
        final float myKartYaw = getKartBodyYaw(MCRiderMain.getRidingPlayer());
        final float delta = (myKartYaw - yawDeg) + IMAGE_CORRECTION_TRICK;
        drawKartIcon(context, KART_ICON, centerX, centerY, kartW, kartH, delta);

        for (PlayerEntity other : Objects.requireNonNull(client.world).getPlayers()) {
            if (other == MCRiderMain.getRidingPlayer()) continue;
            if (other == other.getRootVehicle()) continue;

            final Vec3d q = other.getCameraPosVec(tickDelta);
            final double dx = q.x - p.x;
            final double dz = q.z - p.z;

            final double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > maxDist) continue;

            final double localForward = dx * fx + dz * fz;
            final double localRight   = dx * rx + dz * rz;

            final float dotX = (float) (centerX - localRight  * scale);
            final float dotY = (float) (centerY - localForward * scale);

            // 상대방 카트바디의 실제 yaw - 내 카메라 yaw
            final float enemyKartYaw = getKartBodyYaw(other);
            final float relativeYaw = (enemyKartYaw - yawDeg) + 0.01f;

            drawKartIcon(context, KART_ICON_ENEMY, dotX, dotY, kartW, kartH, relativeYaw);
        }
    }

    /**
     * 플레이어가 탄 카트바디의 실제 yaw를 구한다.
     * 카트바디(rootVehicle)의 passenger를 순회해 "mcrider-modelsaddle" 엔티티를 찾고 그 yaw를 반환한다.
     * 찾지 못하면 플레이어 yaw로 폴백한다.
     */
    private float getKartBodyYaw(PlayerEntity player) {
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

    /**
     * 텍스처를 (cx, cy)를 중심으로 rotationDeg만큼 회전해서 그린다.
     *
     * 핵심 1) drawTexture에서 "그릴 크기(width/height)"와 "텍스처에서 잘라올 영역(regionWidth/regionHeight)"은
     *         별개의 인자다. region을 명시하지 않는 오버로드는 width=region으로 간주해서,
     *         작게 그리면 텍스처의 좌상단 일부만 잘려 나온다. → region을 16x16 전체로 명시한다.
     *
     * 핵심 2) 회전 피벗은 MatrixStack을 (cx,cy)로 옮긴 뒤 회전하고,
     *         drawTexture는 (-w/2, -h/2) 중심 오프셋으로 그린다.
     */
    private void drawKartIcon(DrawContext context, Identifier texture, float cx, float cy,
                              float w, float h, float rotationDeg) {
        int iw = Math.round(w);
        int ih = Math.round(h);

        MatrixStack matrices = context.getMatrices();
        matrices.push();

        // 1) 카트 중심으로 이동 (소수 좌표 허용)
        matrices.translate(cx, cy, 0);
        // 2) Z축 기준 회전
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotationDeg));
        // 3) 중심 정렬 오프셋을 행렬에서 실수로 처리
        matrices.translate(-w / 2f, -h / 2f, 0);

        // 4) region(잘라올 영역)을 텍스처 전체(16x16)로 명시 → 축소돼도 전체가 보임
        context.drawTexture(
                RenderLayer::getGuiTextured,
                texture,
                0, 0,               // 위치: 행렬에서 이미 중심 정렬됨
                0f, 0f,             // u, v (텍스처 내 시작점)
                iw, ih,             // 화면에 그릴 크기
                TEX_SIZE, TEX_SIZE, // regionWidth, regionHeight (잘라올 영역 = 전체)
                TEX_SIZE, TEX_SIZE  // textureWidth, textureHeight (원본 크기)
        );

        matrices.pop();
    }

    /**
     * 화살표 텍스처를 (cx, cy) 중심으로 rotationDeg만큼 회전해서 그린다.
     * alpha: 0~255, 거리에 따라 조절
     */
    private void drawArrowIcon(DrawContext context, float cx, float cy,
                               float size, float rotationDeg, int alpha) {
        int isize = Math.round(size);

        MatrixStack matrices = context.getMatrices();
        matrices.push();

        matrices.translate(cx, cy, 0);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotationDeg));
        matrices.translate(-size / 2f, -size / 2f, 0);

        // alpha를 색상값으로 합성 (흰색 * alpha)
        int color = (alpha << 24) | 0xFFFFFF;

        context.drawTexture(
                RenderLayer::getGuiTextured,
                ARROW_ICON,
                0, 0,
                0f, 0f,
                isize, isize,
                TEX_SIZE, TEX_SIZE,
                TEX_SIZE, TEX_SIZE,
                color
        );

        matrices.pop();
    }

    private void renderRadarMode2(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!MCRiderMain.isPlayingInGame()) return;

        final double sizeFactor = getSizeFactor(client);

        final int screenWidth  = client.getWindow().getScaledWidth();
        final int screenHeight = client.getWindow().getScaledHeight();

        final int centerX = screenWidth  / 2;
        final int centerY = screenHeight / 2;

        final double arcRadius = ARC_RADIUS_BASE * sizeFactor;
        final float arrowSize = (float) (ARROW_SIZE * sizeFactor);

        final float yawDeg = client.gameRenderer.getCamera().getYaw();
        final double yawRad = Math.toRadians(yawDeg);

        final double fx = -Math.sin(yawRad);
        final double fz =  Math.cos(yawRad);

        final Vec3d p = MCRiderMain.getRidingPlayer().getCameraPosVec(tickDelta);

        for (PlayerEntity other : Objects.requireNonNull(client.world).getPlayers()) {
            if (other == MCRiderMain.getRidingPlayer()) continue;
            if (other == other.getRootVehicle()) continue;

            final Vec3d q = other.getCameraPosVec(tickDelta);
            final double dx = q.x - p.x;
            final double dz = q.z - p.z;

            final double dist = Math.sqrt(dx * dx + dz * dz);
            if (!(3 < dist && dist < (maxDist + 3))) continue;

            final double dotProduct = dx * fx + dz * fz;
            if (dotProduct > 0) continue;

            final double angle = Math.atan2(dz, dx) - yawRad;

            final float arrowX = (float)(centerX - Math.cos(angle) * arcRadius);
            final float arrowY = (float)(centerY - Math.sin(angle) * arcRadius);

            // 거리에 따라 알파 변화 (가까울수록 불투명)
            final double minAlpha = 0;
            int a = (int)((1.0 - (dist / (maxDist + 3)) * (1.0 - minAlpha)) * 255);

            // 화살표를 상대방 방향으로 회전
            // angle은 좌표계 기준이므로 degrees로 변환, +90도 보정(텍스처 위쪽=앞)
            final float arrowRotDeg = (float)Math.toDegrees(angle) + 90f + IMAGE_CORRECTION_TRICK;

            drawArrowIcon(context, arrowX, arrowY, arrowSize, arrowRotDeg, a);
        }
    }
}