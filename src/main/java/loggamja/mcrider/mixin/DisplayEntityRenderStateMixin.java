
package loggamja.mcrider.mixin;

import loggamja.mcrider.interfaces.DisplayEntityRenderStateAccessor;
import net.minecraft.client.render.entity.state.DisplayEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

// BlockDisplayEntityRenderState/ItemDisplayEntityRenderState/TextDisplayEntityRenderState가
// 모두 이 클래스를 상속하므로 여기 하나에만 믹스인하면 셋 다 자동으로 커버되고,
// 새 디스플레이 타입이 추가돼도 별도 대응이 필요 없다.
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