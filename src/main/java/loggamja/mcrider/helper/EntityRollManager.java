package loggamja.mcrider.helper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EntityRollManager {
    // 클라이언트 틱/렌더 콜백 전부 단일 클라이언트 스레드에서만 호출되므로 동시성 안전장치가 불필요하다.
    private static final Map<UUID, RollState> rollStates = new HashMap<>();

    public static void setRoll(UUID entityId, float targetRollDeg, int durationTicks) {
        float current = getCurrentRoll(entityId);
        rollStates.put(entityId, new RollState(
                current,
                targetRollDeg,
                System.currentTimeMillis(),
                durationTicks * 50L
        ));
    }

    public static float getCurrentRoll(UUID entityId) {
        RollState state = rollStates.get(entityId);
        if (state == null) return 0f;

        long now = System.currentTimeMillis();
        long elapsed = now - state.startTime;

        if (elapsed >= state.duration) {
            return state.targetRoll;
        }

        float t = (float) elapsed / state.duration;
        // smoothstep interpolation
        t = t * t * (3f - 2f * t);
        return state.startRoll + (state.targetRoll - state.startRoll) * t;
    }

    public static void remove(UUID entityId) {
        rollStates.remove(entityId);
    }

    public static void clear() {
        rollStates.clear();
    }

    public record RollState(
            float startRoll,
            float targetRoll,
            long startTime,
            long duration
    ) {}
}
