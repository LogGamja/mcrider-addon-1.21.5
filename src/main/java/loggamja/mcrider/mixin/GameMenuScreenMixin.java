package loggamja.mcrider.mixin;

import loggamja.mcrider.api.MCRiderAPI;
import loggamja.mcrider.option.MCRiderSettingScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.screen.GameMenuScreen;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {
    protected GameMenuScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void mcrider$addCustomButton(CallbackInfo ci) {
        if (MCRiderAPI.isMenuButtonHidden()) return;

        this.addDrawableChild(
                ButtonWidget.builder(Text.translatable("mcrider.setting.menu_button"), button -> {
                            assert this.client != null;
                            this.client.setScreen(new MCRiderSettingScreen(this));
                        })
                        .position(10, 35)
                        .size(100, 20)
                        .build()
        );
    }
}
