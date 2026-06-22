
package loggamja.mcrider.mixin;

import loggamja.mcrider.interfaces.DisplayEntityRenderStateAccessor;
import net.minecraft.client.render.entity.state.BlockDisplayEntityRenderState;
import net.minecraft.client.render.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.render.entity.state.TextDisplayEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

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