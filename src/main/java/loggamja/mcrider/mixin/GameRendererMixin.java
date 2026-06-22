
package loggamja.mcrider.mixin;

import loggamja.mcrider.MCRiderCamera;
import loggamja.mcrider.MCRiderMain;
import loggamja.mcrider.MCRiderConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;

import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.util.PlayerInput;

import net.minecraft.util.math.MathHelper;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow public abstract MinecraftClient getClient();

    @Shadow
    @Final
    private MinecraftClient client;
    @Unique private PlayerInput lastPlayerInput = PlayerInput.DEFAULT;
    @Unique final GameOptions options = MinecraftClient.getInstance().options;

    @Unique float lastYaw;

    @Unique boolean isFovBackupedThisFrame;
    @Unique boolean isFovEffectScaleBackupedThisTick;

    @Unique int backupFov;
    @Unique double backupFovEffectScale;

    @Inject(method = "updateFovMultiplier", at = @At(value = "HEAD"))
    private void beforeFovMultiplierUpdate(CallbackInfo ci) {
        if (!MCRiderMain.isRidingKart) return;

        backupFovEffectScale = options.getFovEffectScale().getValue();
        isFovEffectScaleBackupedThisTick = true;

        double customEffectScale = MCRiderConfig.INSTANCE.MCRiderFOVEffects / 100f;
        if (client.options.getPerspective() == Perspective.FIRST_PERSON) {
            customEffectScale /= 3;
        }
        options.getFovEffectScale().setValue(customEffectScale);
    }
    @Inject(method = "updateFovMultiplier", at = @At(value = "TAIL"))
    private void afterFovUpdate(CallbackInfo ci) {
        if (!isFovEffectScaleBackupedThisTick) return;

        options.getFovEffectScale().setValue(backupFovEffectScale);
        isFovEffectScaleBackupedThisTick = false;
    }
    @Inject(method = "getFov", at = @At(value = "HEAD"), cancellable = true)
    private void beforeGetFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (!MCRiderMain.isRidingKart) return;

        if (MCRiderConfig.INSTANCE.cameraMode == 0) {
            backupFov = options.getFov().getValue();
            isFovBackupedThisFrame = true;

            final int customFov = MCRiderConfig.INSTANCE.MCRiderFOV;
            options.getFov().setValue(customFov);
        }
        else {
            var asdf = MathHelper.lerp(tickDelta, MCRiderCamera.filteredFOVAtPrevTick, MCRiderCamera.filteredFOV);
            if (client.options.getPerspective() == Perspective.FIRST_PERSON) {
                asdf *= 0.825f;
            }
            cir.setReturnValue(asdf);
        }
    }
    @Inject(method = "getFov", at = @At(value = "TAIL"))
    private void afterGetFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (!isFovBackupedThisFrame) return;

        options.getFov().setValue(backupFov);
        isFovBackupedThisFrame = false;
    }

    @Inject(method = "render", at = @At(value = "HEAD"))
    private void renderHead(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (!MCRiderMain.isRidingKart || !MCRiderMain.isPlayingInGame()) return;

        MCRiderMain.onFrameRender();
    }
    @Inject(method = "render", at = @At(value = "TAIL"))
    private void renderTail(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (!MCRiderMain.isRidingKart || !MCRiderMain.isPlayingInGame() || !MCRiderConfig.INSTANCE.MCRiderPacketAcceleration) return;

        ClientPlayerEntity player = Objects.requireNonNull(getClient().player);

        if (player == MCRiderMain.getRidingPlayer()) {
            PlayerInput input = getPlayerInput(player);

            //키엔진 키 입력이 감지되면 패킷 발사
            if (!this.lastPlayerInput.equals(input)) {
                player.networkHandler.sendPacket(new PlayerInputC2SPacket(input));
                this.lastPlayerInput = input;
            }

            //카메라의 가로 방향이 회전하면 패킷 발사
            if (this.lastYaw != player.getYaw()) {
                player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(player.getYaw(), player.getPitch(), player.isOnGround(), player.horizontalCollision));
                this.lastYaw = player.getYaw();
            }

            Entity vehicle = player.getRootVehicle();
            if (vehicle != player && vehicle.isLogicalSideForUpdatingMovement()) {
                player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(vehicle));
            }
        }
    }
    @Unique
    private @NotNull PlayerInput getPlayerInput(ClientPlayerEntity player) {
        GameOptions options = getClient().options;
        PlayerInput mcInput = player.input.playerInput;

        return new PlayerInput(
                options.forwardKey.isPressed(),
                options.backKey.isPressed(),
                options.leftKey.isPressed(),
                options.rightKey.isPressed(),
                mcInput.jump(),
                mcInput.sneak(),
                mcInput.sprint()
        );
    }
}
