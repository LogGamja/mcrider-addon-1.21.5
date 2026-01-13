package loggamja.mcsync.mixin;

import loggamja.mcsync.MCRiderMain;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class HideCodMixin {
    @Shadow
    @Final
    private MinecraftClient client;
    
    //21.9에서는 아예 함수를 지우기
    
    //renderEntity(Lnet/minecraft/entity/Entity;DDDFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V
    @Inject(
            method = "renderEntity",
            at = @At("HEAD"),
            cancellable = true
    )
    private void hideCod(Entity entity, double cameraX, double cameraY, double cameraZ, float tickProgress, MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
        if (!MCRiderMain.isRidingKart || !MCRiderMain.isPlayingInGame()) return;

        if (entity.getType() != EntityType.COD) {
            boolean invisible = entity.isInvisible();

            PlayerEntity player = client.player;

            // 관전자인지 감지
            if (player != null) {
                invisible = invisible || entity.isInvisibleTo(player);
            }

            if (invisible) {
                ci.cancel();
            }
        }
    }
}