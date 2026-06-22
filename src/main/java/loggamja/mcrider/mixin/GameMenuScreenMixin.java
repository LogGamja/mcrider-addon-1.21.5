package loggamja.mcrider.mixin;

import loggamja.mcrider.MCRiderSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.resource.language.LanguageManager;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.screen.GameMenuScreen;

import java.util.Objects;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {
    protected GameMenuScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void mcrider$addCustomButton(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        LanguageManager lm = client.getLanguageManager();

        String buttonName = "MCRider Setting";
        if (Objects.equals(lm.getLanguage(), "ko_kr")) {
            buttonName = "마크라이더 설정";
        }
        this.addDrawableChild(
                ButtonWidget.builder(Text.literal(buttonName), button -> {
                            assert this.client != null;
                            this.client.setScreen(new MCRiderSetting());
                        })
                        .position(10, 35)
                        .size(100, 20)
                        .build()
        );
    }
}

