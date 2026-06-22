package loggamja.mcrider.interfaces;

import java.util.UUID;

public interface DisplayEntityRenderStateAccessor {
    UUID mcsync_getUuid();
    void mcsync_setUuid(UUID uuid);
}