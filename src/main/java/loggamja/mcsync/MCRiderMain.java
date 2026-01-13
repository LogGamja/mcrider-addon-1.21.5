package loggamja.mcsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;

import java.util.*;

public class MCRiderMain implements ModInitializer {
    public static List<Float> playerYawBuffer = new ArrayList<>(Collections.nCopies(1, 0f));

    public static boolean isRidingKart = false;

    public static float direction = 0;

    static MCRiderConfig cfg;
    static MinecraftClient client = MinecraftClient.getInstance();

    @Override
    public void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            onClientTickEnd();
        });

        MCRiderConfig.INSTANCE.load();
        cfg = MCRiderConfig.INSTANCE;
    }
    //MinecraftClient mc = MinecraftClient.getInstance();
    //mc.getWindow().setWindowedSize(1024, 768);
    void onClientTickEnd() {
        if (!isPlayingInGame()) return;

        updateRidingState();

        if (isRidingKart && getRidingPlayer() == client.player && cfg.MCRiderRotationOption > 0) {
            Entity kartMobil = getRidingPlayer().getRootVehicle();
            if (MCRiderCamera.realSpeed > 0.00001) {
                simulateKartRotation(kartMobil);
            }
        }
    }
    public static boolean isPlayingInGame() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.world != null && client.player != null && !(client.getCameraEntity() == null) &&  !client.isPaused();
    }
    public static PlayerEntity getRidingPlayer() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.getCameraEntity().isPlayer()) {
            return (PlayerEntity) client.getCameraEntity();
        }
        return client.player;
    }
    void updateRidingState() {
        if (client.player == null || client.player.getRootVehicle() == client.player){
            if (isRidingKart) {
                isRidingKart = false;
                autoThirdPerson();
            }
            return;
        }
        if (client.player.getVehicle() != null && client.player.getVehicle().getType() == EntityType.COD || client.player.getVehicle().getType() == EntityType.ARMADILLO) {
            if (client.player.getRootVehicle().getType() == EntityType.ARMOR_STAND || client.player.getRootVehicle().getType() == EntityType.ITEM_DISPLAY) {
                if (!isRidingKart) {
                    isRidingKart = true;
                    autoThirdPerson();
                }
            }
        }
    }
    void autoThirdPerson() {
        if (cfg.useAutoThirdPerson) {
            if (isRidingKart) {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
            else {
                client.options.setPerspective(Perspective.FIRST_PERSON);
            }
        }
    }
    void simulateKartRotation(Entity kartMobil) {
        if (kartMobil == getRidingPlayer()) return;

        float angleToRotate = calculateRotation(kartMobil.getYaw());
        rotateKartModel(kartMobil, angleToRotate);
    }
    float calculateRotation(float directionYaw) {
        var playerYaw = playerYawBuffer.getFirst();

        var deltaAngle = normalizeAngle(playerYaw - directionYaw);
        var overShootAngle = getOverShootAngle(deltaAngle);

        return normalizeAngle(playerYaw + overShootAngle);
    }
    void rotateKartModel(Entity entity, float angleToRotate) {
        List<Entity> models = entity.getPassengerList();
        for (var j: models) {
            j.setYaw(angleToRotate);
        }
    }
    float getOverShootAngle(float deltaAngle) {
        var overShootAngle = deltaAngle;

        if (deltaAngle <= -55) overShootAngle = -110f - deltaAngle;
        if (55 <= deltaAngle) overShootAngle = 110f - deltaAngle;

        return overShootAngle / 2;
    }
    float normalizeAngle(float angle) {
        if (angle > 0) {
            return ((angle + 180f) % 360f) - 180f;
        }
        else {
            return -(((-angle + 180f) % 360f) - 180f);
        }
    }
    public static void onFrameRender() {
        if (!isPlayingInGame()) return;

        var playerYaw = getRidingPlayer().getYaw();
        addToPlayerYawBuffer(playerYaw);
    }
    static void addToPlayerYawBuffer(Float playerYaw) {
        playerYawBuffer.add(playerYaw);

        long delay = getNearestFrame(25);
        if (cfg.MCRiderRotationOption == 2) {
            delay = 1;
        }

        while (playerYawBuffer.size() > delay) {
            playerYawBuffer.removeFirst();
        }
    }
    static long getNearestFrame(long criteriaTick) {
        float fps = client.getCurrentFps();

        float frameMs = 1000 / fps;
        long k = Math.round(criteriaTick / frameMs);
        return Math.max(1L, k);
    }
}