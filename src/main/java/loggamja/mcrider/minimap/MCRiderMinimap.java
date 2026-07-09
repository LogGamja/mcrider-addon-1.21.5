package loggamja.mcrider.minimap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
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

// 플로드필 기반 트랙 미니맵("색깔 관계 그래프" 방식)의 진입점 (규칙 번호는 ColorGraph.java와 동일하게 맞춤)
// 규칙1: 고아 진입(TP/리스폰 포함)은 새 루트 색
// 규칙2: 양방향 이동은 색 유지
// 규칙3: 단방향 이동은 새 색(직전 색의 자식)
// 규칙4: 양방향 인접 시, 혹은 자식이 조상에게 단방향 인접 시 병합
// 규칙5: 플레이어 위치 색(resolve) + 그 자손만 표시. 디버그 설정이면 색상별 구분 표시
public class MCRiderMinimap implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcrider");

    public static boolean isDebugColors() {
        return MCRiderConfig.INSTANCE.useMinimap == 7;
    }
    public static final boolean EXCLUDE_NARROW_PATHS = true;

    static MinecraftClient client = MinecraftClient.getInstance();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> onTickStart());

        HudLayerRegistrationCallback.EVENT.register(layeredDrawer ->
                layeredDrawer.attachLayerAfter(IdentifiedLayer.CHAT,
                        Identifier.of("mcrider-official", "minimap_hud"),
                        (context, tickCounter) -> MinimapRenderer.renderMinimap(context, tickCounter.getTickProgress(false))));

        ClientPlayConnectionEvents.DISCONNECT.register((client, handler) -> {
            clearAllMap();
        });

        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) ->
                BlockSearch.invalidateChunkCacheAt(chunk.getPos().x, chunk.getPos().z));
    }
    private static World lastWorld = null;
    private static boolean lastDebugColors = false;

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

        // 디버그 모드 전환 시 전체 재도색 (렌더 방식 변경)
        boolean debugColors = isDebugColors();
        if (debugColors != lastDebugColors) {
            lastDebugColors = debugColors;
            FrontierSearch.markAllColumnsDirty();
        }

        final int playerMargin = 5;
        BlockPos start = MCRiderMain.getRidingPlayer().getBlockPos().up();

        int searchRange = (int) ((MinimapRenderer.maxDist + playerMargin * 2) * 2);
        FrontierSearch.floodFillWithVertical(start, searchRange, FrontierSearch.STAGING_BUDGET_PER_TICK);

        // 순서 불변식: floodFill(병합/paint) → rebuildActiveSet → ensureOriginFor → repaintDirtyColumns
        // ensureOriginFor 호출 시점에 이번 틱 dirty 마킹이 완료됨. 픽셀 복사는 stale이 아님.
        //
        // floodFillWithVertical이 world/청크미로딩/anchor 못 찾음으로 초기 return하면 activeColor는
        // 이번 틱에 갱신 시도조차 안 된 것이므로 바뀌지 않는다 - 그 상태로도 rebuildActiveSet은
        // 안전하다(캐시가 활성 색이 그대로임을 보고 재계산을 건너뛴다). floodFill이 끝까지 진행됐다면
        // 이미 내부에서 rebuildActiveSet을 호출했으므로 여기 호출은 캐시 히트로 사실상 no-op이다.
        FrontierSearch.rebuildActiveSet();

        MinimapRenderer.ensureOriginFor(start);
        MinimapRenderer.repaintDirtyColumns(start);
    }
    public static void clearAllMap() {
        lastWorld = null;

        FrontierSearch.reset();
        ColorGraph.reset();
        MinimapRenderer.reset();
    }
}
