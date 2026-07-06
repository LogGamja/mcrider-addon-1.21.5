
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

        // JOML의 invert()는 제자리(in-place) 변형이라, m을 담은 이 객체는 첫 줄에서 m^-1이 되고
        // 마지막 줄의 invert() 호출이 그 m^-1을 다시 뒤집어 원래 m으로 되돌린다("두 번 반전"이
        // 아니라 반전 → 원복). 그 사이에서 pivotY 기준 롤 회전을 끼워 넣어, 결과적으로
        // "엔티티 고유 변환을 잠깐 무효화한 좌표계에서 회전 후 원래 변환으로 복귀"하는
        // 켤레(conjugation) 변환이 된다.
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