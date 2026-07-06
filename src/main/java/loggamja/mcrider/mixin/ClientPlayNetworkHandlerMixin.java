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
    // 같은 틱 안에 액션바 패킷이 여러 개 도착할 수 있는데(속도 패킷 + 무관한 패킷 등),
    // 그중 하나라도 속도 파싱에 성공했으면 그 틱은 "성공"으로 인정해야 한다.
    // 이 필드에 마지막으로 성공한 월드타임(틱 카운터)을 기억해두고, 같은 틱 안에서
    // 뒤이어 도착한 실패 패킷이 그 성공 기록을 덮어쓰지 못하게 막는다.
    @Unique
    private long mcrider$lastSpeedSuccessWorldTime = Long.MIN_VALUE;

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
        long worldTime = (world != null) ? world.getTime() : mcrider$lastSpeedSuccessWorldTime;

        if (matched) {
            MCRiderCamera.actionbarSpeed = speed;
            MCRiderCamera.timeAfterLastActionbar = 0;
            mcrider$lastSpeedSuccessWorldTime = worldTime;
        } else if (worldTime != mcrider$lastSpeedSuccessWorldTime) {
            // 이번 틱에 아직 유효한 속도를 못 받았을 때만 "없음"으로 갱신한다.
            // 같은 틱에 이미 성공했는데 뒤이어 온 무관한 패킷이 그걸 지우는 걸 방지.
            MCRiderCamera.actionbarSpeed = speed;
            MCRiderCamera.timeAfterLastActionbar = 20;
        }
    }
    @Unique
    float mcrider$extractSpeed(String text) {
        Pattern p = Pattern.compile("(\\d+(?:\\.\\d+)?)km/h");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return Math.max(Float.parseFloat(m.group(1)), 0);
        }
        return -169f; // 매칭 실패 시 -169 반환
    }
}
