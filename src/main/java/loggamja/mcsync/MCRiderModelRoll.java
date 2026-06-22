package loggamja.mcsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffects;
import org.apache.http.impl.client.SystemDefaultCredentialsProvider;

import java.util.List;
import java.util.Objects;

public class MCRiderModelRoll implements ModInitializer {

    int driftTick = 0;
    int prevPlayerYaw = 0;
    int prevPrevPlayerYaw = 0;

    public static EasedValue rotation = new EasedValue(0.0, 300);
    MinecraftClient client = MinecraftClient.getInstance();
    @Override
    public void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            onClientTickEnd();
        });
    }
    void onClientTickEnd() {
        if (!MCRiderMain.isPlayingInGame()) return;

        var player = MCRiderMain.getRidingPlayer();
        var kart = player.getRootVehicle();


        var yaw = (int) player.getYaw();
        var yawDelta = (yaw - prevPlayerYaw);

        prevPlayerYaw = yaw;
        var yawDeltaDelta = (prevPlayerYaw - prevPrevPlayerYaw);
        prevPrevPlayerYaw = prevPlayerYaw;

        var driftDirection = Math.signum(yawDelta);

        //if (isDrifting) {
        //    driftTick++;
//
        //    if (Math.abs(yawDeltaDelta) > 10) {
        //        driftTick = 1;
        //    }
//
        //    if (driftTick == 1) {
        //        if (Math.abs(yawDeltaDelta) > 10) rotation.set(5 * driftDirection);
        //        else rotation.set(-6 * driftDirection);
        //    }
        //    else if (driftTick == 7) {
        //        rotation.set(-6 * driftDirection);
        //    }
        //}
        //else {
        //    if (driftTick > 0) {
        //        rotation.set(0);
        //    }
//
        //    driftTick = 0;
        //}

        //List<Entity> passengers = kart.getPassengerList();
        //for (var i : passengers) {
        //    if (MCRiderMain.hasCertainName(i, "mcrider-modelsaddle")) {
        //        for (var j : i.getPassengerList()) {
        //            RollManager.setRoll(j.getUuid(), (float) rotation.get(), 1);
        //        }
        //        break;
        //    }
        //}
    }

}