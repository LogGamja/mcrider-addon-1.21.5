package loggamja.mcsync.mixin;

import loggamja.mcsync.MCRiderCamera;
import loggamja.mcsync.MCRiderMain;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.text.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    @Shadow
    private ClientWorld world;

    @Unique
    private static final Identifier DEBUG_MODIFIER_ID = Identifier.of("kfckartriderpack", "debug");

    @Unique
    private float lastEntityYaw = Float.NaN;

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

    @Inject(method = "onEntityAttributes", at = @At("HEAD"))
    private void mcsync$onEntityAttributes(EntityAttributesS2CPacket packet, CallbackInfo ci) {
        if (Thread.currentThread().getName().contains("Render")) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (packet.getEntityId() != mc.player.getId()) return;

        if (!MCRiderMain.isRidingKart) return;

        long now = System.nanoTime();

        for (EntityAttributesS2CPacket.Entry entry : packet.getEntries()) {
            var attr = entry.attribute();
            if (attr.getKey().isEmpty()) continue;
            if (!attr.getKey().get().getValue().toString().contains("explosion_knockback_resistance")) continue;

            for (var modifier : entry.modifiers()) {
                if (!modifier.id().equals(DEBUG_MODIFIER_ID)) continue;

                float entityYaw = (float) modifier.value();

                if (entityYaw == lastEntityYaw) return;
                lastEntityYaw = entityYaw;

                ArrayDeque<MCRiderMain.YawEntry> history = MCRiderMain.playerYawHistory;
                MCRiderMain.YawEntry best = null;
                float bestDiff = Float.MAX_VALUE;
                int maxDistance = 500;

                int distance = 0;
                Iterator<MCRiderMain.YawEntry> it = history.descendingIterator();
                while (it.hasNext() && distance <= maxDistance) {
                    MCRiderMain.YawEntry histEntry = it.next();

                    float diff = Math.abs(MathHelper.wrapDegrees(histEntry.yaw() - entityYaw));
                    if (diff > 180f) diff = 360f - diff;

                    if (diff <= bestDiff) {
                        bestDiff = diff;
                        best = histEntry;
                    }
                    distance++;
                }

                if (best == null) return;

                double diffMs = (now - best.nanoTime()) / 1_000_000.0;
                MCRiderMain.LOGGER.info(
                        "[SyncTracker] 플레이어 yaw → 어트리뷰트 패킷 수신: {} ms (yaw={}, entityYaw={}, diff={})",
                        String.format("%.3f", diffMs),
                        String.format("%.4f", best.yaw()),
                        String.format("%.4f", entityYaw),
                        String.format("%.4f", bestDiff)
                );
                return;
            }
        }
    }
}