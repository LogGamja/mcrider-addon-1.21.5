
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

    @Shadow @Final private MinecraftClient client;

    @Unique private PlayerInput mcrider$lastPlayerInput = PlayerInput.DEFAULT;
    @Unique final GameOptions mcrider$options = MinecraftClient.getInstance().options;

    @Unique float mcrider$lastYaw;

    @Unique boolean mcrider$isFovBackupedThisFrame;
    @Unique boolean mcrider$isFovEffectScaleBackupedThisTick;

    @Unique int mcrider$backupFov;
    @Unique double mcrider$backupFovEffectScale;

    @Inject(method = "updateFovMultiplier", at = @At(value = "HEAD"))
    private void mcrider$beforeFovMultiplierUpdate(CallbackInfo ci) {
        if (!MCRiderMain.isRidingKart) return;

        mcrider$backupFovEffectScale = mcrider$options.getFovEffectScale().getValue();
        mcrider$isFovEffectScaleBackupedThisTick = true;

        double customEffectScale = MCRiderConfig.INSTANCE.MCRiderFOVEffects / 100f;
        if (client.options.getPerspective() == Perspective.FIRST_PERSON) {
            customEffectScale /= 3;
        }
        mcrider$options.getFovEffectScale().setValue(customEffectScale);
    }
    @Inject(method = "updateFovMultiplier", at = @At(value = "TAIL"))
    private void mcrider$afterFovUpdate(CallbackInfo ci) {
        if (!mcrider$isFovEffectScaleBackupedThisTick) return;

        mcrider$options.getFovEffectScale().setValue(mcrider$backupFovEffectScale);
        mcrider$isFovEffectScaleBackupedThisTick = false;
    }
    @Inject(method = "getFov", at = @At(value = "HEAD"), cancellable = true)
    private void mcrider$beforeGetFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (!MCRiderMain.isRidingKart) return;

        if (MCRiderConfig.INSTANCE.cameraMode == 0) {
            mcrider$backupFov = mcrider$options.getFov().getValue();
            mcrider$isFovBackupedThisFrame = true;

            final int customFov = MCRiderConfig.INSTANCE.MCRiderFOV;
            mcrider$options.getFov().setValue(customFov);
        }
        else {
            var interpolatedFOV  = MathHelper.lerp(tickDelta, MCRiderCamera.filteredFOVAtPrevTick, MCRiderCamera.filteredFOV);
            if (client.options.getPerspective() == Perspective.FIRST_PERSON) {
                interpolatedFOV  *= 0.825f;
            }
            cir.setReturnValue(interpolatedFOV);
        }
    }
    @Inject(method = "getFov", at = @At(value = "TAIL"))
    private void mcrider$afterGetFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (!mcrider$isFovBackupedThisFrame) return;

        mcrider$options.getFov().setValue(mcrider$backupFov);
        mcrider$isFovBackupedThisFrame = false;
    }

    @Inject(method = "render", at = @At(value = "HEAD"))
    private void mcrider$renderHead(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (!MCRiderMain.isRidingKart || !MCRiderMain.isPlayingInGame()) return;

        MCRiderMain.onFrameRender();
    }
    @Inject(method = "render", at = @At(value = "TAIL"))
    private void mcrider$renderTail(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (!MCRiderMain.isRidingKart || !MCRiderMain.isPlayingInGame() || !MCRiderConfig.INSTANCE.MCRiderPacketAcceleration) return;

        ClientPlayerEntity player = Objects.requireNonNull(getClient().player);

        if (player == MCRiderMain.getRidingPlayer()) {
            PlayerInput input = mcrider$getPlayerInput(player);

            //키엔진 키 입력이 감지되면 패킷 발사
            if (!this.mcrider$lastPlayerInput.equals(input)) {
                player.networkHandler.sendPacket(new PlayerInputC2SPacket(input));
                this.mcrider$lastPlayerInput = input;
            }

            //카메라의 가로 방향이 회전하면 패킷 발사
            if (this.mcrider$lastYaw != player.getYaw()) {
                player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(player.getYaw(), player.getPitch(), player.isOnGround(), player.horizontalCollision));
                this.mcrider$lastYaw = player.getYaw();
            }

            Entity vehicle = player.getRootVehicle();
            if (vehicle != player && vehicle.isLogicalSideForUpdatingMovement()) {
                player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(vehicle));
            }
        }
    }
    @Unique
    private @NotNull PlayerInput mcrider$getPlayerInput(ClientPlayerEntity player) {
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
