
package loggamja.mcsync.mixin;

import loggamja.mcsync.MCRiderMain;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "clampBodyYaw", at = @At(value = "HEAD"), cancellable = true)
    private static void clampBodyYaw(LivingEntity entity, float degrees, float tickProgress, CallbackInfoReturnable<Float> cir) {
        if (entity instanceof PlayerEntity player && MCRiderMain.isRidingKart(player)) {
            var criteria = player.headYaw;

            var delta = MathHelper.clamp(MathHelper.wrapDegrees(criteria - player.bodyYaw), -85.0F, 85.0F);
            player.setBodyYaw(criteria - delta);

            var value = MathHelper.lerpAngleDegrees(tickProgress, player.lastBodyYaw, player.bodyYaw);
            cir.setReturnValue(value);
        }
    }
}
