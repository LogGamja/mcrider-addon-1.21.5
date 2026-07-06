package loggamja.mcrider;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.tick.TickManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MCRiderCamera implements ClientModInitializer {
    static MinecraftClient client = MinecraftClient.getInstance();

    public static List<Float> targetFovBuffer = new ArrayList<>(Collections.nCopies(1, 0f));

    static boolean isUsingBooster = false;

    float filteredTargetFOV = 92;
    public static float filteredFOV = 90;
    public static float filteredFOVAtPrevTick = 90;

    static double cameraDistanceOffset = 545;
    static double cameraDistanceOffsetAtPrevTick = 545;

    static final double BASE_DISTANCE = 545;
    final double DIST_COEFFICIENT = 1.414;

    static double linearTransformTargetBaseDistance = BASE_DISTANCE;

    Vec3d lastPos = null;
    float speed = 0f;
    public static float realSpeed = 0f;
    public static float actionbarSpeed = 0f;
    public static int timeAfterLastActionbar = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            onClientTickStart();
        });
    }

    void onClientTickStart() {
        if (!MCRiderMain.isPlayingInGame() || !MCRiderMain.isRidingKart || MCRiderConfig.INSTANCE.cameraMode == 0) return;

        calculateSpeed();
        detectBooster();

        int armSpeedMultiplier = 2;
        if (MCRiderConfig.INSTANCE.cameraMode == 1) {
            armSpeedMultiplier = 6;
            linearTransformTargetBaseDistance = 605;
            addToTargetFovBuffer(isUsingBooster ? 118f : 102f);
        }
        else if (MCRiderConfig.INSTANCE.cameraMode == 2) {
            armSpeedMultiplier = 5;
            linearTransformTargetBaseDistance = 600;
            addToTargetFovBuffer(isUsingBooster ? 120f : 100f);
        }
        else if (MCRiderConfig.INSTANCE.cameraMode == 3) {
            armSpeedMultiplier = 4;
            linearTransformTargetBaseDistance = 595;
            addToTargetFovBuffer(isUsingBooster ? 122f : 98f);
        }

        filteredFOVAtPrevTick = filteredFOV;
        if (isUsingBooster) {
            filteredFOV = interpolate(filteredFOV, filteredTargetFOV, 1f);
        }
        else {
            filteredFOV = interpolate(filteredFOV, filteredTargetFOV, 0.65f);
        }

        final double deltaTime = getTickRate();

        final double ARM_SPEED_FORWARD = 0.08 * armSpeedMultiplier;
        final double ARM_SPEED_BACKWARD = 4 * armSpeedMultiplier;

        final float actionbarSpeedHalf = actionbarSpeed / 2;

        timeAfterLastActionbar++;
        speed = (timeAfterLastActionbar > 20) ? realSpeed : actionbarSpeedHalf;
        if (realSpeed > speed) speed++;

        final double convertedSpeed = speed * 24.83;
        var targetDistance = BASE_DISTANCE + (convertedSpeed * 0.0003284 * BASE_DISTANCE * (DIST_COEFFICIENT - 1.0));

        cameraDistanceOffsetAtPrevTick = cameraDistanceOffset;

        var rate = ARM_SPEED_BACKWARD;
        if (cameraDistanceOffset < targetDistance) {
            rate = ARM_SPEED_FORWARD;
        }
        var decay = Math.exp(-rate * DIST_COEFFICIENT * deltaTime);
        cameraDistanceOffset = targetDistance + (cameraDistanceOffset - targetDistance) * decay;

        //System.out.println(linearMap(cameraDistanceOffset, BASE_DISTANCE, 815, 600, 815));
    }
    static float getTickRate() {
        final MinecraftClient client = MinecraftClient.getInstance();
        final ClientWorld world = client.world;

        if (world != null) {
            TickManager tickManager = world.getTickManager();
            return tickManager.getMillisPerTick() / 1000;
        }
        return 0.05f;
    }
    void calculateSpeed() {
        Vec3d cur = MCRiderMain.getRidingPlayer().getCameraPosVec(1);

        if (lastPos != null) {
            float dx = (float) (cur.x - lastPos.x);
            float dy = (float) (cur.y - lastPos.y);
            float dz = (float) (cur.z - lastPos.z);
            float distPerTick = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

            float tickInterval = getTickRate();
            if (tickInterval > 0f) {
                realSpeed = (distPerTick / tickInterval) * 3.6f;
            }
        }
        lastPos = cur;
    }
    void addToTargetFovBuffer(float newFov) {
        targetFovBuffer.add(newFov);

        if (targetFovBuffer.size() > 5) {
            targetFovBuffer.removeFirst();
        }
        filteredTargetFOV = Collections.max(targetFovBuffer);
    }
    void detectBooster() {
        if (client.player == null) return;

        // 나 자신의 effect 감지
        var effect = client.player.getStatusEffect(StatusEffects.DOLPHINS_GRACE);
        var boostState = MCRiderMain.getS2CValue(MCRiderMain.getRidingPlayer(), "state-boost");
        var exceedState = MCRiderMain.getS2CValue(MCRiderMain.getRidingPlayer(), "state-exceed");
        if (boostState == 0) boostState += exceedState;

        if (effect != null && effect.getAmplifier() == 169) {
            isUsingBooster = true;
        }
        else {
            if (MCRiderMain.kartEngine == 11) isUsingBooster = boostState > 1;
            else isUsingBooster = boostState > 0;
        }
    }
    public static float interpolate(float current, float target, float temporalGradient) {
        float tickInterval = getTickRate();

        float alpha = tickInterval * temporalGradient;
        return current + (target - current) * alpha;
    }
    public static float getCameraDistanceOffset(float originalDistance) {
        if (MCRiderConfig.INSTANCE.cameraMode == 0)
            return originalDistance;
        else if (client.options.getPerspective() == Perspective.FIRST_PERSON)
            return originalDistance;

        return (float) linearMap(cameraDistanceOffset, BASE_DISTANCE, 815, linearTransformTargetBaseDistance, 815) / 200;
    }
    public static float getCameraDistanceOffsetAtPrevTick(float originalDistance) {
        if (MCRiderConfig.INSTANCE.cameraMode == 0)
            return originalDistance;
        else if (client.options.getPerspective() == Perspective.FIRST_PERSON)
            return originalDistance;

        return (float) linearMap(cameraDistanceOffsetAtPrevTick, BASE_DISTANCE, 815, linearTransformTargetBaseDistance, 815) / 200;
    }
    // 기본 카메라 거리를 늘리기 위한 선형변환식
    static double linearMap(double x, double a, double b, double c, double d) {
        return c + ((x - a) / (b - a)) * (d - c);
    }
}