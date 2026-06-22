package loggamja.mcsync.mixin;

import loggamja.mcsync.MCRiderCamera;
import loggamja.mcsync.MCRiderConfig;
import loggamja.mcsync.MCRiderMain;
import loggamja.mcsync.RollManager;
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

    @Shadow
    private Quaternionf rotation; // final 이라도 객체 자체는 변경 가능

    // --- 카메라 롤 지수 평활(EMA) 상태 ---
    @Unique private float mcsync$smoothedRoll = 0f;
    @Unique private long  mcsync$lastNanos    = 0L;

    // 부드러움 정도: raw 값의 약 63%까지 따라오는 데 걸리는 시간(초). 클수록 더 느리고 묵직.
    @Unique private static final float MCSYNC$SMOOTH_TIME = 0.15f;
    // 프레임 시간 상한(초). 렉/일시정지로 dt 가 튀어도 이 이상은 무시 → 스냅 방지.
    @Unique private static final float MCSYNC$MAX_DT      = 0.1f;
    // 카메라 롤 배율(원본 롤 대비). 멀미 방지용으로 살짝만.
    @Unique private static final float MCSYNC$ROLL_SCALE  = 0.25f;

    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void mcsync$clipToSpace(float f, CallbackInfoReturnable<Float> cir) {
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
    private void mcsync$applyRoll(float yaw, float pitch, CallbackInfo ci) {
        // 1) 프레임 경과 시간(초). 가드보다 먼저 재서 항상 정확한 간격 유지.
        long now = System.nanoTime();
        float dt = (mcsync$lastNanos == 0L) ? 0f : (now - mcsync$lastNanos) / 1.0e9f;
        mcsync$lastNanos = now;

        // 프레임 요동/멈춤 대비: 음수 제거 + 상한 클램프
        if (dt < 0f) dt = 0f;
        if (dt > MCSYNC$MAX_DT) dt = MCSYNC$MAX_DT;

        // 2) 대상 확인
        Entity focused = ((Camera) (Object) this).getFocusedEntity();
        if (!(focused instanceof PlayerEntity player)) return;

        // 1인칭에서만 쓰려면 주석 해제
        //if (!MinecraftClient.getInstance().options.getPerspective().isFirstPerson()) return;

        // 3) 이번 프레임 원본 롤(0 이어도 그대로 반영 → 평균이 0으로 수렴)
        float raw = RollManager.getCurrentRoll(player.getUuid()) * MCSYNC$ROLL_SCALE;

        // 4) FPS 무관 지수 평활. alpha 는 항상 (0,1] 이라 튀거나 발산하지 않음.
        float alpha = (MCSYNC$SMOOTH_TIME <= 0f) ? 1f
                : 1f - (float) Math.exp(-dt / MCSYNC$SMOOTH_TIME);
        mcsync$smoothedRoll += (raw - mcsync$smoothedRoll) * alpha;

        // 비정상 값 안전망
        if (!Float.isFinite(mcsync$smoothedRoll)) mcsync$smoothedRoll = raw;

        // 5) 적용
        if (Math.abs(mcsync$smoothedRoll) < 1.0e-4f) return;
        this.rotation.rotateZ((float) Math.toRadians(mcsync$smoothedRoll));
    }
}