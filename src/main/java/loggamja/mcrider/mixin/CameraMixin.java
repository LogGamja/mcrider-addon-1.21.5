package loggamja.mcrider.mixin;

import loggamja.mcrider.MCRiderCamera;
import loggamja.mcrider.MCRiderConfig;
import loggamja.mcrider.MCRiderMain;
import loggamja.mcrider.helper.EntityRollManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public class CameraMixin {

    @Shadow private Quaternionf rotation; // final 이라도 객체 자체는 변경 가능

    // --- 카메라 롤 지수 평활(EMA) 상태 ---
    @Unique private long mcrider$lastTime = 0L;
    @Unique private float mcrider$smoothRoll = 0f;

    @Unique private static final float mcrider$MAX_DT      = 0.1f;
    @Unique private static final float mcrider$SMOOTH_TIME = 0.15f;
    @Unique private static final float mcrider$ROLL_MULTIPLIER = 0.25f;

    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void mcrider$clipToSpace(float f, CallbackInfoReturnable<Float> cir) {
        if (!MCRiderMain.isRidingKart) return;

        var delta = MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false);

        var newDistance = MCRiderCamera.getCameraDistanceOffset(f);
        var newDistanceAtPrevTick = MCRiderCamera.getCameraDistanceOffsetAtPrevTick(f);

        var lerpedDistance = MathHelper.lerp(delta, newDistanceAtPrevTick, newDistance);

        if (MCRiderConfig.INSTANCE.useNoclipCamera || newDistance != f) {
            cir.setReturnValue(lerpedDistance);
        }
    }

    @Inject(method = "setRotation(FF)V", at = @At("TAIL"))
    private void mcrider$setRotation(float yaw, float pitch, CallbackInfo ci) {
        if (MCRiderConfig.INSTANCE.suspensionEffect != 2) return;

        long now = System.nanoTime();
        float dt = (mcrider$lastTime == 0L) ? 0f : (now - mcrider$lastTime) / 1.0e9f;
        mcrider$lastTime = now;

        // 프레임 요동/멈춤 대비: 음수 제거 + 상한 클램프
        if (dt < 0f) dt = 0f;
        if (dt > mcrider$MAX_DT) dt = mcrider$MAX_DT;

        // 2) 대상 확인
        Entity focused = ((Camera) (Object) this).getFocusedEntity();
        if (!(focused instanceof PlayerEntity player)) return;

        // 3) 이번 프레임 원본 롤(0이어도 그대로 반영해 평균이 0으로 수렴하게 함)
        float raw = EntityRollManager.getCurrentRoll(player.getUuid()) * mcrider$ROLL_MULTIPLIER;
        var isBike = MCRiderMain.getS2CValue(MCRiderMain.getRidingPlayer(), "data-is-bike");
        if (isBike == 1 && MCRiderConfig.INSTANCE.bikeSuspension == 3) {
            raw /= 4;
        }

        // 4) FPS 무관 지수 평활. alpha 는 항상 (0,1] 이라 튀거나 발산하지 않음.
        float alpha = (mcrider$SMOOTH_TIME <= 0f) ? 1f
                : 1f - (float) Math.exp(-dt / mcrider$SMOOTH_TIME);
        mcrider$smoothRoll += (raw - mcrider$smoothRoll) * alpha;

        // 비정상 값 안전망
        if (!Float.isFinite(mcrider$smoothRoll)) mcrider$smoothRoll = raw;

        // 5) 적용
        if (Math.abs(mcrider$smoothRoll) < 1.0e-4f) return;
        this.rotation.rotateZ((float) Math.toRadians(mcrider$smoothRoll));
    }
}