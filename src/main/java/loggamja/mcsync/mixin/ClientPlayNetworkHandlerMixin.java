package loggamja.mcsync.mixin;

import loggamja.mcsync.MCRiderCamera;
import loggamja.mcsync.MCRiderMain;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.text.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    @Shadow
    private ClientWorld world;

    @Inject(method = "onOverlayMessage", at = @At("HEAD"))
    private void onHandleGameMessage(OverlayMessageS2CPacket packet, CallbackInfo ci) {
        if (!MCRiderMain.isRidingKart || !MCRiderMain.isPlayingInGame()) return;

        Text message = packet.text();
        var speed = extractSpeed(message.getString());

        if (MCRiderMain.currentSaddleType.equals("1.0")) {
            speed *= 2.59065f;
        }

        MCRiderCamera.actionbarSpeed = speed;
        if (speed == -169f) MCRiderCamera.timeAfterLastActionbar = 20;
        else MCRiderCamera.timeAfterLastActionbar = 0;
    }

    @Unique
    float extractSpeed(String text) {
        Pattern p = Pattern.compile("(\\d+(?:\\.\\d+)?)km/h");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return Math.max(Float.parseFloat(m.group(1)), 0);
        }
        return -169f;
    }

    private float prevEntityYaw = Float.NaN;

    @Inject(method = "onEntity", at = @At("HEAD"))
    private void mcsync$onEntityPacket(EntityS2CPacket packet, CallbackInfo ci) {
        if (Thread.currentThread().getName().contains("Render")) return;

        if (MCRiderMain.trackedEntityId == -1) return;
        if (packet.getEntity(world).getId() != MCRiderMain.trackedEntityId) return;

        float entityYaw = packet.getYaw();
        long now = System.nanoTime();

        boolean thisPacketMoved = !Float.isNaN(prevEntityYaw) && entityYaw != prevEntityYaw;

        if (thisPacketMoved) {
            if (MCRiderMain.entityWasStopped) {
                // 정지 → 이동 전환: 즉시 측정
                if (!MCRiderMain.playerWasStopped) {
                    long playerYawAt = MCRiderMain.lastPlayerYawChangedAt;
                    if (playerYawAt != -1L) {
                        double diffMs = (now - playerYawAt) / 1_000_000.0;
                        MCRiderMain.LOGGER.info(
                                "[SyncTracker] 플레이어 yaw 변화 → 엔티티 패킷 수신: {} ms",
                                String.format("%.3f", diffMs)
                        );
                    }
                }
                MCRiderMain.entityWasStopped = false;
            }
            MCRiderMain.lastEntityMovedAt = now;
        }

        prevEntityYaw = entityYaw;
    }
}