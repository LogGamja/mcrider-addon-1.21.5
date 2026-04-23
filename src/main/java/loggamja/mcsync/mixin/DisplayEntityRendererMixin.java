
package loggamja.mcsync.mixin;

import loggamja.mcsync.MCRiderMain;
import loggamja.mcsync.RollManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.DisplayEntityRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.render.entity.DisplayEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.decoration.DisplayEntity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisplayEntityRenderer.class)
public class DisplayEntityRendererMixin {

    /**
     * render() 호출 시 MatrixStack에 roll rotation을 주입합니다.
     *
     * AT 타겟: setupTransforms 이후, 실제 geometry 렌더링 직전
     */
    @Inject(
            method = "render(Lnet/minecraft/client/render/entity/state/DisplayEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/entity/DisplayEntityRenderer;render(Lnet/minecraft/client/render/entity/state/DisplayEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V"
            )
    )
    private void injectRollRotation(
            DisplayEntityRenderState state,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            CallbackInfo ci
    ) {
        if (state.displayRenderState == null) return;

        AffineTransformation affine = state.displayRenderState.transformation().interpolate(state.lerpProgress);

        Matrix4f m = new Matrix4f(affine.getMatrix());
        Matrix4f q = new Matrix4f();

        q.rotateZ((float) Math.toRadians(10));

        matrices.multiplyPositionMatrix(m.invert());
        matrices.multiplyPositionMatrix(q);
        matrices.multiplyPositionMatrix(m.invert());
    }
}