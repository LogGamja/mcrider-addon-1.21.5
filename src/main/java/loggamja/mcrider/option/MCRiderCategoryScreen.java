package loggamja.mcrider.option;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class MCRiderCategoryScreen extends Screen {
    private final Screen parent;
    private final MCRiderOptionTable.Category category;
    private int startY;

    public MCRiderCategoryScreen(Screen parent, MCRiderOptionTable.Category category) {
        super(Text.translatable(category.labelKey()));
        this.parent = parent;
        this.category = category;
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 169;
        int buttonHeight = 20;
        int spacing = 25;
        int startX = this.width / 2 - buttonWidth / 2;

        var toggles = MCRiderOptionTable.togglesIn(category);
        int buttonsPerRow = 2;
        int gap = 8;
        int centerX = this.width / 2;

        int toggleRowCount = (toggles.length + buttonsPerRow - 1) / buttonsPerRow;
        var sliders = MCRiderOptionTable.slidersIn(category);
        int sliderRowCount = (sliders.length + buttonsPerRow - 1) / buttonsPerRow;
        int totalRows = toggleRowCount + sliderRowCount + 2;
        this.startY = this.height / 2 - (totalRows * spacing) / 2;
        int startY = this.startY;

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

        for (int i = 0; i < sliders.length; i++) {
            var def = sliders[i];
            double min = def.min();
            double max = def.max();
            double value = def.getter().getAsDouble();

            double normalized = (value - min) / (max - min);
            String labelText = Text.translatable(def.labelKey()).getString();

            int row = i / buttonsPerRow;
            int col = i % buttonsPerRow;
            int sliderY = startY + (toggleRowCount + row) * spacing;
            int leftX  = centerX - (gap / 2) - buttonWidth;
            int rightX = centerX + (gap / 2);
            int sliderX = (col == 0) ? leftX : rightX;

            SliderWidget slider = new SliderWidget(sliderX, sliderY,
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
                }

                @Override
                public void onRelease(double mouseX, double mouseY) {
                    super.onRelease(mouseX, mouseY);
                    MCRiderConfig.INSTANCE.save();
                }
            };
            slider.setTooltip(Tooltip.of(Text.translatable(def.tooltipKey())));
            this.addDrawableChild(slider);
        }

        this.addDrawableChild(
            ButtonWidget.builder(Text.translatable("mcrider.setting.ok"), button -> this.close())
                .position(startX, startY + (toggleRowCount + sliderRowCount + 1) * spacing)
                .size(buttonWidth, buttonHeight)
                .build()
        );
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable(category.labelKey()), this.width / 2, this.startY / 2, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        assert this.client != null;
        this.client.setScreen(parent);
        MCRiderConfig.INSTANCE.save();
    }
}
