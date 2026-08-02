package loggamja.mcrider.option;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class MCRiderSettingScreen extends Screen {
    private final Screen parent;
    private int startY;

    // ESC 메뉴가 아닌 다른 곳(API 등)에서도 열릴 수 있으므로, 닫을 때 돌아갈 화면을 직접 기억한다
    public MCRiderSettingScreen(Screen parent) {
        super(Text.translatable("mcrider.setting.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 169;
        int buttonHeight = 20;
        int spacing = 25;
        int startX = this.width / 2 - buttonWidth / 2;

        MCRiderOptionTable.clampAllToggles();
        MCRiderOptionTable.clampAllSliders();

        MCRiderOptionTable.Category[] categories = MCRiderOptionTable.Category.values();
        int totalRows = categories.length + 2;
        this.startY = this.height / 2 - (totalRows * spacing) / 2;
        int startY = this.startY;

        for (int i = 0; i < categories.length; i++) {
            var category = categories[i];
            this.addDrawableChild(
                ButtonWidget.builder(Text.translatable(category.labelKey()),
                                button -> this.client.setScreen(new MCRiderCategoryScreen(this, category)))
                        .position(startX, startY + i * spacing)
                        .size(buttonWidth, buttonHeight)
                        .build()
            );
        }

        this.addDrawableChild(
            ButtonWidget.builder(Text.translatable("mcrider.setting.ok"), button -> this.close())
                .position(startX, startY + (categories.length + 1) * spacing)
                .size(buttonWidth, buttonHeight)
                .build()
        );
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("mcrider.setting.title"), this.width / 2, this.startY / 2, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        assert this.client != null;
        this.client.setScreen(parent);
        MCRiderConfig.INSTANCE.save();
    }
}
