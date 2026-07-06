package loggamja.mcrider;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class MCRiderConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcrider");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "mcrider-config.json");

    public int MCRiderRotationOption = 2;
    public boolean MCRiderPacketAcceleration = true;
    public int MCRiderRadarOption = 1;
    public int useMinimap = 0; // 0=꺼짐, 1=켜짐, 2=디버그(색깔별 표시)
    public boolean useNoclipCamera = true;
    public boolean useDraftGauge = true;
    public boolean useAutoThirdPerson = true;
    public int cameraMode = 2;

    public int suspensionEffect = 0;
    public int bikeSuspension = 0;

    public int MCRiderFOV = 90;
    public int MCRiderFOVEffects = 80;

    // 싱글톤
    public static final MCRiderConfig INSTANCE = new MCRiderConfig();

    public void load() {
        if (!CONFIG_FILE.exists()) return;
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            MCRiderConfig loaded = GSON.fromJson(reader, MCRiderConfig.class);
            if (loaded != null) copyFrom(loaded);
        } catch (IOException e) {
            LOGGER.error("[MCRider] 설정 파일을 불러오는 데 실패했습니다.", e);
        } catch (com.google.gson.JsonParseException e) {
            // 예: useMinimap이 boolean → int로 바뀌기 전 저장된 옛 설정 파일처럼, 필드 타입이
            // 안 맞는 경우 Gson이 JsonSyntaxException(RuntimeException 계열)을 던진다.
            // 잡지 않으면 모드 초기화 자체가 죽으므로, 로그만 남기고 기본값으로 계속 진행한다.
            LOGGER.error("[MCRider] 설정 파일 형식이 올바르지 않아 기본값으로 대체합니다.", e);
        }
    }

    private void copyFrom(MCRiderConfig other) {
        this.MCRiderRotationOption     = other.MCRiderRotationOption;
        this.MCRiderPacketAcceleration = other.MCRiderPacketAcceleration;
        this.MCRiderRadarOption        = other.MCRiderRadarOption;
        this.useMinimap                = other.useMinimap;
        this.useNoclipCamera           = other.useNoclipCamera;
        this.useDraftGauge             = other.useDraftGauge;
        this.useAutoThirdPerson        = other.useAutoThirdPerson;
        this.cameraMode                = other.cameraMode;

        this.suspensionEffect          = other.suspensionEffect;
        this.bikeSuspension            = other.bikeSuspension;

        this.MCRiderFOV                = other.MCRiderFOV;
        this.MCRiderFOVEffects         = other.MCRiderFOVEffects;
    }
    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            LOGGER.error("[MCRider] 설정 파일을 저장하는 데 실패했습니다.", e);
        }
    }
}
