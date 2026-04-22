package loggamja.mcsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.*;

public class MCRiderMain implements ModInitializer {
    public static String currentSaddleType = "none";
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

        if (cfg.MCRiderRotationOption > 0) {
            if (isRidingKart && getRidingPlayer() == client.player) {
                Entity kartMobil = getRidingPlayer().getRootVehicle();

                if (!hasCertainName(kartMobil, "mcrider-stop")) {
                    simulateKartRotation(kartMobil);
                }
            }
            fixAllPlayersBodyToKart();
        }
    }
    public static boolean isRidingKart(PlayerEntity player) {
        var kartSaddle = player.getVehicle();
        if (kartSaddle == null) return false;

        return !Objects.equals(getSaddleType(kartSaddle), "none");
    }
    public static boolean isPlayingInGame() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.world != null && client.player != null && client.getCameraEntity() != null && !client.isPaused();
    }
    public static PlayerEntity getRidingPlayer() {
        MinecraftClient client = MinecraftClient.getInstance();
        var cameraEntity = client.getCameraEntity();

        if (cameraEntity != null && cameraEntity.isPlayer()) {
            return (PlayerEntity) cameraEntity;
        }
        return client.player;
    }
    static String getSaddleType(Entity saddle) {
        if (hasCertainName(saddle, "mcrider-saddle-common")) {
            return "common";
        }
        else if (hasCertainName(saddle, "mcrider-saddle-1.0")) {
            return "1.0";
        }
        else if (hasCertainName(saddle, "mcrider-saddle-boat")) {
            return "boat";
        }
        else {
            return "none";
        }
    }
    static boolean hasCertainName(Entity entity, String saddleName) {
        if (entity != null && entity.getCustomName() != null) {
            return entity.getCustomName().getString().equals(saddleName);
        }
        else {
            return false;
        }
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
    void fixAllPlayersBodyToKart() {
        for (PlayerEntity other : Objects.requireNonNull(client.world).getPlayers()) {
            if (!isRidingKart(other)) continue;

            fixPlayerBodyToKart(other, other.getRootVehicle());
        }
    }
    void fixPlayerBodyToKart(PlayerEntity player, Entity kartMobil) {
        if (kartMobil == player) return;

        List<Entity> passengers = kartMobil.getPassengerList();
        for (var i: passengers) {
            if (hasCertainName(i, "mcrider-direction")) {
                for (var j : passengers) {
                    if (hasCertainName(j, "mcrider-modelsaddle")) {
                        player.setBodyYaw(j.getYaw());
                        break;
                    }
                }
                break;
            }
        }
    }
    void simulateKartRotation(Entity kartMobil) {
        var player = getRidingPlayer();
        if (kartMobil == player) return;

        List<Entity> passengers = kartMobil.getPassengerList();
        for (var i: passengers) {
            if (hasCertainName(i, "mcrider-direction")) {
                var kartModelRotation = calculateRotation(i.getYaw());

                for (var j : passengers) {
                    if (hasCertainName(j, "mcrider-modelsaddle")) {
                        rotateKartModel(j, kartModelRotation);
                        break;
                    }
                }
                break;
            }
        }
    }
    float calculateRotation(float directionYaw) {
        var playerYaw = playerYawBuffer.getFirst();

        var deltaAngle = MathHelper.wrapDegrees(playerYaw - directionYaw);
        var overShootAngle = getOverShootAngle(deltaAngle);

        if (currentSaddleType.equals("boat"))
            return MathHelper.wrapDegrees(playerYaw);
        else
            return MathHelper.wrapDegrees(playerYaw + overShootAngle);
    }
    void rotateKartModel(Entity entity, float angleToRotate) {
        List<Entity> models = entity.getPassengerList();

        entity.setYaw(angleToRotate);
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
    static int getS2CValue(PlayerEntity player, String name) {
        Identifier id = Identifier.of("minecraft", name);

        EntityAttributeInstance inst = player.getAttributeInstance(EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE);
        if (inst == null) return 0;

        EntityAttributeModifier mod = inst.getModifier(id);
        return (mod == null) ? 0 : (int)mod.value();
    }
}