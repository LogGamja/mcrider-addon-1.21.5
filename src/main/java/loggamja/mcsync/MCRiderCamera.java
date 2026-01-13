package loggamja.mcsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.tick.TickManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class MCRiderCamera implements ModInitializer {
    static MinecraftClient client = MinecraftClient.getInstance();

    Vec3d lastPos = null;
    public static float realSpeed = 0f;

    @Override
    public void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            onClientTickEnd();
        });
    }

    void onClientTickEnd() {
        if (!MCRiderMain.isPlayingInGame() || !MCRiderMain.isRidingKart || MCRiderConfig.INSTANCE.cameraMode == 0) return;

        calculateSpeed();
    }
    static float getTickRate() {
        final MinecraftClient client = MinecraftClient.getInstance();
        final ClientWorld world = client.world;

        if (world != null) {
            TickManager tickManager = world.getTickManager();
            return tickManager.getMillisPerTick() / 1000;
        }
        return 0.05f;
    }
    void calculateSpeed() {
        Vec3d cur = MCRiderMain.getRidingPlayer().getCameraPosVec(1);

        if (lastPos != null) {
            float dx = (float) (cur.x - lastPos.x);
            float dy = (float) (cur.y - lastPos.y);
            float dz = (float) (cur.z - lastPos.z);
            float distPerTick = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

            realSpeed = distPerTick * 20f * 3.6f;
        }
        lastPos = cur;
    }
}