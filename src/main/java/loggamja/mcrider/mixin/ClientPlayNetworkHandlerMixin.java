package loggamja.mcrider.mixin;

import loggamja.mcrider.MCRiderCamera;
import loggamja.mcrider.MCRiderMain;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;

import net.minecraft.text.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    @Unique
    private long mcrider$lastSpeedSuccessTime = Long.MIN_VALUE;

    @Inject(method = "onOverlayMessage", at = @At("HEAD"))
    private void mcrider$onHandleGameMessage(OverlayMessageS2CPacket packet, CallbackInfo ci) {
        if (!MCRiderMain.isRidingKart || !MCRiderMain.isPlayingInGame()) return;

        Text message = packet.text();
        var speed = mcrider$extractSpeed(message.getString());
        boolean matched = speed != -169f;

        // 배율은 반드시 "파싱 성공"으로 확인된 값에만 적용한다. 실패 sentinel(-169)에도
        // 곱해버리면 값이 바뀌어(-169가 아니게 돼) 아래 매칭 판정을 영원히 통과 못 하는
        // 문제가 있었다.
        if (matched && MCRiderMain.kartEngine == 7) {
            speed *= 2.59065f;
        }

        var world = MinecraftClient.getInstance().world;
        long worldTime = (world != null) ? world.getTime() : mcrider$lastSpeedSuccessTime;

        if (matched) {
            MCRiderCamera.actionbarSpeed = speed;
            MCRiderCamera.timeAfterLastActionbar = 0;
            mcrider$lastSpeedSuccessTime = worldTime;
        } else if (worldTime != mcrider$lastSpeedSuccessTime) {
            MCRiderCamera.actionbarSpeed = speed;
            MCRiderCamera.timeAfterLastActionbar = 20;
        }
    }
    @Unique
    private static final Pattern mcrider$SPEED_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)km/h");

    @Unique
    float mcrider$extractSpeed(String text) {
        Matcher m = mcrider$SPEED_PATTERN.matcher(text);
        if (m.find()) {
            return Math.max(Float.parseFloat(m.group(1)), 0);
        }
        return -169f; // 매칭 실패 시 -169 반환
    }
}
