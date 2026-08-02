package loggamja.mcrider;

import loggamja.mcrider.option.MCRiderConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 옵저버 카메라: 실제로 다른 라이더를 관전 중일 때 작동
public class MCRiderObserverCamera implements ClientModInitializer {
    static final double TELEPORT_DISTANCE_THRESHOLD = 16.0;
    static UUID lastObservedTargetUuid = null;
    static Vec3d lastObservedPos = null;
    static boolean isReset = true;

    static final float DEFAULT_PITCH = 15f;
    static final float MIN_PITCH = 10f;
    static final float MAX_PITCH = 60f;

    static final float LOCAL_PITCH_DOWN_OFFSET = 10f;

    static final int VELOCITY_WINDOW_SIZE = 15;
    static final float VERTICAL_VELOCITY_MULTIPLIER = 0.75f;

    static Double lastY = null;
    static List<Float> verticalVelocityBuffer = new ArrayList<>();

    static float pitch = DEFAULT_PITCH;
    static float pitchAtPrevTick = DEFAULT_PITCH;

    // 카트바디 모델 방향으로의 보간
    static float kartBodyYawRaw = 0f;
    static final float ANCHOR_YAW_SMOOTH_TIME = 0.3f;
    static final float ANCHOR_YAW_MAX_DT = 0.1f;
    static long anchorYawLastTimeNanos = 0L;
    static float renderedAnchorYaw = 0f;
    static boolean needsAnchorResync = true;

