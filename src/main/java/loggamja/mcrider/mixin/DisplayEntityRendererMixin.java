
package loggamja.mcrider.mixin;

import loggamja.mcrider.MCRiderMain;
import loggamja.mcrider.MCRiderSuspension;
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

        double pivotY = ((DisplayEntityRenderStateAccessor) state).mcrider$getPivotY();

        AffineTransformation affine = state.displayRenderState.transformation().interpolate(state.lerpProgress);
        Matrix4f m = new Matrix4f(affine.getMatrix());
        Matrix4f roll = new Matrix4f().rotateZ((float) Math.toRadians(rollDeg));

        // 주의: 아래 두 m.invert() 호출은 "같은 걸 두 번 곱하는 실수"가 아니다.
        // JOML의 Matrix4f.invert()는 자기 자신을 제자리(in-place)에서 뒤집고 반환하므로,
        // 첫 번째 호출 시점엔 m == M(원본) → 뒤집혀서 M⁻¹이 적용되고,
        // 두 번째 호출 시점엔 m == M⁻¹(직전 결과) → 다시 뒤집혀서 M(원본)이 적용된다.
        // 즉 실제로 곱해지는 순서는 M⁻¹ · Roll · M (변환 공간을 원점 기준으로 되돌려서
        // roll을 적용한 뒤 다시 원래 변환으로 되돌리는 conjugation) 이다.
        // 둘 중 하나를 "중복"이라 착각해 지우면 조용히 깨지니 절대 지우지 말 것.
        matrices.multiplyPositionMatrix(m.invert());
        matrices.translate(0.0f, (float) -pivotY, 0.0f);
        matrices.multiplyPositionMatrix(roll);
        matrices.translate(0.0f, (float) pivotY, 0.0f);
        matrices.multiplyPositionMatrix(m.invert());
    }
    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/decoration/DisplayEntity;Lnet/minecraft/client/render/entity/state/DisplayEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void mcrider$captureUuid(DisplayEntity entity, DisplayEntityRenderState state, float tickDelta, CallbackInfo ci) {
        DisplayEntityRenderStateAccessor accessor = (DisplayEntityRenderStateAccessor) state;
        accessor.mcrider$setUuid(entity.getUuid());

        // 회전 중심 y 오프셋: 엔티티 y - RootVehicle y - pivotYOffset
        // pivotYOffset이 클수록 회전 중심이 위로 올라감
        if (MCRiderMain.isRidingKart && entity.hasVehicle()) {
            double pivotY = entity.getY() - entity.getRootVehicle().getY() - MCRiderSuspension.pivotYOffset;
            accessor.mcrider$setPivotY(pivotY);
        } else {
            accessor.mcrider$setPivotY(0.0);
        }
    }
}