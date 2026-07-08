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

        // BlockSearch의 4슬롯 청크 캐시가 언로드된 뒤에도 옛 Chunk 객체를 들고 있지 않도록,
        // 언로드되는 청크만 캐시에서 뽑아낸다(전체 초기화는 월드 전환/리셋 때만 일어남).
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

        // 디버그<->일반 전환 시 computeColumnColor의 의미가 통째로 바뀌므로 전체 재도색을 예약한다.
        // 안 하면 다음 재앵커까지 두 렌더 방식이 섞인 텍스처가 남는다
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
        if (!FrontierSearch.activeColorUpdatedThisTick) {
            FrontierSearch.updateActiveColor(start);
        }
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
