
package loggamja.mcrider.mixin;

import loggamja.mcrider.interfaces.DisplayEntityRenderStateAccessor;
import net.minecraft.client.render.entity.state.DisplayEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

// 부모 클래스 믹스인으로 모든 디스플레이 타입 커버
@Mixin(DisplayEntityRenderState.class)
public class DisplayEntityRenderStateMixin implements DisplayEntityRenderStateAccessor {
    @Unique
    private UUID mcrider$uuid;

    @Unique
    private double mcrider$pivotY;

    @Override
    public UUID mcrider$getUuid() { return mcrider$uuid; }

    @Override
    public void mcrider$setUuid(UUID uuid) { this.mcrider$uuid = uuid; }

    @Override
    public double mcrider$getPivotY() { return mcrider$pivotY; }

    @Override
    public void mcrider$setPivotY(double pivotY) { this.mcrider$pivotY = pivotY; }
}