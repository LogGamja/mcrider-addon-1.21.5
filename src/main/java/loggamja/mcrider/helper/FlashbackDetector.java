package loggamja.mcrider.helper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Flashback(1.21.5용) 호환 레이어 By Claude Opus 5
 *
 * Flashback을 컴파일 의존성으로 추가하지 않기 위해 리플렉션으로만 접근한다.
 * Flashback이 없거나 내부 구조가 바뀌면 static 초기화에서 조용히 실패하고,
 * 모든 메서드가 false를 반환하므로 mcrider는 평소와 완전히 동일하게 동작한다.
 *
 * 참조 구조 (Flashback 0.39.5 for 1.21.5에서 직접 확인):
 *   com.moulberry.flashback.Flashback#isInReplay()                -> boolean (Flashback이 getFov를 가로채는 조건과 동일)
 *   com.moulberry.flashback.state.EditorStateManager#getCurrent() -> EditorState (리플레이 중이어도 null일 수 있음)
 *   com.moulberry.flashback.state.EditorState#replayVisuals       -> ReplayVisuals (public field)
 *   com.moulberry.flashback.visuals.ReplayVisuals#overrideFov     -> boolean (public field)
 *
 * 두 판정의 가용 여부를 따로 두는 이유: EditorState 쪽 구조만 바뀌어도 리플레이 판정까지 꺼지면
 * mcrider가 일반 플레이 경로로 빠져 유저의 FOV 옵션을 되돌리지 못한 채 남기게 된다.
 *
 * 시그니처까지 초기화 때 검증하는 이유: 여기는 프레임마다 도는 getFov 경로라, 구조가 바뀌면
 * 매 프레임 예외를 던지고 삼키며 조용히 렌더 스레드를 갉아먹는다.
 */
public final class FlashbackDetector {

    private static Method isInReplayMethod;
    private static Method getCurrentMethod;
    private static Field replayVisualsField;
    private static Field overrideFovField;

    private static boolean replayCheckAvailable = false;
    private static boolean fovOverrideCheckAvailable = false;

    private FlashbackDetector() {
    }

    static {
        try {
            Class<?> flashbackClass = findClass("com.moulberry.flashback.Flashback");
            isInReplayMethod = findStaticMethod(flashbackClass, "isInReplay", boolean.class);

            replayCheckAvailable = true;
        } catch (Throwable t) {
            replayCheckAvailable = false;
        }

        try {
            Class<?> managerClass = findClass("com.moulberry.flashback.state.EditorStateManager");
            Class<?> stateClass = findClass("com.moulberry.flashback.state.EditorState");
            Class<?> visualsClass = findClass("com.moulberry.flashback.visuals.ReplayVisuals");

            getCurrentMethod = findStaticMethod(managerClass, "getCurrent", stateClass);
            replayVisualsField = findField(stateClass, "replayVisuals", visualsClass);
            overrideFovField = findField(visualsClass, "overrideFov", boolean.class);

            fovOverrideCheckAvailable = true;
        } catch (Throwable t) {
            fovOverrideCheckAvailable = false;
        }
    }

    // initialize=false: 이 static 블록은 프레임 도중(첫 FOV 훅)에 도므로 Flashback 초기화를 유발하지 않는다
    private static Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, FlashbackDetector.class.getClassLoader());
    }

    private static Method findStaticMethod(Class<?> owner, String name, Class<?> returnType) throws NoSuchMethodException {
        Method method = owner.getMethod(name);

        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != returnType) {
            throw new NoSuchMethodException(owner.getName() + "#" + name);
        }
        return method;
    }

    // static 여부는 보지 않는다: static이 되어도 get(instance) 읽기는 그대로 동작한다
    private static Field findField(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        Field field = owner.getField(name);

        if (field.getType() != type) {
            throw new NoSuchFieldException(owner.getName() + "#" + name);
        }
        return field;
    }

    public static boolean isInReplay() {
        if (!replayCheckAvailable) return false;

        try {
            return (boolean) isInReplayMethod.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isOverridingFov() {
        if (!fovOverrideCheckAvailable) return false;

        try {
            Object state = getCurrentMethod.invoke(null);
            if (state == null) return false;

            Object visuals = replayVisualsField.get(state);
            if (visuals == null) return false;
            return (boolean) overrideFovField.get(visuals);
        } catch (Throwable t) {
            return false;
        }
    }
}
