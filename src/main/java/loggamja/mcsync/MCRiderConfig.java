package loggamja.mcsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class MCRiderConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "mcsync-config.json");

    public int MCRiderRotationOption = 2;
    public boolean MCRiderPacketAcceleration = true;
    public int MCRiderRadarOption = 1;
    public boolean useNoclipCamera = true;
    public boolean useDraftGauge = true;
    public boolean useAutoThirdPerson = true;
    public int cameraMode = 2;

    public int MCRiderFOV = 90;
    public int MCRiderFOVEffects = 80;

    // 싱글톤
    public static final MCRiderConfig INSTANCE = new MCRiderConfig();

    public void load() {
        if (!CONFIG_FILE.exists()) return;
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            MCRiderConfig loaded = GSON.fromJson(reader, MCRiderConfig.class);
            if (loaded != null) {
                this.MCRiderRotationOption = loaded.MCRiderRotationOption;
                this.MCRiderPacketAcceleration = loaded.MCRiderPacketAcceleration;
                this.MCRiderRadarOption = loaded.MCRiderRadarOption;
                this.useNoclipCamera = loaded.useNoclipCamera;
                this.useDraftGauge = loaded.useDraftGauge;
                this.useAutoThirdPerson = loaded.useAutoThirdPerson;
                this.cameraMode = loaded.cameraMode;

                this.MCRiderFOV = loaded.MCRiderFOV;
                this.MCRiderFOVEffects = loaded.MCRiderFOVEffects;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
