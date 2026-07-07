
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import net.minecraft.client.render.entity.DisplayEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.decoration.DisplayEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(DisplayEntityRenderer.class)
public class DisplayEntityRendererMixin {

    // 렌더링은 클라이언트 단일 스레드에서만 도니 재사용 가능. 롤 회전 중인 엔티티마다 매 프레임
    // new Matrix4f를 할당하던 것을 없애 GC 압박을 줄인다.
    @Unique private static final Matrix4f mcrider$scratchOriginal = new Matrix4f();
    @Unique private static final Matrix4f mcrider$scratchInverse = new Matrix4f();
    @Unique private static final Matrix4f mcrider$scratchRoll = new Matrix4f();

    // render() 호출 시 MatrixStack에 roll rotation을 주입합니다.
    // AT 타겟: setupTransforms 이후, 실제 geometry 렌더링 직전
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
        mcrider$scratchOriginal.set(affine.getMatrix());
        mcrider$scratchInverse.set(mcrider$scratchOriginal).invert();
        mcrider$scratchRoll.rotationZ((float) Math.toRadians(rollDeg));

        matrices.multiplyPositionMatrix(mcrider$scratchInverse);
        matrices.translate(0.0f, (float) -pivotY, 0.0f);
        matrices.multiplyPositionMatrix(mcrider$scratchRoll);
        matrices.translate(0.0f, (float) pivotY, 0.0f);
        matrices.multiplyPositionMatrix(mcrider$scratchOriginal);
    }
    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/decoration/DisplayEntity;Lnet/minecraft/client/render/entity/state/DisplayEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void mcrider$captureUuid(DisplayEntity entity, DisplayEntityRenderState state, float tickDelta, CallbackInfo ci) {
        DisplayEntityRenderStateAccessor accessor = (DisplayEntityRenderStateAccessor) state;
        accessor.mcrider$setUuid(entity.getUuid());

        // pivotYOffset이 클수록 회전 중심이 위로 올라간다.
        if (MCRiderMain.isRidingKart && entity.hasVehicle()) {
            double pivotY = entity.getY() - entity.getRootVehicle().getY() - MCRiderSuspension.pivotYOffset;
            accessor.mcrider$setPivotY(pivotY);
        } else {
            accessor.mcrider$setPivotY(0.0);
        }
    }
}