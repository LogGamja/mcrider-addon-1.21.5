package loggamja.mcrider.minimap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import loggamja.mcrider.option.MCRiderConfig;
import loggamja.mcrider.MCRiderMain;
import net.minecraft.world.World;

// 플로드필 기반 트랙 미니맵("색깔 관계 그래프" 방식)의 진입점
// 규칙0: 양방향 이동은 색 유지. 규칙1: 고아 진입(TP/리스폰 포함)은 새 루트 색
// 규칙2: 단방향 이동은 새 색(직전 색의 자식)
// 규칙3: 양방향 인접 시, 혹은 자식이 조상에게 단방향 인접 시 병합
// 규칙4: 플레이어 위치 색(resolve) + 그 자손만 표시. 디버그 설정이면 색상별 구분 표시
public class MCRiderMinimap implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcrider");

    public static boolean isDebugColors() {
        return MCRiderConfig.INSTANCE.useMinimap == 2;
    }
    public static final boolean EXCLUDE_NARROW_PATHS = true;

    static MinecraftClient client = MinecraftClient.getInstance();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> onTickStart());

        // 채팅과 겹칠 때 항상 미니맵이 위에 보이도록 CHAT 레이어 뒤에 붙이고
        // hudHidden(F1)도 CHAT 레이어의 렌더 조건을 그대로 물려받아 자동으로 함께 감춰지게 한다
        HudLayerRegistrationCallback.EVENT.register(layeredDrawer ->
                layeredDrawer.attachLayerAfter(IdentifiedLayer.CHAT,
                        Identifier.of("mcrider-official", "minimap_hud"),
                        (context, tickCounter) -> MinimapRenderer.renderMinimap(context, tickCounter.getTickProgress(false))));

        ClientPlayConnectionEvents.DISCONNECT.register((client, handler) -> {
            clearAllMap();
        });
    }
    private static World lastWorld = null;

    private static void onTickStart() {
        if (MCRiderConfig.INSTANCE.useMinimap == 0) return;

        if (!MCRiderMain.isRidingKart) return;
        if (client.player == null || client.world == null) return;

        if (client.world != lastWorld) {
            clearAllMap();
        }
        lastWorld = client.world;

        if (ColorGraph.actualColorCount >= 5000) {
            LOGGER.warn("[MCRider] Minimap color limit exceeded ({}), resetting map.", ColorGraph.actualColorCount);
            clearAllMap();
            return;
        }

        final int playerMargin = 5;
        BlockPos start = client.player.getBlockPos().up();

        int searchRange = (int) ((MinimapRenderer.maxDist + playerMargin * 2) * 2);
        FrontierSearch.floodFillWithVertical(start, searchRange, FrontierSearch.STAGING_BUDGET_PER_TICK);

        // 텍스처 (재)생성 전에 activeColor/activeSet을 먼저 확정한다
        // 순서가 바뀌면 rebuildTexture가 새 컬럼을 잘못(투명하게) 그릴 수 있다
        // floodFill이 정상 완주하면 이미 activeColor를 갱신하고 나오므로 그때는 보정을 건너뛴다(하향 스캔 반복 + 히스테리시스 이중 증가 방지)
        // 초기 리턴한 경우에만 보정
        if (!FrontierSearch.activeColorUpdatedThisTick) {
            FrontierSearch.updateActiveColor(start);
        }
        FrontierSearch.rebuildActiveSet();

        MinimapRenderer.ensureOriginFor(start);
        MinimapRenderer.repaintDirtyColumns(start);
    }
    public static void clearAllMap() {
        FrontierSearch.reset();
        ColorGraph.reset();
        MinimapRenderer.reset();
    }
}
