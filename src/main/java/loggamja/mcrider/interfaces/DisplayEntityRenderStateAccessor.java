package loggamja.mcrider.interfaces;

import java.util.UUID;

public interface DisplayEntityRenderStateAccessor {
    UUID mcrider$getUuid();
    void mcrider$setUuid(UUID uuid);

    double mcrider$getPivotY();
    void mcrider$setPivotY(double pivotY);
}