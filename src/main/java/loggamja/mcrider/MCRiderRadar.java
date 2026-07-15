package loggamja.mcrider;

import loggamja.mcrider.option.MCRiderConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MCRiderRadar implements ClientModInitializer {
    private static final double GUI_SCALE_BASIS = 4.0;

    final double baseDist = 25.0;
    final double distScale = 0.75;
    final double maxDist = baseDist * distScale;

    private static final Identifier ARROW_ICON =
            Identifier.of("mcrider-official", "textures/hud/arrow_icon.png");

    private static final float IMAGE_CORRECTION_TRICK = 0.001f;
    private static final int TEX_SIZE = 16;
    private static final float ARROW_SIZE = (float) (10f * GUI_SCALE_BASIS);
    private static final double ARC_RADIUS_BASE = 46.0 * GUI_SCALE_BASIS;

    private static final int ENEMY_ARROW_RGB = 0xFF0000;
    private static final int SHADOW_ARROW_RGB = 0x808080;   // 고스트는 미니맵과 같은 회색

    public static final String SHADOW_ENTITY_NAME = "mcrider-shadow";
    private static final List<Entity> shadowEntities = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        HudLayerRegistrationCallback.EVENT.register(layeredDrawer ->
                layeredDrawer.attachLayerAfter(IdentifiedLayer.EXPERIENCE_LEVEL,
                        Identifier.of("mcrider-official", "radar_hud"),
                        (context, tickCounter) -> renderRadar(context, tickCounter.getTickProgress(false))));

        ClientTickEvents.END_CLIENT_TICK.register(client -> collectShadowEntities(client));
    }
    private static void collectShadowEntities(MinecraftClient client) {
        shadowEntities.clear();
        if (client.world == null) return;
        if (MCRiderConfig.INSTANCE.MCRiderRadarOption != 1 && MCRiderConfig.INSTANCE.useMinimap == 0) return;

        for (Entity entity : client.world.getEntities()) {
            if (entity.isRemoved()) continue;
            if (!(entity instanceof DisplayEntity)) continue;
            if (!MCRiderMain.hasCertainName(entity, SHADOW_ENTITY_NAME)) continue;

            shadowEntities.add(entity);
        }
    }
    public static List<Entity> getShadowEntities() {
        return shadowEntities;
    }
    private void renderRadar(DrawContext context, float tickDelta) {
        if (!MCRiderMain.isRidingKart) return;
        if (MCRiderConfig.INSTANCE.MCRiderRadarOption != 1) return;

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

        // 적
        for (PlayerEntity other : Objects.requireNonNull(client.world).getPlayers()) {
            if (other == MCRiderMain.getRidingPlayer()) continue;
            if (other == other.getRootVehicle()) continue;

            drawArrowIfBehind(context, other.getCameraPosVec(tickDelta), p, fx, fz, yawRad,
                    centerX, centerY, arcRadius, arrowSize, ENEMY_ARROW_RGB);
        }

        // 고스트
        for (Entity entity : getShadowEntities()) {
            drawArrowIfBehind(context, entity.getLerpedPos(tickDelta), p, fx, fz, yawRad,
                    centerX, centerY, arcRadius, arrowSize, SHADOW_ARROW_RGB);
        }
    }
    private void drawArrowIfBehind(DrawContext context, Vec3d q, Vec3d p, double fx, double fz, double yawRad,
                                   int centerX, int centerY, double arcRadius, float arrowSize, int rgb) {
        final double dx = q.x - p.x;
        final double dz = q.z - p.z;

        final double dist = Math.sqrt(dx * dx + dz * dz);
        if (!(3 < dist && dist < (maxDist + 3))) return;

        final double dotProduct = dx * fx + dz * fz;
        if (dotProduct > 0) return;

        final double angle = Math.atan2(dz, dx) - yawRad;

        final float arrowX = (float)(centerX - Math.cos(angle) * arcRadius);
        final float arrowY = (float)(centerY - Math.sin(angle) * arcRadius);

        final double minAlpha = 0;
        int a = (int)((1.0 - (dist / (maxDist + 3)) * (1.0 - minAlpha)) * 255);

        // angle은 좌표계 기준이므로 degrees로 변환, -90도 보정(텍스처 위쪽=앞)
        final float arrowRotDeg = (float)Math.toDegrees(angle) - 90f + IMAGE_CORRECTION_TRICK;

        drawArrowIcon(context, arrowX, arrowY, arrowSize, arrowRotDeg, a, rgb);
    }
    private double getSizeFactor(MinecraftClient client) {
        final double physicalScale = client.getWindow().getHeight() / 1080.0;
        final double scaleFactor = client.getWindow().getScaleFactor();
        return physicalScale / scaleFactor;
    }
    private void drawArrowIcon(DrawContext context, float cx, float cy,
                               float size, float rotationDeg, int alpha, int rgb) {
        MatrixStack matrices = context.getMatrices();
        matrices.push();

        matrices.translate(cx, cy, 0);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotationDeg));
        matrices.translate(-size / 2f, -size / 2f, 0);

        int color = (alpha << 24) | rgb;

        context.drawTexture(
                RenderLayer::getGuiTextured,
                ARROW_ICON,
                0, 0,
                0f, 0f,
                Math.round(size), Math.round(size),
                TEX_SIZE, TEX_SIZE,
                TEX_SIZE, TEX_SIZE,
                color
        );
        matrices.pop();
    }
}