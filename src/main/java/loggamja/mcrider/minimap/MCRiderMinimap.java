package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import loggamja.mcrider.MCRiderConfig;
import loggamja.mcrider.MCRiderMain;
import net.minecraft.world.World;

import java.util.Objects;

/**
 * (WIP) 플러드필 기반 트랙 미니맵 — "색깔 관계 그래프" 방식. 진입점.
 *
 * 실제 로직은 세 클래스로 나뉘고, 이 클래스는 틱/렌더 콜백을 등록해 매 틱 순서대로 호출하는
 * 조립(orchestration) 역할만 한다:
 *  - {@link ColorGraph}: 색 간 부모/자식 관계 + union-find 병합
 *  - {@link FrontierSearch}: 월드 블록 조회 + 플러드필 탐색 엔진 + 방문 데이터 저장
 *  - {@link MinimapRenderer}: 텍스처 관리 + 화면 그리기
 *
 * 규칙0: 양방향 이동 → 색 유지. 규칙1: 고아 진입(TP/리스폰 포함) → 새 루트 색.
 * 규칙2: 단방향 이동 → 새 색(직전 색의 자식). 규칙3: 양방향 인접 시만 병합.
 * 규칙4(표시): 플레이어 위치 색(resolve) + 그 자손만 표시. 설정이 "디버그"면 색상별 구분 표시(isDebugColors()).
 */
public class MCRiderMinimap implements ClientModInitializer {

    /** true면 색마다 다른 색으로 표시(디버그). 이제 컴파일 상수가 아니라 설정의
     *  "미니맵: 디버그"(useMinimap==2) 값을 그대로 따른다.
     *  FrontierSearch와 MinimapRenderer 양쪽이 참조하는 공용 토글이라 진입점에 둔다. */
    public static boolean isDebugColors() {
        return MCRiderConfig.INSTANCE.useMinimap == 2;
    }

    /** true면 폭이 1칸인 통로(1칸 너비 수직굴 포함)는 아예 프론티어로 확장하지 않는다.
     *  "탐색은 했지만 화면에 숨김"이 아니라 "그 방향으로 확장 자체를 안 함"이라, 좁은 통로
     *  뒤에 이어지는 지형도 그 통로를 거쳐서는 발견되지 않는다(다른 경로로 이어져 있다면
     *  그쪽에서 정상 탐색됨). FrontierSearch.floodFillWithVertical이 참조한다. */
    public static final boolean EXCLUDE_NARROW_PATHS = true;

    /** 여러 클래스가 공유하는 클라이언트 싱글턴. */
    static MinecraftClient client = MinecraftClient.getInstance();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> onTickStart());
        HudRenderCallback.EVENT.register((context, context2) ->
                MinimapRenderer.renderMinimap(context, context2.getTickProgress(false)));

        ClientPlayConnectionEvents.DISCONNECT.register((client, handler) -> {
            clearAllMap();
        });
    }
    private static World lastWorld = null;

    private static void onTickStart() {
        if (MCRiderConfig.INSTANCE.useMinimap == 0) return;
        // 디버그 편의상 상시 켜뒀던 것을 원래 의도대로 되돌림 — 레이더(MCRiderRadar)처럼
        // 카트를 타고 있을 때만 탐색/렌더링한다. 안 타는 동안엔 예산도 안 쓰고 화면도 안 그림.
        if (!MCRiderMain.isRidingKart) return;
        if (client.player == null || client.world == null) return;

        if (client.world != lastWorld) {
            clearAllMap();
        }
        lastWorld = client.world;

        int colorCount = 0;
        for (Long2LongOpenHashMap.Entry e : ColorGraph.colorParentPtr.long2LongEntrySet()) {
            if (e.getLongValue() == e.getLongKey()) colorCount++;
        }
        if (colorCount >= 5000) {
            System.out.println("[MCRider] Minimap color limit exceeded (" + colorCount + "), resetting map.");
            clearAllMap();
            return;
        }

        final int playerMargin = 5;
        BlockPos start = client.player.getBlockPos().up();

        int searchRange = (int) ((MinimapRenderer.maxDist + playerMargin * 2) * 2);
        FrontierSearch.floodFillWithVertical(start, searchRange, FrontierSearch.STAGING_BUDGET_PER_TICK);

        // 텍스처 (재)생성 전에 activeColor/activeSet을 먼저 확정한다. 순서가 바뀌면
        // rebuildTexture가 새 컬럼을 잘못(투명하게) 그릴 수 있다.
        // floodFill이 정상 완주하면 이미 activeColor를 갱신하고 나오므로, 그때는 보정을
        // 건너뛴다(하향 스캔 반복 + 히스테리시스 이중 증가 방지). 초기 리턴한 경우에만 보정.
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

    /** 미니맵 원이 실제로 표시하는 반경(월드 블록 단위). MCRiderRadar가 미니맵과 같은
     *  화면 영역을 공유할 때 자신의 축척을 이 값에 맞추는 용도로 쓴다. */
    public static double getViewDistance() {
        return MinimapRenderer.maxDist;
    }
}
