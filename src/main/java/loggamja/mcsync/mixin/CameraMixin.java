package loggamja.mcsync.mixin;

import loggamja.mcsync.MCRiderCamera;
import loggamja.mcsync.MCRiderConfig;
import loggamja.mcsync.MCRiderMain;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public class CameraMixin {
    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void clipToSpace(float f, CallbackInfoReturnable<Float> cir) {
        if (!MCRiderMain.isRidingKart) return;

        var delta = MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false);

        var newDistance = MCRiderCamera.getCameraDistanceOffset(f);
        var newDistanceAtPrevTick = MCRiderCamera.getCameraDistanceOffsetAtPrevTick(f);

        var lerpedDistance = MathHelper.lerp(delta, newDistanceAtPrevTick, newDistance);

        if (MCRiderConfig.INSTANCE.useNoclipCamera || newDistance != f) {
            cir.setReturnValue(lerpedDistance);
        }
    }
}
