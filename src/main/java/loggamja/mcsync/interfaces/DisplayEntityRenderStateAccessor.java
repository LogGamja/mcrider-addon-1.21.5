package loggamja.mcsync.interfaces;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.UUID;

public interface DisplayEntityRenderStateAccessor {
    UUID mcsync_getUuid();
    void mcsync_setUuid(UUID uuid);
}