package loggamja.mcsync.mixin;

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
    @Inject(method = "onOverlayMessage", at = @At("HEAD"))
    private void onHandleGameMessage(OverlayMessageS2CPacket packet, CallbackInfo ci) {
        Text message = packet.text();
        //MCRiderCamera.actionbarSpeed = extractSpeed(message.getString());
    }
    @Unique
    Float extractSpeed(String text) {
        Pattern p = Pattern.compile("(\\d+(?:\\.\\d+)?)km/h");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return Float.parseFloat(m.group(1));
        }
        return -1.0f; // 매칭 실패 시 null 반환
    }
}
