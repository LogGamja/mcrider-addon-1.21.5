package loggamja.mcrider;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class MCRiderSetting extends Screen {

    public MCRiderSetting() {
        super(Text.translatable("mcrider.setting.title"));
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 169;
        int buttonHeight = 20;
        int spacing = 25;
        int startX = this.width / 2 - buttonWidth / 2;
        int startY = this.height / 5;

        // 옛 config에 남아있을 수 있는 범위 밖 값 보정(예: 삭제된 옵션 상태).
        MCRiderOptionDefs.clampAllToggles();

        var toggles = MCRiderOptionDefs.TOGGLES;
        int buttonsPerRow = 2;
        int gap = 12;
        int centerX = this.width / 2;

        for (int i = 0; i < toggles.length; i++) {
            var def = toggles[i];

            int row = i / buttonsPerRow;
            int col = i % buttonsPerRow;

            int buttonY = startY + row * spacing;
            int leftX  = centerX - (gap / 2) - buttonWidth;
            int rightX = centerX + (gap / 2);

            int buttonX = (col == 0) ? leftX : rightX;

            int current = def.getter().getAsInt();

            ButtonWidget toggleButton = ButtonWidget.builder(
                            Text.translatable(def.labelKeys()[current]),
                            button -> {
                                int next = (def.getter().getAsInt() + 1) % def.stateCount();
                                def.setter().accept(next);
                                button.setMessage(Text.translatable(def.labelKeys()[next]));
                                MCRiderConfig.INSTANCE.save();
                            })
                    .position(buttonX, buttonY)
                    .size(buttonWidth, buttonHeight)
                    .tooltip(Tooltip.of(Text.translatable(def.tooltipKey())))
                    .build();

            this.addDrawableChild(toggleButton);
        }

        // Sliders
        var sliders = MCRiderOptionDefs.SLIDERS;
        for (int i = 0; i < sliders.length; i++) {
            var def = sliders[i];
            double min = def.min();
            double max = def.max();
            double value = def.getter().getAsDouble();

            double normalized = (value - min) / (max - min);
            String labelText = Text.translatable(def.labelKey()).getString();

            SliderWidget slider = new SliderWidget(startX, startY + (toggles.length / 2 + i + 1) * spacing,
                    buttonWidth, buttonHeight, Text.literal(labelText + ": " + (int) value), normalized) {
                @Override
                protected void updateMessage() {
                    double actual = min + this.value * (max - min);
                    this.setMessage(Text.literal(labelText + ": " + (int) actual));
                }

                @Override
                protected void applyValue() {
                    double actual = min + this.value * (max - min);
                    def.setter().accept((float) actual);
                    MCRiderConfig.INSTANCE.save();
                }
            };
            slider.setTooltip(Tooltip.of(Text.translatable(def.tooltipKey())));
            this.addDrawableChild(slider);
        }

        // Exit button
        this.addDrawableChild(
            ButtonWidget.builder(Text.translatable("mcrider.setting.ok"), button -> {
                    assert this.client != null;
                    this.client.setScreen(new GameMenuScreen(true));
                    MCRiderConfig.INSTANCE.save();
                })
                .position(startX, startY + (toggles.length + sliders.length) * spacing)
                .size(buttonWidth, buttonHeight)
                .build()
        );
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("mcrider.setting.title"), this.width / 2, 20, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }
}
