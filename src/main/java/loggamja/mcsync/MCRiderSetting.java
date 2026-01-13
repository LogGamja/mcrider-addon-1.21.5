package loggamja.mcsync;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.resource.language.LanguageManager;
import net.minecraft.text.Text;

import java.util.Objects;
public class MCRiderSetting extends Screen {

    static String[][] TOGGLE_OPTIONS = {
            { "Steer Boost: OFF", "Steer Boost: Normal", "Steer Boost: Extreme!" },
            { "Packet Boost: OFF", "Packet Boost: ON" },
            { "Enemy Radar: OFF", "Enemy Radar: Center", "Enemy Radar: Side", "Enemy Radar: Both" },
            { "Draft gauge: OFF", "Draft gauge: ON" },
            { "Auto third person: OFF", "Auto third person: ON" },
            { "Noclip camera: OFF", "Noclip camera: ON" },
            { "Camera mode: Default[1]", "Camera mode: Balance[2]", "Camera mode: Normal[3]", "Camera mode: Kartrider[4]" }
    };
    static String[] tooltips = {
            "Optimize the rotation of karts when riding a kart.",
            "Accelerate packets when riding a kart.",
            "Show enemy radar when riding a kart.",
            "Show a draft gauge when riding a kart.",
            "Automatically change to 3rd person view when riding a kart.",
            "Show enemy radar when riding a kart.",
            "Third person camera goes through blocks when riding a kart.",
            "Change camera mode when riding a kart."
    };

    // 슬라이더 정의: {라벨, 최소값, 최대값, 기본값, 툴팁}
    static Object[][] SLIDER_OPTIONS = {
            { "Riding FOV", 30.0, 110.0, "This option only works in camera mode 'default'." },
            { "Riding FOV Effects", 0.0, 100.0, "This option only works in camera mode 'default'." }
    };

    private final int[] toggleIndices = new int[TOGGLE_OPTIONS.length];
    private final double[] sliderValues = new double[SLIDER_OPTIONS.length];

