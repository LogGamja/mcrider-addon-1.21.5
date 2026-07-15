package loggamja.mcrider.api;

import loggamja.mcrider.option.MCRiderConfig;
import loggamja.mcrider.option.MCRiderOptionTable;
import loggamja.mcrider.option.MCRiderSettingScreen;
import net.minecraft.client.MinecraftClient;

import java.util.OptionalDouble;
import java.util.OptionalInt;

// 다른 모드가 MCRider를 제어하기 위한 공개 API
public final class MCRiderAPI {
    private MCRiderAPI() {}

    private static volatile boolean menuButtonHidden = false;

    // ESC 메뉴의 설정 버튼을 숨긴다 (래치형 — 해제하기 전까지 계속 숨겨진 상태 유지)
    public static void setMenuButtonHidden(boolean hidden) {
        menuButtonHidden = hidden;
    }

    public static boolean isMenuButtonHidden() {
        return menuButtonHidden;
    }

    // id로 지정한 토글 옵션 값을 강제로 바꾼다. 존재하지 않는 id면 false 반환
    // 실제 쓰기/저장은 클라이언트 메인 스레드로 마샬링해 게임 루프와의 경합을 방지한다
    public static boolean setToggleOption(String id, int value) {
        MCRiderOptionTable.ToggleDef def = MCRiderOptionTable.findToggle(id);
        if (def == null) return false;

        int clamped = Math.max(0, Math.min(value, def.stateCount() - 1));
        MinecraftClient.getInstance().execute(() -> {
            def.setter().accept(clamped);
            MCRiderConfig.INSTANCE.save();
        });
        return true;
    }

    // id로 지정한 토글 옵션의 현재 값을 읽는다. 존재하지 않는 id면 빈 값 반환
    public static OptionalInt getToggleOption(String id) {
        MCRiderOptionTable.ToggleDef def = MCRiderOptionTable.findToggle(id);
        return def == null ? OptionalInt.empty() : OptionalInt.of(def.getter().getAsInt());
    }

    // id로 지정한 슬라이더 옵션 값을 강제로 바꾼다. 존재하지 않는 id면 false 반환
    // 실제 쓰기/저장은 클라이언트 메인 스레드로 마샬링해 게임 루프와의 경합을 방지한다
    public static boolean setSliderOption(String id, float value) {
        MCRiderOptionTable.SliderDef def = MCRiderOptionTable.findSlider(id);
        if (def == null) return false;

        float clamped = (float) Math.max(def.min(), Math.min(value, def.max()));
        MinecraftClient.getInstance().execute(() -> {
            def.setter().accept(clamped);
            MCRiderConfig.INSTANCE.save();
        });
        return true;
    }

    // id로 지정한 슬라이더 옵션의 현재 값을 읽는다. 존재하지 않는 id면 빈 값 반환
    public static OptionalDouble getSliderOption(String id) {
        MCRiderOptionTable.SliderDef def = MCRiderOptionTable.findSlider(id);
        return def == null ? OptionalDouble.empty() : OptionalDouble.of(def.getter().getAsDouble());
    }

    // 모든 옵션을 기본값으로 되돌린다. 실제 쓰기/저장은 클라이언트 메인 스레드로 마샬링한다
    public static void resetToDefaults() {
        MinecraftClient.getInstance().execute(() -> {
            MCRiderConfig.INSTANCE = new MCRiderConfig();
            MCRiderConfig.INSTANCE.save();
        });
    }

    // 현재 화면 위에 MCRider 설정 화면을 연다 (ESC 메뉴를 거치지 않아도 됨)
    // setScreen은 메인 스레드 전용이므로 마샬링한다. currentScreen은 실행 시점(메인 스레드)에 읽어 정확한 화면을 parent로 삼는다
    public static void openSettings() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> client.setScreen(new MCRiderSettingScreen(client.currentScreen)));
    }
}
