package loggamja.mcsync.mixin;

import loggamja.mcsync.MCRiderMain;
import loggamja.mcsync.RollManager;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

    @Unique
    private float roll = 0f;

    @Unique
    private double pivotDown = 0.0;

    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void captureRoll(LivingEntity entity, LivingEntityRenderState state,
                             float tickDelta, CallbackInfo ci) {
        this.roll = -RollManager.getCurrentRoll(entity.getUuid());

        Entity root = entity.getRootVehicle();
        this.pivotDown = entity.getY() - root.getY(); // 플레이어 Y − 루트 탈것 Y
    }

    @Inject(
            method = "setupTransforms(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;FF)V",
            at = @At("TAIL")
    )
    private void applyRoll(LivingEntityRenderState state, MatrixStack matrices,
                           float bodyYaw, float baseHeight, CallbackInfo ci) {
        float rollDeg = this.roll;
        if (rollDeg == 0f) return;

        matrices.translate(0.0, -this.pivotDown, 0.0);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rollDeg));
        matrices.translate(0.0,  this.pivotDown, 0.0);
    }
}