package loggamja.mcrider;

import loggamja.mcrider.minimap.MCRiderMinimap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import loggamja.mcrider.helper.EntityRollManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.*;

public class MCRiderMain implements ClientModInitializer {
    static boolean useLegacyKartStopData = false;

    public static int kartEngine = 0;
    public static String currentSaddleType = "none";
    public static List<Float> playerYawBuffer = new ArrayList<>(Collections.nCopies(1, 0f));

    public static boolean isRidingKart = false;

    static MCRiderConfig cfg;
    static MinecraftClient client = MinecraftClient.getInstance();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            onClientTickEnd();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            EntityRollManager.clear();
        });

        MCRiderConfig.INSTANCE.load();
        cfg = MCRiderConfig.INSTANCE;
    }
    //MinecraftClient mc = MinecraftClient.getInstance();
    //mc.getWindow().setWindowedSize(1024, 768);
    private static int rollPruneTickCounter = 0;
    private static final int ROLL_PRUNE_INTERVAL_TICKS = 200; // 10초마다, 나갔다 들어오는 다른 플레이어의 롤 엔트리가 계속 쌓이는 것을 방지

    void onClientTickEnd() {
        if (!isPlayingInGame()) return;

        if (++rollPruneTickCounter >= ROLL_PRUNE_INTERVAL_TICKS) {
            rollPruneTickCounter = 0;
            Set<UUID> present = new HashSet<>();
            for (PlayerEntity p : client.world.getPlayers()) present.add(p.getUuid());
            EntityRollManager.pruneExcept(present);
        }

        Entity vehicle = getRidingPlayer().getVehicle();
        currentSaddleType = getSaddleType(vehicle);
        kartEngine = MCRiderMain.getS2CValue(MCRiderMain.getRidingPlayer(), "data-engine-real");
        // legacy engine detecting
        {
            if (currentSaddleType.equals("1.0")) kartEngine = 7;
            else if (currentSaddleType.equals("boat")) kartEngine = 1004;
        }

        updateRidingState();

        if (cfg.MCRiderRotationOption > 0) {
            if (isRidingKart && getRidingPlayer() == client.player) {
                Entity kartMobil = getRidingPlayer().getRootVehicle();

                if (getAllowModelRotation(kartMobil)) {
                    simulateKartRotation(kartMobil);
                }
            }
            fixAllPlayersBodyToKart();
        }
    }
    public static boolean isRidingKart(PlayerEntity player) {
        var kartSaddle = player.getVehicle();
        if (kartSaddle == null) return false;

        return !getSaddleType(kartSaddle).equals("none");
    }
    public static boolean getAllowModelRotation(Entity kartMobil) {
        if (hasCertainName(kartMobil, "mcrider-stop")) useLegacyKartStopData = true;

        if (useLegacyKartStopData) {
            return !hasCertainName(kartMobil, "mcrider-stop");
        }
        return getS2CValue(getRidingPlayer(), "state-allow-model-rotation") == 1;
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
        if (hasCertainName(saddle, "mcrider-saddle")) {
            return "common";
        }
        // 1.0, common and boat are for legacy support
        else if (hasCertainName(saddle, "mcrider-saddle-common")) {
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
    public static boolean hasCertainName(Entity entity, String saddleName) {
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

            if (!isRidingKart) {
                useLegacyKartStopData = false;
                EntityRollManager.clear();
            }
            MCRiderMinimap.clearAllMap();
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

        if (kartEngine == 1004)
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
        if (kartEngine == 7) {
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
    static long getNearestFrame(long criteriaMs) {
        float fps = Math.max(1f, client.getCurrentFps());

        float frameMs = 1000 / fps;
        long k = Math.round(criteriaMs  / frameMs);
        return Math.max(1L, k);
    }
    private static final Map<String, Identifier> s2cIdCache = new HashMap<>();

    public static int getS2CValue(PlayerEntity player, String name) {
        Identifier id = s2cIdCache.computeIfAbsent(name, n -> Identifier.of("minecraft", n));

        Entity saddle = player.getVehicle();
        if (saddle == null || !saddle.isLiving()) return 0;

        EntityAttributeInstance inst = ((LivingEntity)saddle).getAttributeInstance(EntityAttributes.ARMOR);
        if (inst == null) return 0;

        EntityAttributeModifier mod = inst.getModifier(id);
        return (mod == null) ? 0 : (int)mod.value();
    }
}