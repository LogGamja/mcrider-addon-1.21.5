
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
    private UUID mcrider$uuid;

    @Override
    public UUID mcrider$getUuid() { return mcrider$uuid; }

    @Override
    public void mcrider$setUuid(UUID uuid) { this.mcrider$uuid = uuid; }
}