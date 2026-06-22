package loggamja.mcrider;

import loggamja.mcrider.helper.EntityRollManager;
import loggamja.mcrider.helper.SpringSimulator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

public class MCRiderSuspension implements ClientModInitializer {
    List<Float> steerGradientBuffer = new ArrayList<>();

    private static boolean isDrifting;
    private static float driftAngle = 0;

    private static final double DT  = 0.05;
    private static final double FREQ  = 1.66; // 공진주파수 (Hz)
    private static final double Q     = 1;    // 공진 팩터

    float prevPlayerYaw = 0;

    // 스윙 애니메이션 재생용 펄스
    private static final int SWING_ANIMATION_TICKS = 6;
    private int swingAnimationTicks = 0;

    // 드리프트가 시작된 직후 n틱을 세는 변수
    private static final int DRIFT_START_TICKS = 10;
    private int driftJustStartedTicks = 0;

    // 질량체의 현재 상태(위치/속도). 매 틱 갱신.
    private final SpringSimulator.State state = new SpringSimulator.State();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick());
    }
    private void onClientTick() {
        if (!MCRiderMain.isPlayingInGame()) return;

        var player = MCRiderMain.getRidingPlayer();
        var kart = player.getRootVehicle();
        List<Entity> passengers = kart.getPassengerList();

        var isBike = MCRiderMain.getS2CValue(MCRiderMain.getRidingPlayer(), "data-is-bike");

        if (MCRiderConfig.INSTANCE.suspensionEffect == 0) {
            EntityRollManager.setRoll(player.getUuid(), 0f, 1);

            for (var i : passengers) {
                if (MCRiderMain.hasCertainName(i, "mcrider-modelsaddle")) {
                    for (var j : i.getPassengerList()) {
                        EntityRollManager.setRoll(j.getUuid(), 0f, 1);
                    }
                }
            }
            return;
        }

        var playerYaw = player.getYaw();
        steerGradientBuffer.add(Math.abs(prevPlayerYaw - playerYaw));
        if (steerGradientBuffer.size() > 5) steerGradientBuffer.removeFirst();
        prevPlayerYaw = playerYaw;

        boolean isPlayingSwingAnimation = swingAnimationTicks > 0;
        if (swingAnimationTicks > 0) swingAnimationTicks--;

        boolean hasDriftStarted = driftJustStartedTicks > 0;
        if (driftJustStartedTicks > 0) driftJustStartedTicks--;

        var filteredSteerGradient = steerGradientBuffer.stream().mapToDouble(Float::floatValue)
                .average()
                .orElse(0.0);

        boolean disableSwingAnimation = isBike == 1 && MCRiderConfig.INSTANCE.bikeSuspension >= 2;
        boolean isDriftingTemp = detectDriftState(kart);
        if (isDriftingTemp != isDrifting) {
            isDrifting = isDriftingTemp;

            if (isDrifting) {
                driftJustStartedTicks = DRIFT_START_TICKS;
            }
            else {
                if (filteredSteerGradient > 2 && !isPlayingSwingAnimation && !disableSwingAnimation) {
                    swingAnimationTicks = SWING_ANIMATION_TICKS;
                }
            }
        }
        // 투드맆
        //if (isDrifting && filteredSteerGradient > 10 && !isPlayingSwingAnimation && !hasDriftStarted) {
        //  swingAnimationTicks = SWING_ANIMATION_TICKS;
        //}

        var clampedDriftAngle = driftAngle;
        if (clampedDriftAngle > 90) clampedDriftAngle = (180 - clampedDriftAngle) / 2;
        if (clampedDriftAngle < -90) clampedDriftAngle = (-180 - clampedDriftAngle) / 2;

        final double a = (45 * 2 / Math.PI);
        final double b = 0.5;
        clampedDriftAngle = (float) (a * Math.atan(b / a * clampedDriftAngle));
        if (isPlayingSwingAnimation) clampedDriftAngle = 0;

        // 스프링 물리 통과
        SpringSimulator.step(state, DT, FREQ, Q, -(clampedDriftAngle / 2f));
        var modelRollValue = Math.toDegrees(state.x);

        // 바이크 옵션
        if (isBike == 1) {
            if (MCRiderConfig.INSTANCE.bikeSuspension == 0) {
                modelRollValue = 0;
            }
            else if (MCRiderConfig.INSTANCE.bikeSuspension == 1) {
                // 4-Wheel: 바이크가 아닌 것처럼 처리 (카트와 동일)
            }
            else if (MCRiderConfig.INSTANCE.bikeSuspension == 2) {
                modelRollValue *= -1;
            }
            else if (MCRiderConfig.INSTANCE.bikeSuspension == 3) {
                modelRollValue *= -5;
            }
        }

        // 실제 카트바디에 적용 + 동시에 Direction 얻기
        float moveDirection = 0f;
        float steerDirection = 0f;
        for (var i : passengers) {
            if (MCRiderMain.hasCertainName(i, "mcrider-modelsaddle")) {
                for (var j : i.getPassengerList()) {
                    EntityRollManager.setRoll(j.getUuid(), (float) modelRollValue, 1);
                }
            }
            else if (MCRiderMain.hasCertainName(i, "mcrider-direction")) {
                moveDirection = i.getYaw();
            }
            else if (MCRiderMain.hasCertainName(i, "mcrider-datacarrier")) {
                steerDirection = i.getYaw();
            }
        }
        EntityRollManager.setRoll(player.getUuid(), (float) modelRollValue, 1);
        driftAngle = MathHelper.subtractAngles(moveDirection, steerDirection);
    }
    boolean detectDriftState(Entity kart) {
        if (kart.isPlayer()) return false;

        List<Entity> passengers = kart.getPassengerList();
        for (var i : passengers) {
            if (!MCRiderMain.hasCertainName(i, "mcrider-modelsaddle")) continue;

            for (var j : i.getPassengerList()) {
                if (!MCRiderMain.hasCertainName(j, "mcrider-drift-effect") || !isDisplayEntity(j)) continue;

                return ((DisplayEntity) j).getViewRange() > 0;
            }
        }
        return false;
    }
    static boolean isDisplayEntity(Entity entity) {
        return entity.getType() == EntityType.ITEM_DISPLAY
                || entity.getType() == EntityType.BLOCK_DISPLAY
                || entity.getType() == EntityType.TEXT_DISPLAY;
    }
}