package loggamja.mcrider;

import loggamja.mcrider.minimap.MCRiderMinimap;
import loggamja.mcrider.option.MCRiderConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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

    // 미니맵 내 카트 아이콘 회전 보간용
    private static float prevKartYaw = 0f;
    private static float currentKartYaw = 0f;
    private static boolean hasKartYawHistory = false;
    private static boolean isKartYawUpdatedThisTick = false;

    static MinecraftClient client = MinecraftClient.getInstance();

    @Override
    public void onInitializeClient() {
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) ->
                EntityRollManager.remove(entity.getUuid()));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            onClientTickEnd();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            EntityRollManager.clear();
            MCRiderSuspension.clearStates();
        });
        // 무조건 렌더 스레드로 예약
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            client.execute(() -> {
                isRidingKart = false;
                useLegacyKartStopData = false;
                hasKartYawHistory = false;
            });
        });

        MCRiderConfig.INSTANCE.load();
    }
    //MinecraftClient mc = MinecraftClient.getInstance();
    //mc.getWindow().setWindowedSize(1024, 768);

    void onClientTickEnd() {
        if (!isPlayingInGame()) return;

        Entity vehicle = getRidingPlayer().getVehicle();
        currentSaddleType = getSaddleType(vehicle);
        kartEngine = MCRiderMain.getS2CValue(MCRiderMain.getRidingPlayer(), "data-engine-real");
        // legacy engine detecting
        {
            if (currentSaddleType.equals("1.0")) kartEngine = 7;
            else if (currentSaddleType.equals("boat")) kartEngine = 1004;
        }

        updateRidingState();

        isKartYawUpdatedThisTick = false;

        if (MCRiderConfig.INSTANCE.MCRiderRotationOption > 0) {
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
        return getAllowModelRotation(kartMobil, getRidingPlayer());
    }
    public static boolean getAllowModelRotation(Entity kartMobil, PlayerEntity player) {
        if (hasCertainName(kartMobil, "mcrider-stop")) useLegacyKartStopData = true;

        if (useLegacyKartStopData) {
            return !hasCertainName(kartMobil, "mcrider-stop");
        }
        return getS2CValue(player, "state-allow-model-rotation") == 1;
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

            MCRiderCamera.lastPos = null;
            MCRiderMinimap.clearAllMap();
            autoThirdPerson();
        }
    }
    void autoThirdPerson() {
        if (MCRiderConfig.INSTANCE.useAutoThirdPerson) {
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
                prevKartYaw = hasKartYawHistory ? currentKartYaw : kartModelRotation;
                currentKartYaw = kartModelRotation;
                hasKartYawHistory = true;
                isKartYawUpdatedThisTick = true;

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
    public static boolean hasTrackedSelfKartYaw() {
        return isKartYawUpdatedThisTick;
    }

    public static float getInterpolatedSelfKartYaw(float tickDelta) {
        return MathHelper.lerpAngleDegrees(tickDelta, prevKartYaw, currentKartYaw);
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

        // 1.0 엔진은 클램프 구현하지 않음
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
        if (MCRiderConfig.INSTANCE.MCRiderRotationOption == 2) {
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