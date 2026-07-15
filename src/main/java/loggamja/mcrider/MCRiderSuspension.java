package loggamja.mcrider;

import loggamja.mcrider.helper.EntityRollManager;
import loggamja.mcrider.helper.SpringSimulator;
import loggamja.mcrider.option.MCRiderConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MCRiderSuspension implements ClientModInitializer {
    public static float pivotYOffset = 0.1f;

    private static final double DT  = 0.05;
    private static final double FREQ  = 1.66; // 공진주파수 (Hz)
    private static final double Q     = 1;    // 공진 팩터

    private static final int SWING_ANIMATION_TICKS = 6;
    private static final int DRIFT_START_TICKS = 10;

    private static final class SuspensionState {
        final List<Float> steerGradientBuffer = new ArrayList<>();
        float prevPlayerYaw = 0f;
        boolean isDrifting = false;
        int swingAnimationTicks = 0;
        int driftJustStartedTicks = 0;
        final SpringSimulator.SpringState spring = new SpringSimulator.SpringState();
        long lastSeenTick = 0L;
    }

    private static final Map<UUID, SuspensionState> states = new HashMap<>();
    private static long tickCounter = 0L;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick());

        // 무조건 렌더 스레드로 예약
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            client.execute(() -> {
                EntityRollManager.clear();
                clearStates();
            });
        });
    }

    private void onClientTick() {
        if (!MCRiderMain.isPlayingInGame()) return;

        var world = MinecraftClient.getInstance().world;
        if (world == null) return;

        if (MCRiderConfig.INSTANCE.suspensionEffect == 0) {
            // 대상별 0 되돌리기 순회 대신 통째로 비우기
            if (!states.isEmpty() || !EntityRollManager.isEmpty()) {
                states.clear();
                EntityRollManager.clear();
            }
            return;
        }

        tickCounter++;
        int processed = 0;
        for (PlayerEntity player : world.getPlayers()) {
            if (!MCRiderMain.isRidingKart(player)) continue;

            SuspensionState st = states.computeIfAbsent(player.getUuid(), k -> {
                SuspensionState s = new SuspensionState();
                s.prevPlayerYaw = player.getYaw(); // 재진입 시 가짜 steerGradient 스파이크 방지
                return s;
            });
            st.lastSeenTick = tickCounter;
            processPlayer(player, st);
            processed++;
        }

        // 이번 틱에 카트를 타지 않은 플레이어의 상태 제거
        if (states.size() > processed) {
            final long tc = tickCounter;
            states.entrySet().removeIf(e -> {
                if (e.getValue().lastSeenTick != tc) {
                    EntityRollManager.remove(e.getKey());
                    return true;
                }
                return false;
            });
        }
    }

    private void processPlayer(PlayerEntity player, SuspensionState st) {
        Entity kart = player.getRootVehicle();
        List<Entity> passengers = kart.getPassengerList();

        int isBike = MCRiderMain.getS2CValue(player, "data-is-bike");
        boolean isModelRotateAllowed = MCRiderMain.getAllowModelRotation(kart, player);

        float playerYaw = player.getYaw();

        float wrappedDegree = Math.abs(MathHelper.wrapDegrees(st.prevPlayerYaw - playerYaw));
        st.steerGradientBuffer.add(wrappedDegree);
        if (st.steerGradientBuffer.size() > 5) st.steerGradientBuffer.removeFirst();
        st.prevPlayerYaw = playerYaw;

        boolean wasPlayingSwingAnimation = st.swingAnimationTicks > 0;
        if (st.swingAnimationTicks > 0) st.swingAnimationTicks--;
        if (st.driftJustStartedTicks > 0) st.driftJustStartedTicks--;

        double steerGradientSum = 0.0;
        for (int i = 0; i < st.steerGradientBuffer.size(); i++) {
            steerGradientSum += st.steerGradientBuffer.get(i);
        }
        double filteredSteerGradient = steerGradientSum / st.steerGradientBuffer.size();

        boolean disableSwingAnimation = isBike == 1 && MCRiderConfig.INSTANCE.bikeSuspension >= 2;
        boolean isDriftingTemp = detectDriftState(kart);
        if (isDriftingTemp != st.isDrifting) {
            st.isDrifting = isDriftingTemp;

            if (st.isDrifting) {
                st.driftJustStartedTicks = DRIFT_START_TICKS;
            }
            else {
                if (filteredSteerGradient > 2 && !wasPlayingSwingAnimation && !disableSwingAnimation) {
                    st.swingAnimationTicks = SWING_ANIMATION_TICKS;
                }
            }
        }

        boolean isPlayingSwingAnimation = st.swingAnimationTicks > 0;

        float moveDirection = 0f;
        float steerDirection = 0f;
        for (var i : passengers) {
            if (MCRiderMain.hasCertainName(i, "mcrider-direction")) {
                moveDirection = i.getYaw();
            }
            else if (MCRiderMain.hasCertainName(i, "mcrider-datacarrier")) {
                steerDirection = i.getYaw();
            }
        }
        var clampedDriftAngle = MathHelper.subtractAngles(moveDirection, steerDirection);

        if (clampedDriftAngle > 90) clampedDriftAngle = 180 - clampedDriftAngle;
        if (clampedDriftAngle < -90) clampedDriftAngle = -180 - clampedDriftAngle;

        final double a = (50 * 2 / Math.PI);
        final double b = 0.5;
        clampedDriftAngle = (float) (a * Math.atan(b / a * clampedDriftAngle));

        if (isBike == 1) {
            if (MCRiderConfig.INSTANCE.bikeSuspension == 3) {
                clampedDriftAngle *= 5;
            }
        }

        if (isPlayingSwingAnimation || !isModelRotateAllowed) clampedDriftAngle = 0;

        SpringSimulator.step(st.spring, DT, FREQ, Q, -(clampedDriftAngle / 2f));
        double modelRollValue = Math.toDegrees(st.spring.x);

        if (isBike == 1) {
            if (MCRiderConfig.INSTANCE.bikeSuspension == 0) {
                modelRollValue = 0;
            }
            else if (MCRiderConfig.INSTANCE.bikeSuspension == 1) {
                // 4-Wheel: 바이크가 아닌 것처럼 처리 (카트와 동일)
            }
            else {
                modelRollValue *= -1;
            }
        }

        for (var i : passengers) {
            if (MCRiderMain.hasCertainName(i, "mcrider-modelsaddle")) {
                for (var j : i.getPassengerList()) {
                    EntityRollManager.setRoll(j.getUuid(), (float) modelRollValue, 1);
                }
            }
        }
        EntityRollManager.setRoll(player.getUuid(), (float) modelRollValue, 1);
    }

    static boolean detectDriftState(Entity kart) {
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
    public static void clearStates() {
        states.clear();
    }
}
