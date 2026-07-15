package loggamja.mcrider.option;

import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

// 토글/슬라이더 옵션의 번역 키 + config 필드 getter/setter 정의 테이블
// MCRiderSetting(화면)과 MCRiderConfig(로드 시 범위 클램프), api.MCRiderAPI(외부 모드 연동) 모두 이 테이블 하나만 보고 동작하므로
// 옵션을 추가 / 변경할 때 이 파일 한 곳만 수정하면 된다
public final class MCRiderOptionTable {
    private MCRiderOptionTable() {}

    public record ToggleDef(String id, String[] labelKeys, String tooltipKey, IntSupplier getter, IntConsumer setter) {
        public int stateCount() { return labelKeys.length; }
    }

    public record SliderDef(String id, String labelKey, String tooltipKey, double min, double max,
                      DoubleSupplier getter, Consumer<Float> setter) {}

    public static final ToggleDef[] TOGGLES = {
            new ToggleDef(
                    "steer_boost",
                    new String[]{"mcrider.option.steer_boost.off", "mcrider.option.steer_boost.normal", "mcrider.option.steer_boost.extreme"},
                    "mcrider.tooltip.steer_boost",
                    () -> MCRiderConfig.INSTANCE.MCRiderRotationOption,
                    v -> MCRiderConfig.INSTANCE.MCRiderRotationOption = v
            ),
            new ToggleDef(
                    "packet_boost",
                    new String[]{"mcrider.option.packet_boost.off", "mcrider.option.packet_boost.on"},
                    "mcrider.tooltip.packet_boost",
                    () -> MCRiderConfig.INSTANCE.MCRiderPacketAcceleration ? 1 : 0,
                    v -> MCRiderConfig.INSTANCE.MCRiderPacketAcceleration = v != 0
            ),
            new ToggleDef(
                    "enemy_radar",
                    new String[]{"mcrider.option.enemy_radar.off", "mcrider.option.enemy_radar.on"},
                    "mcrider.tooltip.enemy_radar",
                    () -> MCRiderConfig.INSTANCE.MCRiderRadarOption,
                    v -> MCRiderConfig.INSTANCE.MCRiderRadarOption = v
            ),
            new ToggleDef(
                    "draft_gauge",
                    new String[]{"mcrider.option.draft_gauge.off", "mcrider.option.draft_gauge.on"},
                    "mcrider.tooltip.draft_gauge",
                    () -> MCRiderConfig.INSTANCE.useDraftGauge ? 1 : 0,
                    v -> MCRiderConfig.INSTANCE.useDraftGauge = v != 0
            ),
            new ToggleDef(
                    "auto_third_person",
                    new String[]{"mcrider.option.auto_third_person.off", "mcrider.option.auto_third_person.on"},
                    "mcrider.tooltip.auto_third_person",
                    () -> MCRiderConfig.INSTANCE.useAutoThirdPerson ? 1 : 0,
                    v -> MCRiderConfig.INSTANCE.useAutoThirdPerson = v != 0
            ),
            new ToggleDef(
                    "noclip_camera",
                    new String[]{"mcrider.option.noclip_camera.off", "mcrider.option.noclip_camera.on"},
                    "mcrider.tooltip.noclip_camera",
                    () -> MCRiderConfig.INSTANCE.useNoclipCamera ? 1 : 0,
                    v -> MCRiderConfig.INSTANCE.useNoclipCamera = v != 0
            ),
            new ToggleDef(
                    "camera_mode",
                    new String[]{"mcrider.option.camera_mode.default", "mcrider.option.camera_mode.balance", "mcrider.option.camera_mode.normal", "mcrider.option.camera_mode.kartrider"},
                    "mcrider.tooltip.camera_mode",
                    () -> MCRiderConfig.INSTANCE.cameraMode,
                    v -> MCRiderConfig.INSTANCE.cameraMode = v
            ),
            new ToggleDef(
                    "suspension_effect",
                    new String[]{"mcrider.option.suspension_effect.off", "mcrider.option.suspension_effect.kart", "mcrider.option.suspension_effect.kart_and_camera"},
                    "mcrider.tooltip.suspension_effect",
                    () -> MCRiderConfig.INSTANCE.suspensionEffect,
                    v -> MCRiderConfig.INSTANCE.suspensionEffect = v
            ),
            new ToggleDef(
                    "bike_suspension",
                    new String[]{"mcrider.option.bike_suspension.default", "mcrider.option.bike_suspension.four_wheel", "mcrider.option.bike_suspension.realistic", "mcrider.option.bike_suspension.extreme"},
                    "mcrider.tooltip.bike_suspension",
                    () -> MCRiderConfig.INSTANCE.bikeSuspension,
                    v -> MCRiderConfig.INSTANCE.bikeSuspension = v
            ),
            new ToggleDef(
                    "track_minimap",
                    new String[]{
                            "mcrider.option.track_minimap.off",
                            "mcrider.option.track_minimap.bottom_left",
                            "mcrider.option.track_minimap.bottom_right",
                            "mcrider.option.track_minimap.left_middle",
                            "mcrider.option.track_minimap.right_middle",
                            "mcrider.option.track_minimap.top_left",
                            "mcrider.option.track_minimap.top_right",
                            "mcrider.option.track_minimap.debug"
                    },
                    "mcrider.tooltip.track_minimap",
                    () -> MCRiderConfig.INSTANCE.useMinimap,
                    v -> MCRiderConfig.INSTANCE.useMinimap = v
            ),
    };

    public static final SliderDef[] SLIDERS = {
            new SliderDef(
                    "riding_fov",
                    "mcrider.slider.riding_fov", "mcrider.slider.riding_fov.tooltip", 30.0, 110.0,
                    () -> MCRiderConfig.INSTANCE.MCRiderFOV,
                    v -> MCRiderConfig.INSTANCE.MCRiderFOV = Math.round(v)
            ),
            new SliderDef(
                    "riding_fov_effects",
                    "mcrider.slider.riding_fov_effects", "mcrider.slider.riding_fov_effects.tooltip", 0.0, 100.0,
                    () -> MCRiderConfig.INSTANCE.MCRiderFOVEffects,
                    v -> MCRiderConfig.INSTANCE.MCRiderFOVEffects = Math.round(v)
            ),
    };

    public static ToggleDef findToggle(String id) {
        for (ToggleDef def : TOGGLES) {
            if (def.id().equals(id)) return def;
        }
        return null;
    }

    public static SliderDef findSlider(String id) {
        for (SliderDef def : SLIDERS) {
            if (def.id().equals(id)) return def;
        }
        return null;
    }

    static void clampAllToggles() {
        for (ToggleDef def : TOGGLES) {
            int current = def.getter().getAsInt();
            int clamped = Math.max(0, Math.min(current, def.stateCount() - 1));
            if (clamped != current) def.setter().accept(clamped);
        }
    }
    static void clampAllSliders() {
        for (SliderDef def : SLIDERS) {
            double current = def.getter().getAsDouble();
            double clamped = Math.max(def.min(), Math.min(current, def.max()));
            if (clamped != current) def.setter().accept((float) clamped);
        }
    }
}
