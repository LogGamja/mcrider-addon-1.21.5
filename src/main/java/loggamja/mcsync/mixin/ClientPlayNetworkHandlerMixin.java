package loggamja.mcsync.mixin;

import loggamja.mcsync.MCRiderCamera;
import loggamja.mcsync.MCRiderMain;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.text.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onOverlayMessage", at = @At("HEAD"))
    private void onHandleGameMessage(OverlayMessageS2CPacket packet, CallbackInfo ci) {
        if (!MCRiderMain.isRidingKart || !MCRiderMain.isPlayingInGame()) return;

        Text message = packet.text();
        var speed = extractSpeed(message.getString());

        if (MCRiderMain.currentSaddleType.equals("1.0")) {
            speed *= 2.59065f;
        }

        MCRiderCamera.actionbarSpeed = speed;
    }
    @Unique
    float extractSpeed(String text) {
        Pattern p = Pattern.compile("(\\d+(?:\\.\\d+)?)km/h");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return Math.max(Float.parseFloat(m.group(1)), 0);
        }
        return -169f; // 매칭 실패 시 -169 반환
    }
}
