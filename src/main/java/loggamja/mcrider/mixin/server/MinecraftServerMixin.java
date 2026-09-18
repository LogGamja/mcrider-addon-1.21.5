package loggamja.mcrider.mixin.server;

import loggamja.mcrider.option.MCRiderConfig;
import loggamja.mcrider.server.MCRiderAutoSaveDelayState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

	@Unique private static final TagKey<EntityType<?>> KART_ENTITY_TYPE_TAG =
		TagKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of("kartmobil", "kartmobil"));
	@Unique private static final TagKey<EntityType<?>> SADDLE_ENTITY_TYPE_TAG =
		TagKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of("kartmobil", "kartsaddle"));

	@Inject(method = "runAutosave", at = @At("HEAD"), cancellable = true)
	private void mcrider$onRunAutosave(CallbackInfo ci) {
		MinecraftServer server = (MinecraftServer) (Object) this;

		if (mcrider$shouldSkipDelay(server)) {
			MCRiderAutoSaveDelayState.reset();
			return;
		}

		if (MCRiderAutoSaveDelayState.tryDelayAutosave(() -> mcrider$isRaceActive(server), server.getTicks(),
			server.getTickManager().getTickRate())) {
			ci.cancel();
		}
	}

	@Unique
	private static boolean mcrider$shouldSkipDelay(MinecraftServer server) {
		return !MCRiderConfig.INSTANCE.autoSaveDelayEnabled
			|| server.getPlayerManager().getCurrentPlayerCount() == 0
			|| Registries.ENTITY_TYPE.getOptional(KART_ENTITY_TYPE_TAG).isEmpty()
			|| Registries.ENTITY_TYPE.getOptional(SADDLE_ENTITY_TYPE_TAG).isEmpty();
	}

	@Unique
	private static boolean mcrider$isRaceActive(MinecraftServer server) {
		ScoreboardObjective sidebar = server.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
		if (sidebar == null || !"timerdisplay".equals(sidebar.getName())) {
			return false;
		}

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (mcrider$isRidingKart(player)) {
				return true;
			}
		}
		return false;
	}

	@Unique
	private static boolean mcrider$isRidingKart(ServerPlayerEntity player) {
		Entity saddle = player.getVehicle();
		if (saddle == null || !(saddle.getType().isIn(SADDLE_ENTITY_TYPE_TAG) && saddle.getCommandTags().contains("kartsaddle"))) {
			return false;
		}
        // 태그가 사라지는 루프 구간에서도 탑승으로 처리하기 위해 type만 봄.
		return player.getRootVehicle().getType().isIn(KART_ENTITY_TYPE_TAG);
	}
}
