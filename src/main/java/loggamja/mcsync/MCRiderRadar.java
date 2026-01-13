package loggamja.mcsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

public class MCRiderRadar implements ModInitializer {
    final int padding = 10;
    final int baseRadius = 50;
    final double baseDist = 25.0;

    final double uiScale = 0.75;
    final double distScale = 0.75;

    final int radius = (int) Math.round(baseRadius * uiScale);
    final double maxDist = baseDist * distScale;

    @Override
    public void onInitialize() {
        HudRenderCallback.EVENT.register((context, context2) -> renderRadar(context, context2.getTickDelta(false)));
        //HudRenderCallback.EVENT.register((context, context2) -> renderRadar(context, context2.getTickProgress(false)));
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

    private void renderRadarMode1(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!MCRiderMain.isPlayingInGame()) return;

        final int screenWidth = client.getWindow().getScaledWidth();
        final int screenHeight = client.getWindow().getScaledHeight();

        final int centerX = padding + radius;
        final int centerY = screenHeight - padding - radius;

        // 반투명 검은색 배경
        context.fill(centerX - radius, centerY - radius,
                centerX + radius, centerY + radius,
                0x88000000);

        // 플레이어 yaw 기반 방향 벡터
        final float yawDeg = client.gameRenderer.getCamera().getYaw();
        final double yawRad = Math.toRadians(yawDeg);

        final double fx = -Math.sin(yawRad);
        final double fz =  Math.cos(yawRad);
        final double rx =  Math.cos(yawRad);
        final double rz =  Math.sin(yawRad);

        final Vec3d p = MCRiderMain.getRidingPlayer().getCameraPosVec(tickDelta);

        // 내 위치 (녹색 점)
        context.fill(centerX - 2, centerY - 2, centerX + 2, centerY + 2, 0xFFFFFFFF);

        for (PlayerEntity other : Objects.requireNonNull(client.world).getPlayers()) {
            if (other == MCRiderMain.getRidingPlayer()) continue;
            if (other == other.getRootVehicle()) continue;

            final Vec3d q = other.getCameraPosVec(tickDelta);
            final double dx = q.x - p.x;
            final double dz = q.z - p.z;

            final double dist = Math.sqrt(dx*dx + dz*dz);
            if (dist > maxDist) continue;

            final double localForward = dx * fx + dz * fz;
            final double localRight   = dx * rx + dz * rz;

            final double scale = radius / maxDist;
            final int dotX = centerX - (int)Math.round(localRight * scale);
            final int dotY = centerY - (int)Math.round(localForward * scale);

            context.fill(dotX - 1, dotY - 1, dotX + 1, dotY + 1, 0xFF00FF00);
        }
    }
    private void renderRadarMode2(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!MCRiderMain.isPlayingInGame()) return;

        final int screenWidth = client.getWindow().getScaledWidth();
        final int screenHeight = client.getWindow().getScaledHeight();

        final int centerX = screenWidth / 2;
        final int centerY = screenHeight / 2;

        final double resolutionScale = client.getWindow().getHeight() / 1080.0;
        double scaleMultiplier = (4 / client.getWindow().getScaleFactor());

        final double arcRadius = 46 * scaleMultiplier * resolutionScale;

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

            final double dist = Math.sqrt(dx*dx + dz*dz);
            if (!(3 < dist && dist < (maxDist + 3))) continue;

            final double dotProduct = dx * fx + dz * fz;
            if (dotProduct > 0) continue;

            final double angle = Math.atan2(dz, dx) - yawRad;

            final int dotX = centerX - (int)(Math.cos(angle) * arcRadius);
            final int dotY = centerY - (int)(Math.sin(angle) * arcRadius);

            final double minAlpha = 0;
            int a = (int)((1.0 - (dist / (maxDist + 3)) * (1.0 - minAlpha)) * 255);
            int color = (a << 24) | 0xFF0000;

            int dotSize = Math.max(1, (int)(2 * scaleMultiplier));
            context.fill(dotX - dotSize, dotY - dotSize, dotX + dotSize, dotY + dotSize, color);
        }
    }
}