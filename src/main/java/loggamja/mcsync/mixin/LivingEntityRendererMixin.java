
package loggamja.mcsync.mixin;

import loggamja.mcsync.MCRiderMain;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "clampBodyYaw", at = @At(value = "HEAD"), cancellable = true)
    private static void clampBodyYaw(LivingEntity entity, float degrees, float tickProgress, CallbackInfoReturnable<Float> cir) {
        if (MCRiderMain.isRidingKart && entity == MCRiderMain.getRidingPlayer()) {
            cir.setReturnValue(MathHelper.lerpAngleDegrees(tickProgress, entity.lastBodyYaw, entity.bodyYaw));
        }
    }
}
