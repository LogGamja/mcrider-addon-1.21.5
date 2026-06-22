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
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "mcsync-config.json");

    public int MCRiderRotationOption = 2;
    public boolean MCRiderPacketAcceleration = true;
    public int MCRiderRadarOption = 1;
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
        }
    }

    private void copyFrom(MCRiderConfig other) {
        this.MCRiderRotationOption     = other.MCRiderRotationOption;
        this.MCRiderPacketAcceleration = other.MCRiderPacketAcceleration;
        this.MCRiderRadarOption        = other.MCRiderRadarOption;
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
