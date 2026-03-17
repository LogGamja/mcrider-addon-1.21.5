package loggamja.mcsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffects;

import java.util.Objects;

public class MCRiderDraft implements ModInitializer {
    static MinecraftClient client = MinecraftClient.getInstance();

    static boolean isChargingDraft = false;
    static boolean isDraftActive = false;

    static int draftChargeTick;

    @Override
    public void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            draftGauge();
        });

        //HudRenderCallback.EVENT.register((context, context2) -> render(context, context2.getTickDelta(false)));
        HudRenderCallback.EVENT.register((context, context2) -> render(context, context2.getDynamicDeltaTicks()));
    }
    public static void draftGauge() {
        if (!MCRiderMain.isRidingKart || !MCRiderMain.isPlayingInGame() || !MCRiderConfig.INSTANCE.useDraftGauge) return;

        draftChargeTick = 0;

        // 다른 플레이어의 effect는 읽을 수 없음, 관전 시 관전자에게 effect 적용 후 감지
        var effect = Objects.requireNonNull(client.player).getStatusEffect(StatusEffects.WIND_CHARGED);

        if (effect != null) {
            if (effect.getAmplifier() == 169) {
                draftChargeTick = effect.getDuration() - 10;
            }
        }
        isChargingDraft = 0 < draftChargeTick && draftChargeTick <= 60;
        isDraftActive = draftChargeTick > 100;
    }
    public void render(DrawContext context, float tickDelta) {
        if (!MCRiderMain.isRidingKart || !MCRiderMain.isPlayingInGame() || !MCRiderConfig.INSTANCE.useDraftGauge) return;

        int blueColor = 0xFF0080FF;
        int yellowColor = 0xFFFFFF00;
        if (isChargingDraft) {
            float progress = (50 - draftChargeTick) / 50f;
            renderVerticalGauge(context, blueColor, progress, "");
        }
        if (isDraftActive) {
            if (389 <= draftChargeTick) {
                renderVerticalGauge(context, yellowColor, 1, "");
            }
            else if (387 <= draftChargeTick) {
                renderVerticalGauge(context, blueColor, 1, "");
            }
            else if (362 <= draftChargeTick) {
                renderVerticalGauge(context, yellowColor, 1, "DRAFT");
            }
            else if (360 <= draftChargeTick) {
                renderVerticalGauge(context, yellowColor, 0, "");
            }
        }
    }
    public void renderVerticalGauge(DrawContext context, int color, float fillRatio, String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!MCRiderMain.isPlayingInGame()) return;

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        // 게이지 위치: 화면 중앙보다 약간 왼쪽, 머리 높이 근처
        int gaugeWidth = 4;
        int gaugeHeight = 35;
        int gaugeX = screenWidth / 2 - 35;
        int gaugeY = screenHeight / 2 - gaugeHeight / 2;

        // 배경 색상 (반투명 검은색)
        int backgroundColor = 0x88000000;

        // 배경 그리기
        context.fill(gaugeX, gaugeY, gaugeX + gaugeWidth, gaugeY + gaugeHeight, backgroundColor);

        // 채워진 부분 계산
        int filledHeight = (int)(gaugeHeight * Math.max(0, Math.min(1, fillRatio)));
        int fillTop = gaugeY + gaugeHeight - filledHeight;

        // 채움 그리기
        context.fill(gaugeX, fillTop, gaugeX + gaugeWidth, gaugeY + gaugeHeight, color);

        // 텍스트 출력 (문자열이 비어있지 않을 때만)
        if (text != null && !text.isEmpty()) {
            TextRenderer renderer = client.textRenderer;
            int textWidth = renderer.getWidth(text);

            int anchorX = gaugeX + (gaugeWidth / 2);
            int anchorY = gaugeY - 3;

            float scale = 0.6f;
            //context.getMatrices().push();
            //context.getMatrices().translate(anchorX, anchorY, 0);
            //context.getMatrices().scale(scale, scale, 1.0f);
//
            //int localX = -textWidth / 2;
            //int localY = -renderer.fontHeight;
//
            //context.drawTextWithShadow(renderer, text, localX, localY, color);
            //context.getMatrices().pop();

            context.getMatrices().translate(anchorX, anchorY);
            context.getMatrices().scale(scale, scale);

            int localX = -textWidth / 2;
            int localY = -renderer.fontHeight;

            context.drawTextWithShadow(renderer, text, localX, localY, color);
        }
    }
}