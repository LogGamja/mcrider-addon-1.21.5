package loggamja.mcrider.option;

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
    public int useMinimap = 0;
    public boolean useNoclipCamera = true;
    public boolean useDraftGauge = true;
    public boolean useAutoThirdPerson = true;
    public int cameraMode = 2;

    public int suspensionEffect = 0;
    public int bikeSuspension = 0;

    public int MCRiderFOV = 90;
    public int MCRiderFOVEffects = 80;

    // 싱글톤 (load()가 역직렬화된 인스턴스로 통째로 교체하므로 final이 아님)
    public static MCRiderConfig INSTANCE = new MCRiderConfig();

    public void load() {
        if (!CONFIG_FILE.exists()) return;
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            MCRiderConfig loaded = GSON.fromJson(reader, MCRiderConfig.class);
            if (loaded != null) {
                INSTANCE = loaded;
                MCRiderOptionTable.clampAllToggles();
                MCRiderOptionTable.clampAllSliders();
            }
        } catch (IOException e) {
            LOGGER.error("[MCRider] 설정 파일을 불러오는 데 실패했습니다.", e);
        } catch (com.google.gson.JsonParseException e) {
            LOGGER.error("[MCRider] 설정 파일 형식이 올바르지 않아 기본값으로 대체합니다.", e);
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            LOGGER.error("[MCRider] 설정 파일을 저장하는 데 실패했습니다.", e);
        }
    }
}
