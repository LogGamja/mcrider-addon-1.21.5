
package loggamja.mcrider.mixin;

import loggamja.mcrider.helper.EntityRollManager;
import loggamja.mcrider.interfaces.DisplayEntityRenderStateAccessor;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.state.DisplayEntityRenderState;
import net.minecraft.util.math.AffineTransformation;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import net.minecraft.client.render.entity.DisplayEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.decoration.DisplayEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

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
    private void mcrider$injectRollRotation(
            DisplayEntityRenderState state,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            CallbackInfo ci
    ) {
        if (state.displayRenderState == null) return;

        UUID uuid = ((DisplayEntityRenderStateAccessor) state).mcrider$getUuid();
        if (uuid == null) return;

        float rollDeg = EntityRollManager.getCurrentRoll(uuid);
        if (rollDeg == 0f) return;

        AffineTransformation affine = state.displayRenderState.transformation().interpolate(state.lerpProgress);
        Matrix4f m = new Matrix4f(affine.getMatrix());
        Matrix4f roll = new Matrix4f().rotateZ((float) Math.toRadians(rollDeg));

        matrices.multiplyPositionMatrix(m.invert());
        matrices.multiplyPositionMatrix(roll);
        matrices.multiplyPositionMatrix(m.invert());
    }
    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/decoration/DisplayEntity;Lnet/minecraft/client/render/entity/state/DisplayEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void mcrider$captureUuid(DisplayEntity entity, DisplayEntityRenderState state, float tickDelta, CallbackInfo ci) {
        ((DisplayEntityRenderStateAccessor) state).mcrider$setUuid(entity.getUuid());
    }
}