    public MCRiderSetting() {
        super(Text.literal("Setting"));
    }
    void setTextLang() {
        MinecraftClient client = MinecraftClient.getInstance();
        LanguageManager lm = client.getLanguageManager();

        if (Objects.equals(lm.getLanguage(), "ko_kr")) {
            TOGGLE_OPTIONS = new String[][] {
                    { "조작 가속: 꺼짐", "조작 가속: 보통", "조작 가속: 극한" },
                    { "패킷 가속: 꺼짐", "패킷 가속: 켜짐" },
                    { "카트 레이더: 꺼짐", "카트 레이더: 중앙", "카트 레이더: 하단", "카트 레이더: 모두" },
                    { "드래프트 게이지: 꺼짐", "드래프트 게이지: 켜짐" },
                    { "자동 3인칭: 꺼짐", "자동 3인칭: 켜짐" },
                    { "카메라 통과: 꺼짐", "카메라 통과: 켜짐" },
                    { "카메라 모드: 기본[1]", "카메라 모드: 균형[2]", "카메라 모드: 보통[3]", "카메라 모드: 원작[4]" }
            };
            tooltips = new String[] {
                    "카트 탑승 시 조작감을 최적화합니다.",
                    "카트 탑승 시 통신 과정을 가속합니다.",
                    "카트 탑승 시 주변에 있는 다른 라이더의 위치를 표시합니다.",
                    "카트 탑승 시 드래프트 게이지를 표시합니다.",
                    "카트 탑승 시 자동으로 3인칭으로 전환합니다.",
                    "카트 탑승 시 3인칭 카메라가 블록에 걸리지 않게 합니다.",
                    "카트 탑승 시 카메라 연출 방식을 선택할 수 있습니다."
            };
            SLIDER_OPTIONS = new Object[][] {
                    { "주행 시야 범위", 30.0, 110.0, "카메라 모드 '기본' 상태에서만 적용됩니다." },
                    { "주행 시야 효과", 0.0, 100.0, "카메라 모드 '기본' 상태에서만 적용됩니다." }
            };
        }
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 169;
        int buttonHeight = 20;
        int spacing = 25;
        int startX = this.width / 2 - buttonWidth / 2;
        int startY = this.height / 5;

        setTextLang();

        // Config load
        MCRiderConfig cfg = MCRiderConfig.INSTANCE;
        toggleIndices[0] = cfg.MCRiderRotationOption;
        toggleIndices[1] = cfg.MCRiderPacketAcceleration ? 1 : 0;
        toggleIndices[2] = cfg.MCRiderRadarOption;
        toggleIndices[3] = cfg.useDraftGauge ? 1 : 0;
        toggleIndices[4] = cfg.useAutoThirdPerson ? 1 : 0;
        toggleIndices[5] = cfg.useNoclipCamera ? 1 : 0;
        toggleIndices[6] = cfg.cameraMode;

        // slider load
        sliderValues[0] = cfg.MCRiderFOV;
        sliderValues[1] = cfg.MCRiderFOVEffects;

        // toggle button
        int buttonsPerRow = 2;
        int gap = 12;
        int centerX = this.width / 2;

        for (int i = 0; i < TOGGLE_OPTIONS.length; i++) {
            final int index = i;

            int row = i / buttonsPerRow;
            int col = i % buttonsPerRow;

            int buttonY = startY + row * spacing;
            int leftX  = centerX - (gap / 2) - buttonWidth;
            int rightX = centerX + (gap / 2);

            int buttonX = (col == 0) ? leftX : rightX;

            ButtonWidget toggleButton = ButtonWidget.builder(
                            Text.literal(TOGGLE_OPTIONS[index][toggleIndices[index]]),
                            button -> {
                                toggleIndices[index] = (toggleIndices[index] + 1) % TOGGLE_OPTIONS[index].length;
                                button.setMessage(Text.literal(TOGGLE_OPTIONS[index][toggleIndices[index]]));
                                onButtonClick(index, toggleIndices[index]);
                                MCRiderConfig.INSTANCE.save();
                            })
                    .position(buttonX, buttonY)
                    .size(buttonWidth, buttonHeight)
                    .tooltip(Tooltip.of(Text.literal(tooltips[index])))
                    .build();

            this.addDrawableChild(toggleButton);
        }

        // Sliders
        for (int i = 0; i < SLIDER_OPTIONS.length; i++) {
            final int index = i;
            String label = (String) SLIDER_OPTIONS[i][0];
            double min = (double) SLIDER_OPTIONS[i][1];
            double max = (double) SLIDER_OPTIONS[i][2];
            double value = sliderValues[i];

            double normalized = (value - min) / (max - min);

            String tip = (String) SLIDER_OPTIONS[i][3];

            SliderWidget slider = new SliderWidget(startX, startY + (TOGGLE_OPTIONS.length / 2 + i + 1) * spacing,
                    buttonWidth, buttonHeight, Text.literal(label + ": " + (int)value), normalized) {
                @Override
                protected void updateMessage() {
                    double actual = min + this.value * (max - min);
                    this.setMessage(Text.literal(label + ": " + (int)actual));
                }

                @Override
                protected void applyValue() {
                    double actual = min + this.value * (max - min);
                    sliderValues[index] = actual;
                    onSliderChange(index, (float)actual);
                    MCRiderConfig.INSTANCE.save();
                }
            };
            slider.setTooltip(Tooltip.of(Text.literal(tip)));
            this.addDrawableChild(slider);
        }

        // Exit button
        this.addDrawableChild(
            ButtonWidget.builder(Text.literal("OK"), button -> {
                    assert this.client != null;
                    this.client.setScreen(new GameMenuScreen(true));
                    MCRiderConfig.INSTANCE.save();
                })
                .position(startX, startY + (TOGGLE_OPTIONS.length + SLIDER_OPTIONS.length) * spacing ) //+40
                .size(buttonWidth, buttonHeight)
                .build()
        );
    }

    void onButtonClick(int button, int index) {
        MCRiderConfig cfg = MCRiderConfig.INSTANCE;
        if (button == 0) cfg.MCRiderRotationOption = index;
        else if (button == 1) cfg.MCRiderPacketAcceleration = (index != 0);
        else if (button == 2) cfg.MCRiderRadarOption = index;
        else if (button == 3) cfg.useDraftGauge  = (index != 0);
        else if (button == 4) cfg.useAutoThirdPerson = (index != 0);
        else if (button == 5) cfg.useNoclipCamera = (index != 0);
        else if (button == 6) cfg.cameraMode = index;
    }

    void onSliderChange(int slider, float value) {
        MCRiderConfig cfg = MCRiderConfig.INSTANCE;
        if (slider == 0) cfg.MCRiderFOV = (int)value;
        else if (slider == 1) cfg.MCRiderFOVEffects = (int)value;
    }
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, "Setting", this.width / 2, 20, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }
}