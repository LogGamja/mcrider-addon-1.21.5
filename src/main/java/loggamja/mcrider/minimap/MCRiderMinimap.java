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

import java.lang.ref.WeakReference;

// 플로드필 기반 트랙 미니맵 (색깔 관계 그래프 방식)
// 규칙은 ColorGraph.java 참고
public class MCRiderMinimap implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcrider");

    public static boolean isDebugColors() {
        return MCRiderConfig.INSTANCE.useMinimap == 7;
    }
    public static final boolean EXCLUDE_NARROW_PATHS = true;

    static MinecraftClient client;

    @Override
    public void onInitializeClient() {
        client = MinecraftClient.getInstance();

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
        // CHUNK_NOT_LOADED로 보류된 셀 복구용
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            BlockSearch.invalidateChunkCacheAt(chunk.getPos().x, chunk.getPos().z); // 청크 교체(재전송) 시 인스턴스가 바뀔 수 있음
            FrontierSearch.notifyChunkLoaded();
        });
    }
    private static WeakReference<World> lastWorld = new WeakReference<>(null);
    private static boolean lastDebugColors = false;

    private static void onTickStart() {
        if (MCRiderConfig.INSTANCE.useMinimap == 0) return;

        if (!MCRiderMain.isRidingKart) return;
        if (client.player == null || client.world == null) return;

        if (client.world != lastWorld.get()) {
            clearAllMap();
        }
        lastWorld = new WeakReference<>(client.world);

        if (ColorGraph.actualColorCount >= 2500) {
            LOGGER.warn("[MCRider] Minimap color limit exceeded ({}), resetting map.", ColorGraph.actualColorCount);
            clearAllMap();
            lastWorld = new WeakReference<>(client.world);

            return;
        }

        boolean debugColors = isDebugColors();
        if (debugColors != lastDebugColors) {
            lastDebugColors = debugColors;
            FrontierSearch.markAllColumnsDirty();
        }

        final int playerMargin = 5;
        BlockPos start = MCRiderMain.getRidingPlayer().getBlockPos().up();

        int searchRange = (int) ((MinimapRenderer.MAX_DIST + playerMargin * 2) * 2);
        FrontierSearch.floodFillWithVertical(start, searchRange, FrontierSearch.STAGING_BUDGET_PER_TICK);

        // floodFill, rebuildActiveSet, ensureOriginFor, repaintDirtyColumns 순서를 지켜야 한다.
        // rebuildActiveSet은 colorGraphVersion을 캐시 키로 써서 그래프가 실제로 바뀐 경우만 재계산한다.
        FrontierSearch.rebuildActiveSet();

        MinimapRenderer.ensureOriginFor(start);
        MinimapRenderer.repaintDirtyColumns();
    }
    public static void clearAllMap() {
        lastWorld = new WeakReference<>(null);

        FrontierSearch.reset();
        ColorGraph.reset();
        MinimapRenderer.reset();
    }

    // 카트 세션(탑승~하차) PNG export 훅. exportSessionEnd()는 반드시 clearAllMap()보다 먼저 호출해야
    // 세션 동안의 방문 기록(FrontierSearch.visitedColumns)이 지워지기 전에 이미지를 만들 수 있다.
    public static void exportSessionStart() {
        MinimapExporter.startSession();
    }
    public static void exportSessionEnd() {
        MinimapExporter.endSession();
    }
}