    // 오버슈팅 보간
    static final float YAW_OVERSHOOT_MULTIPLIER = -0.175f;
    static final float YAW_OVERSHOOT_MAX = 90f;
    static final float YAW_VELOCITY_SMOOTH_TIME = 0.3f;
    static float anchorYawVelocity = 0f;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> onClientTickStart());
    }
    void onClientTickStart() {
        if (!isObserverLogicActive()) {
            if (!isReset) reset();
            return;
        }
        isReset = false;

        PlayerEntity target = MCRiderMain.getRidingPlayer();
        float newYaw = MCRiderMain.getKartBodyYaw(target, 1f);
        Vec3d pos = target.getPos();

        // 대상 전환은 렌더 경로(updateAnchorYaw)가 먼저 감지해 이미 리싱크했을 수 있다.
        // 여기서 또 UUID만 보고 판단하면 그 리싱크를 놓치므로 공용 헬퍼로 통일한다
        boolean targetChanged = syncToTargetIfChanged(target, newYaw);
        boolean mustResync = targetChanged || (lastObservedPos != null && pos.distanceTo(lastObservedPos) > TELEPORT_DISTANCE_THRESHOLD);

        if (mustResync) resync(newYaw);

        lastObservedTargetUuid = target.getUuid();
        lastObservedPos = pos;

        updatePitch(target);
        kartBodyYawRaw = newYaw;
    }
    // 관전 대상이 바뀌면 화면에 다음 프레임이 그려지기 전에 앵커를 새 대상 방향으로 맞춰야 한다.
    // 틱 핸들러와 렌더 경로(updateAnchorYaw) 양쪽에서 호출되므로, 어느 쪽이 먼저 대상 전환을
    // 감지하더라도 나머지 한쪽이 중복 리싱크하지 않도록 UUID 갱신까지 여기서 함께 처리한다
    static boolean syncToTargetIfChanged(PlayerEntity target, float newYaw) {
        if (target.getUuid().equals(lastObservedTargetUuid)) return false;

        resync(newYaw);
        lastObservedTargetUuid = target.getUuid();
        lastObservedPos = target.getPos();
        return true;
    }
    static void resync(float newYaw) {
        lastY = null;
        verticalVelocityBuffer.clear();
        syncAnchor(newYaw);

        pitch = DEFAULT_PITCH;
        pitchAtPrevTick = DEFAULT_PITCH;
    }
    static void syncAnchor(float newYaw) {
        kartBodyYawRaw = newYaw;
        renderedAnchorYaw = newYaw;
        anchorYawVelocity = 0f;

        anchorYawLastTimeNanos = 0L;
        needsAnchorResync = false;
    }
    void updatePitch(PlayerEntity target) {
        double curY = target.getY();
        float tickInterval = MCRiderCamera.getTickRate();

        if (lastY != null && tickInterval > 0f) {
            float verticalVelocity = (float) ((curY - lastY) / tickInterval);
            addToVelocityBuffer(verticalVelocity);
        }
        lastY = curY;

        pitchAtPrevTick = pitch;
        float targetPitch = DEFAULT_PITCH - averageVelocity() * VERTICAL_VELOCITY_MULTIPLIER;
        pitch = MathHelper.clamp(targetPitch, MIN_PITCH, MAX_PITCH);
    }
    static void addToVelocityBuffer(float velocity) {
        verticalVelocityBuffer.add(velocity);
        if (verticalVelocityBuffer.size() > VELOCITY_WINDOW_SIZE) {
            verticalVelocityBuffer.removeFirst();
        }
    }
    static float averageVelocity() {
        if (verticalVelocityBuffer.isEmpty()) return 0f;

        float sum = 0f;
        for (float v : verticalVelocityBuffer) sum += v;
        return sum / verticalVelocityBuffer.size();
    }
    static void reset() {
        lastObservedTargetUuid = null;
        lastObservedPos = null;

        lastY = null;
        verticalVelocityBuffer.clear();
        pitch = DEFAULT_PITCH;
        pitchAtPrevTick = DEFAULT_PITCH;

        kartBodyYawRaw = 0f;
        anchorYawLastTimeNanos = 0L;
        renderedAnchorYaw = 0f;
        anchorYawVelocity = 0f;
        needsAnchorResync = true;
        isReset = true;
    }
    public static boolean isObserverLogicActive() {
        if (!MCRiderMain.isPlayingInGame() || !MCRiderMain.isRidingKart) return false;
        if (MCRiderConfig.INSTANCE.spectatorCameraMode == 0) return false;

        return MCRiderMain.isSpectatingPlayer();
    }
    public static boolean isOvershootActive() {
        return isObserverLogicActive() && MCRiderConfig.INSTANCE.spectatorCameraMode == 2;
    }
    public static float getPitchOverride(float vanillaPitch, float tickDelta) {
        if (!isObserverLogicActive()) return vanillaPitch;
        return MathHelper.lerp(tickDelta, pitchAtPrevTick, pitch);
    }
    // 1인칭이나 2인칭 상태에서 호출
    public static void requestAnchorResync() {
        needsAnchorResync = true;
    }
    // 앵커 위치 계산에 쓰이는 yaw. 카트바디 방향의 지수평균으로 대체한다.
    // Camera#setRotation의 @ModifyVariable에서만 프레임당 정확히 한 번 호출해야 한다
    public static float updateAnchorYaw(float vanillaYaw) {
        if (!isObserverLogicActive()) return vanillaYaw;

        // 대상 전환도 틱을 기다리면 그 사이 프레임들이 이전 대상의 앵커로 그려진다
        PlayerEntity target = MCRiderMain.getRidingPlayer();
        syncToTargetIfChanged(target, MCRiderMain.getKartBodyYaw(target, 1f));

        // 틱을 기다리면 그 사이 프레임들이 초기화된 방향을 그대로 그린다
        if (needsAnchorResync) syncAnchor(MCRiderMain.getKartBodyYaw(MCRiderMain.getRidingPlayer(), 1f));

        long now = System.nanoTime();
        float dt = (anchorYawLastTimeNanos == 0L) ? 0f : (now - anchorYawLastTimeNanos) / 1.0e9f;
        anchorYawLastTimeNanos = now;
        dt = MathHelper.clamp(dt, 0f, ANCHOR_YAW_MAX_DT);

        float alpha = 1f - (float) Math.exp(-dt / ANCHOR_YAW_SMOOTH_TIME);
        float step = MathHelper.wrapDegrees(kartBodyYawRaw - renderedAnchorYaw) * alpha;
        renderedAnchorYaw = MathHelper.wrapDegrees(renderedAnchorYaw + step);

        float rawVelocity = (dt > 0f) ? step / dt : 0f;
        float velocityAlpha = 1f - (float) Math.exp(-dt / YAW_VELOCITY_SMOOTH_TIME);
        anchorYawVelocity += (rawVelocity - anchorYawVelocity) * velocityAlpha;

        return renderedAnchorYaw;
    }
    public static float getYawOvershootOffset() {
        if (!isOvershootActive()) return 0f;

        // 클램프
        float offset = anchorYawVelocity * YAW_OVERSHOOT_MULTIPLIER;
        return MathHelper.clamp(offset, -YAW_OVERSHOOT_MAX, YAW_OVERSHOOT_MAX);
    }
    public static float getLocalPitchOffset() {
        if (!isObserverLogicActive()) return 0f;
        return LOCAL_PITCH_DOWN_OFFSET;
    }
}
