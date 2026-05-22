
package loggamja.mcsync.mixin;

import loggamja.mcsync.MCRiderMain;
import loggamja.mcsync.interfaces.DisplayEntityRenderStateAccessor;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.BlockDisplayEntityRenderState;
import net.minecraft.client.render.entity.state.DisplayEntityRenderState;
import net.minecraft.client.render.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.render.entity.state.TextDisplayEntityRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin({
        BlockDisplayEntityRenderState.class,
        ItemDisplayEntityRenderState.class,
        TextDisplayEntityRenderState.class
})
public class DisplayEntityRenderStateMixin implements DisplayEntityRenderStateAccessor {
    @Unique
    private UUID mcsync_uuid;

    @Override
    public UUID mcsync_getUuid() { return mcsync_uuid; }

    @Override
    public void mcsync_setUuid(UUID uuid) { this.mcsync_uuid = uuid; }
}