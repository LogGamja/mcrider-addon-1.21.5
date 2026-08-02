package loggamja.mcrider.mixin;

import loggamja.mcrider.MCRiderCamera;
import loggamja.mcrider.MCRiderObserverCamera;
import loggamja.mcrider.option.MCRiderConfig;
import loggamja.mcrider.MCRiderMain;
import loggamja.mcrider.helper.EntityRollManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.BlockView;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public class CameraMixin {

    @Shadow private Quaternionf rotation; // final 이라도 객체 자체는 변경 가능

    // 카메라 롤 지수 평활(EMA) 상태
    @Unique private long mcrider$lastTime = 0L;
    @Unique private float mcrider$smoothRoll = 0f;

    @Unique private static final float mcrider$MAX_DT      = 0.1f;
    @Unique private static final float mcrider$SMOOTH_TIME = 0.15f;
    @Unique private static final float mcrider$ROLL_MULTIPLIER = 0.25f;

    // 기본 카메라이면 null을 반환해 vanilla 거리를 그대로 사용
    @Unique
    private static Float mcrider$computeCustomDistance(float f) {
        var newDistance = MCRiderCamera.getCameraDistanceOffset(f);
        if (newDistance == f) return null;

        var delta = MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false);
        var newDistanceAtPrevTick = MCRiderCamera.getCameraDistanceOffsetAtPrevTick(f);
        return MathHelper.lerp(delta, newDistanceAtPrevTick, newDistance);
    }
    // 카메라 모드에 무관하게 노클립
    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void mcrider$clipToSpace(float f, CallbackInfoReturnable<Float> cir) {
        if (!MCRiderMain.isRidingKart || !MCRiderConfig.INSTANCE.useNoclipCamera) return;

        Float distance = mcrider$computeCustomDistance(f);
        cir.setReturnValue(distance != null ? distance : f);
    }
    // 바닐라가 커스텀 카메라 거리로 벽 충돌검사를 하도록 바꿔치기한다
    @ModifyVariable(method = "clipToSpace", at = @At("HEAD"), argsOnly = true)
    private float mcrider$overrideClipDistance(float f) {
        if (!MCRiderMain.isRidingKart || MCRiderConfig.INSTANCE.useNoclipCamera) return f;

        Float distance = mcrider$computeCustomDistance(f);
        return distance != null ? distance : f;
    }

    @Inject(method = "setRotation(FF)V", at = @At("TAIL"))
    private void mcrider$setRotation(float yaw, float pitch, CallbackInfo ci) {
        if (MCRiderConfig.INSTANCE.suspensionEffect != 2) return;

        long now = System.nanoTime();
        float dt = (mcrider$lastTime == 0L) ? 0f : (now - mcrider$lastTime) / 1.0e9f;
        mcrider$lastTime = now;

        if (dt < 0f) dt = 0f;
        if (dt > mcrider$MAX_DT) dt = mcrider$MAX_DT;

        Entity focused = ((Camera) (Object) this).getFocusedEntity();
        if (!(focused instanceof PlayerEntity player)) return;

        // 0이어도 그대로 반영해야 평균이 0으로 수렴한다
        float raw = EntityRollManager.getCurrentRoll(player.getUuid()) * mcrider$ROLL_MULTIPLIER;
        var isBike = MCRiderMain.getS2CValue(player, "data-is-bike");
        if (isBike == 1 && MCRiderConfig.INSTANCE.bikeSuspension == 3) {
            raw /= 4;
        }

        // alpha는 항상 (0,1]이라 튀거나 발산하지 않음
        float alpha = (mcrider$SMOOTH_TIME <= 0f) ? 1f
                : 1f - (float) Math.exp(-dt / mcrider$SMOOTH_TIME);
        mcrider$smoothRoll += (raw - mcrider$smoothRoll) * alpha;

        if (!Float.isFinite(mcrider$smoothRoll)) mcrider$smoothRoll = raw;

        if (Math.abs(mcrider$smoothRoll) < 1.0e-4f) return;
        this.rotation.rotateZ((float) Math.toRadians(mcrider$smoothRoll));
    }

    // 앵커(카메라 위치) 계산에 쓰일 yaw를 카트바디(mcrider-modelsaddle) 방향의 지수평균으로 대체한다.
    @ModifyVariable(method = "setRotation(FF)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float mcrider$overrideObserverAnchorYaw(float yaw) {
        if (MinecraftClient.getInstance().options.getPerspective() != Perspective.THIRD_PERSON_BACK) {
            MCRiderObserverCamera.requestAnchorResync();
            return yaw;
        }

        return MCRiderObserverCamera.updateAnchorYaw(yaw);
    }

    // pitch는 옵저버 카메라 로직(수직 속도 기반)으로 대체한다
    @ModifyVariable(method = "setRotation(FF)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float mcrider$overrideObserverPitch(float pitch) {
        if (MinecraftClient.getInstance().options.getPerspective() != Perspective.THIRD_PERSON_BACK) return pitch;
        if (!MCRiderObserverCamera.isObserverLogicActive()) return pitch;

        float delta = MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false);
        return MCRiderObserverCamera.getPitchOverride(pitch, delta);
    }

    // TAIL이라 앵커 위치는 그대로 두고 화면 방향만 바뀐다.
    // 서스펜션 롤과 축이 섞이지 않도록 롤을 떼었다가 yaw(월드축 premul)/pitch(로컬축)를 적용한 뒤 롤을 다시 붙인다.
    @Inject(method = "update", at = @At("TAIL"))
    private void mcrider$applyObserverOffsets(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (!thirdPerson || inverseView) return;

        float yawOffset = MCRiderObserverCamera.getYawOvershootOffset();
        float pitchOffset = MCRiderObserverCamera.getLocalPitchOffset();
        if (yawOffset == 0f && pitchOffset == 0f) return;

        boolean hasRoll = MCRiderConfig.INSTANCE.suspensionEffect == 2 && Math.abs(mcrider$smoothRoll) >= 1.0e-4f;
        float rollRad = hasRoll ? (float) Math.toRadians(mcrider$smoothRoll) : 0f;

        if (hasRoll) this.rotation.rotateZ(-rollRad);

        if (yawOffset != 0f) {
            Quaternionf worldYaw = new Quaternionf().rotationY((float) Math.toRadians(yawOffset));
            this.rotation.premul(worldYaw);
        }
        if (pitchOffset != 0f) {
            this.rotation.rotateX((float) Math.toRadians(-pitchOffset));
        }

        if (hasRoll) this.rotation.rotateZ(rollRad);
    }
}