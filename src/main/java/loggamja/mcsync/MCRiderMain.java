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
    String currentSaddleType = "none";
    public static List<Float> playerYawBuffer = new ArrayList<>(Collections.nCopies(1, 0f));

    public static boolean isRidingKart = false;

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

        Entity vehicle = getRidingPlayer().getVehicle();
        currentSaddleType = getSaddleType(vehicle);

        updateRidingState();

        if (isRidingKart && getRidingPlayer() == client.player && cfg.MCRiderRotationOption > 0) {
            Entity kartMobil = getRidingPlayer().getRootVehicle();
            if (!hasCertainName(kartMobil, "mcrider-stop")) {
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
        if (currentSaddleType.equals("none") == isRidingKart) {
            isRidingKart = !isRidingKart;
            autoThirdPerson();
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

        List<Entity> passengers = kartMobil.getPassengerList();
        for (var i: passengers) {
            if (hasCertainName(i, "mcrider-direction")) {
                for (var j : passengers) {
                    if (hasCertainName(j, "mcrider-modelsaddle")) {
                        float angleToRotate = calculateRotation(i.getYaw());
                        rotateKartModel(j, angleToRotate);
                        break;
                    }
                }
                break;
            }
        }
    }
    float calculateRotation(float directionYaw) {
        var playerYaw = playerYawBuffer.getFirst();

        var deltaAngle = normalizeAngle(playerYaw - directionYaw);
        var overShootAngle = getOverShootAngle(deltaAngle);

        if (currentSaddleType.equals("boat"))
            return normalizeAngle(playerYaw);
        else
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
        if (currentSaddleType.equals("1.0")) {
            if (deltaAngle <= -55) overShootAngle = -110f - deltaAngle;
            if (55 <= deltaAngle) overShootAngle = 110f - deltaAngle;
        }
        else {
            if (-110 <= deltaAngle && deltaAngle <= -55) overShootAngle = -110f - deltaAngle;
            if (55 <= deltaAngle && deltaAngle <= 110) overShootAngle = 110f - deltaAngle;
            if ((deltaAngle < -110 || deltaAngle > 110)) overShootAngle = 0f;
        }
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