package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static loggamja.mcrider.minimap.ColorGraph.NO_ID;

/**
 * 플로드필 탐색 엔진 + 방문 셀/컬럼 저장소.
 * "지금까지 어디를 탐색했고 어떤 색인지"(cellColor/visitedColumns/columnsByRoot/dirtyColumns)와
 * "지금 뭘 화면에 보여줄지"(activeColor/activeSet/searchActiveSet)가 전부 여기 있다.
 * ColorGraph와 MinimapRenderer 양쪽이 참조하는 미니맵의 핵심 엔진이다.
 */
final class FrontierSearch {
    private FrontierSearch() {}

    // 탐색 상태
    /** 방문 셀의 색 ID. 키 BlockPos.asLong, 값 색 ID(불변). */
    static Long2LongOpenHashMap cellColor = new Long2LongOpenHashMap();
    /** packColumn(x,z)가 방문한 Y 집합(텍스처 도색 대상). */
    static Long2ObjectOpenHashMap<IntOpenHashSet> visitedColumns = new Long2ObjectOpenHashMap<>();
    /** 이번 틱(또는 병합/활성색 변경)으로 다시 그려야 할 컬럼 집합. columnsByRoot 역인덱스
     *  덕분에 실제로 영향받는 컬럼만 담아, 재도색 비용이 변경분에만 비례한다. */
    static LongOpenHashSet dirtyColumns = new LongOpenHashSet();
    /** 루트 색이 칠한 컬럼들의 역인덱스. 병합/활성색 변경 시 영향받는 컬럼만
     *  dirtyColumns에 추가하기 위해 쓴다. paintCell에서 채워지고, 병합 시 생존 루트로 이전된다.
     *  ColorGraph.absorbInto가 병합 시 이 맵을 직접 참조/이전한다. */
    static Long2ObjectOpenHashMap<LongOpenHashSet> columnsByRoot = new Long2ObjectOpenHashMap<>();

    static long activeColor = NO_ID;

    static { cellColor.defaultReturnValue(NO_ID); }

    // 예산
    static final int STAGING_BUDGET_PER_TICK = 1024;
    static final long STAGING_TIME_BUDGET_NANOS = 1_000_000L; // 1ms (50ms 틱의 2%)
    /** 착지/텔레포트 직후처럼 뷰 반경 안에 아직 못 훑은 프론티어가 남아있을 때만 쓰는 확장
     *  예산. 뷰 반경(REANCHOR_MARGIN과 동일)이 다 채워지면 다음 틱부터 STAGING_*으로 복귀한다.
     *  MinimapRenderer의 REPAINT 예산(이미 그려진 셀 재도색)과는 별개라 서로 간섭하지 않는다. */
    static final int URGENT_SEARCH_RANGE = MinimapRenderer.REANCHOR_MARGIN;
    static final int URGENT_SEARCH_BUDGET_PER_TICK = 4096;
    static final long URGENT_SEARCH_TIME_BUDGET_NANOS = 4_000_000L; // 4ms (50ms 틱의 8%)
    /** activeColor 전환 히스테리시스: 같은 후보 색이 이 값만큼 연속으로 나와야 전환한다.
     *  벽 경계처럼 anchor 판정이 매 틱 흔들리는 위치에서 activeColor가 잡음성으로 반복
     *  전환되는 것을 막는다. */
    static final int ACTIVE_COLOR_SWITCH_STREAK = 3;

    // ---- STAGING/URGENT 예산 캘리브레이팅용 임시 계측. 튜닝 끝나면 DEBUG_BUDGET_LOG를 false로
    // 돌리거나 이 블록과 floodFillWithVertical 안의 계측 코드를 통째로 지운다. 1초(20틱)마다
    // staging/urgent 각각 호출 횟수, 평균/최대 소요 시간, 평균 처리 셀 수, 그리고 "시간 예산에
    // 걸려 멈춤" vs "카운트 예산에 걸려 멈춤" 횟수를 한 줄로 요약해 찍는다. ----
    private static final Logger DEBUG_BUDGET_LOGGER = LoggerFactory.getLogger("mcrider");
    private static final boolean DEBUG_BUDGET_LOG = true;
    private static final int DEBUG_LOG_INTERVAL_TICKS = 20;
    private static int debugTicksInWindow = 0;
    private static int debugStagingCalls = 0, debugUrgentCalls = 0;
    private static long debugStagingNanosSum = 0, debugUrgentNanosSum = 0;
    private static long debugStagingNanosMax = 0, debugUrgentNanosMax = 0;
    private static long debugStagingCellsSum = 0, debugUrgentCellsSum = 0;
    private static int debugStagingTimeStops = 0, debugUrgentTimeStops = 0;
    private static int debugStagingCountStops = 0, debugUrgentCountStops = 0;

    private static void debugRecordBudgetUsage(boolean urgent, long elapsedNanos, int cellsUsed,
                                                boolean timeStop, boolean countStop) {
        if (!DEBUG_BUDGET_LOG) return;
        if (urgent) {
            debugUrgentCalls++;
            debugUrgentNanosSum += elapsedNanos;
            debugUrgentNanosMax = Math.max(debugUrgentNanosMax, elapsedNanos);
            debugUrgentCellsSum += cellsUsed;
            if (timeStop) debugUrgentTimeStops++;
            if (countStop) debugUrgentCountStops++;
        } else {
            debugStagingCalls++;
            debugStagingNanosSum += elapsedNanos;
            debugStagingNanosMax = Math.max(debugStagingNanosMax, elapsedNanos);
            debugStagingCellsSum += cellsUsed;
            if (timeStop) debugStagingTimeStops++;
            if (countStop) debugStagingCountStops++;
        }

        if (++debugTicksInWindow >= DEBUG_LOG_INTERVAL_TICKS) {
            if (debugStagingCalls > 0) {
                DEBUG_BUDGET_LOGGER.info(
                        "[BudgetDebug] STAGING calls={} avg={}us max={}us avgCells={} timeStop={} countStop={}",
                        debugStagingCalls,
                        debugStagingNanosSum / debugStagingCalls / 1000,
                        debugStagingNanosMax / 1000,
                        debugStagingCellsSum / debugStagingCalls,
                        debugStagingTimeStops, debugStagingCountStops);
            }
            if (debugUrgentCalls > 0) {
                DEBUG_BUDGET_LOGGER.info(
                        "[BudgetDebug] URGENT  calls={} avg={}us max={}us avgCells={} timeStop={} countStop={}",
                        debugUrgentCalls,
                        debugUrgentNanosSum / debugUrgentCalls / 1000,
                        debugUrgentNanosMax / 1000,
                        debugUrgentCellsSum / debugUrgentCalls,
                        debugUrgentTimeStops, debugUrgentCountStops);
            }
            debugTicksInWindow = 0;
            debugStagingCalls = 0; debugUrgentCalls = 0;
            debugStagingNanosSum = 0; debugUrgentNanosSum = 0;
            debugStagingNanosMax = 0; debugUrgentNanosMax = 0;
            debugStagingCellsSum = 0; debugUrgentCellsSum = 0;
            debugStagingTimeStops = 0; debugUrgentTimeStops = 0;
            debugStagingCountStops = 0; debugUrgentCountStops = 0;
        }
    }

    // 표시/탐색용 activeSet

    /** "활성 색 + 그 자손"의 resolve된 집합(표시용, 비디버그 모드). 틱당 1회 계산해 캐시. */
    static final LongOpenHashSet activeSet = new LongOpenHashSet();
    private static long activeSetVersion = -1;
    private static long activeSetSnapshotColor = NO_ID;
    /** rebuildActiveSet 재계산 시 "직전 activeSet"을 담아두는 diff용 버퍼. */
    private static final LongOpenHashSet prevActiveSetForDiff = new LongOpenHashSet();

    /** "탐색 허용 범위" 전용 activeSet. 표시용(히스테리시스 걸린 activeColor 기반)을 그대로
     *  쓰면 교착 상태가 생긴다: 빠르게 새 트랙에 올라탈 때 매 틱 새 색이 시드되는데 그 색이
     *  옛 activeColor의 activeSet에 없어 즉시 exile되고, 프론티어가 안 이어지니 같은 후보가
     *  연속으로 안 나와 히스테리시스가 안 채워지고, 그래서 activeColor도 안 바뀌는 순환이다.
     *  그래서 탐색 쪽은 히스테리시스 없이 "지금 서 있는 셀의 색"을 즉시 기준으로 삼는다. */
    static final LongOpenHashSet searchActiveSet = new LongOpenHashSet();
    private static long searchActiveSetSnapshotRoot = NO_ID;
    private static long searchActiveSetVersion = -1;

    /** rebuildActiveSet/rebuildSearchActiveSet이 공유하는 BFS 큐(매 틱 new 방지). 두 호출은
     *  같은 틱에 순차 실행되고 재진입하지 않으므로 하나만 두고 재사용해도 안전하다. */
    private static final LongArrayFIFOQueue subtreeQueue = new LongArrayFIFOQueue();

    /** root와 그 자손(resolve됨)을 out에 채운다. out은 호출 전 비어 있어야 한다(각 호출부가
     *  diff 등 자기 사정에 맞춰 clear 시점을 정하므로 여기선 clear하지 않음). */
    private static void collectColorSubtree(long root, LongOpenHashSet out) {
        if (root == NO_ID) return;
        LongArrayFIFOQueue q = subtreeQueue;
        q.clear();
        out.add(root);
        q.enqueue(root);
        while (!q.isEmpty()) {
            long cur = q.dequeueLong();
            LongOpenHashSet kids = ColorGraph.parentToChildren.get(cur);
            if (kids == null) continue;
            LongIterator it = kids.iterator();
            while (it.hasNext()) {
                long kid = ColorGraph.resolve(it.nextLong());
                if (out.add(kid)) q.enqueue(kid);
            }
        }
    }

    private static void rebuildSearchActiveSet(long liveRoot) {
        if (searchActiveSetSnapshotRoot == liveRoot && searchActiveSetVersion == ColorGraph.colorGraphVersion) {
            return;
        }
        searchActiveSet.clear();
        collectColorSubtree(liveRoot, searchActiveSet);
        searchActiveSetSnapshotRoot = liveRoot;
        searchActiveSetVersion = ColorGraph.colorGraphVersion;
    }

    static void rebuildActiveSet() {
        if (activeSetSnapshotColor == activeColor && activeSetVersion == ColorGraph.colorGraphVersion) {
            return; // 캐시 유효: activeColor도 그래프도 안 바뀜.
        }
        // 재계산 전 이전 activeSet을 보존해 새 집합과 diff, 실제 변경된 루트의 컬럼만 dirty
        // 표시한다(전체 재도색 방지). 디버그 모드는 activeSet과 무관하므로 diff 불필요.
        prevActiveSetForDiff.clear();
        if (!MCRiderMinimap.isDebugColors()) prevActiveSetForDiff.addAll(activeSet);

        activeSet.clear();
        collectColorSubtree(activeColor, activeSet);
        activeSetSnapshotColor = activeColor;
        activeSetVersion = ColorGraph.colorGraphVersion;

        if (!MCRiderMinimap.isDebugColors()) {
            LongIterator it = prevActiveSetForDiff.iterator();
            while (it.hasNext()) {
                long r = it.nextLong();
                if (!activeSet.contains(r)) markColumnsDirtyForRoot(r);
            }
            it = activeSet.iterator();
            while (it.hasNext()) {
                long r = it.nextLong();
                if (!prevActiveSetForDiff.contains(r)) markColumnsDirtyForRoot(r);
            }
        }
    }

    /** root 색에 속한 컬럼들을 전부 dirtyColumns에 추가한다. visitedColumns 전체가 아니라
     *  columnsByRoot 버킷만 훑으므로 "이 색이 칠한 컬럼 수"에만 비례하는 저렴한 연산이다.
     *  ColorGraph.absorbInto(병합)에서도 호출한다. */
    static void markColumnsDirtyForRoot(long root) {
        LongOpenHashSet cols = columnsByRoot.get(root);
        if (cols != null) dirtyColumns.addAll(cols);
    }

    static long resolvedRootAt(int x, int y, int z) {
        long id = cellColor.get(BlockPos.asLong(x, y, z));
        return id == NO_ID ? NO_ID : ColorGraph.resolve(id);
    }

    private static long pendingActiveColorCandidate = NO_ID;
    private static int pendingActiveColorStreak = 0;

    /** 이번 floodFillWithVertical 호출이 activeColor 갱신(updateActiveColorFromCell)까지
     *  도달했으면 true. floodFill이 초기 리턴(청크 미로딩 등)하면 false로 남아, onTickStart가
     *  그때만 updateActiveColor로 보정한다. 정상 완주 시엔 중복 갱신(하향 스캔 반복 +
     *  히스테리시스 streak 이중 증가)을 막는다. */
    static boolean activeColorUpdatedThisTick = false;

    static void updateActiveColor(BlockPos start) {
        updateActiveColorFromCell(resolvePlayerCell(start));
    }

    /** 이미 구해둔 플레이어 앵커 셀로 activeColor를 갱신한다(resolvePlayerCell 중복 호출 회피). */
    static void updateActiveColorFromCell(long cell) {
        if (cell == NO_ID) return;
        long id = cellColor.get(cell);
        if (id == NO_ID) return;
        long candidate = ColorGraph.resolve(id);

        if (candidate == activeColor) {
            pendingActiveColorCandidate = NO_ID;
            pendingActiveColorStreak = 0;
            return;
        }

        if (candidate == pendingActiveColorCandidate) {
            pendingActiveColorStreak++;
        } else {
            pendingActiveColorCandidate = candidate;
            pendingActiveColorStreak = 1;
        }

        if (pendingActiveColorStreak >= ACTIVE_COLOR_SWITCH_STREAK) {
            activeColor = candidate;
            pendingActiveColorCandidate = NO_ID;
            pendingActiveColorStreak = 0;
        }
    }

    static long resolvePlayerCell(BlockPos start) {
        // findAnchorCell로 실제로 내려가며 확인하므로, 플레이어가 트랙 위 얼마나 떠 있든
        // 그 아래 트랙을 정확히 찾는다.
        var world = MCRiderMinimap.client.world;
        if (world == null) return NO_ID;
        if (!BlockSearch.isChunkLoadedAt(start.getX(), start.getZ())) return NO_ID;
        long anchor = findAnchorCell(start.getX(), start.getY(), start.getZ(), world.getBottomY());
        if (anchor == NO_ID) return NO_ID;
        if (cellColor.get(anchor) == NO_ID) return NO_ID;
        return anchor;
    }

    static long findAnchorCell(int x, int y, int z, int bottomY) {
        if (BlockSearch.isAirAt(x, y, z)) {
            int startY = y;
            while (y > bottomY) {
                if (cellColor.get(BlockPos.asLong(x, y, z)) != NO_ID) break;
                if (!BlockSearch.isAirAt(x, y - 1, z)) break;
                if (startY - y >= BlockSearch.MAX_ANCHOR_DROP_SEARCH) break;
                y--;
            }
        }
        if (y <= bottomY) return NO_ID;
        return BlockPos.asLong(x, y, z);
    }

    private static long activeColorOrPark(long cell, int cx, int cz, boolean containToActive) {
        long curColor = cellColor.get(cell);
        if (curColor == NO_ID) return NO_ID;
        if (containToActive && !searchActiveSet.contains(ColorGraph.resolve(curColor))) {
            FrontierQueue.park(cell, cx, cz);
            return NO_ID;
        }
        return curColor;
    }

    // 플러드필 탐색
    static void floodFillWithVertical(BlockPos start, int maxRange, int updatePixel) {
        activeColorUpdatedThisTick = false;
        var world = MCRiderMinimap.client.world;
        if (world == null) return;

        // 청크 캐시는 틱 사이에 유지한다(BlockSearch 소유). BlockSearch 호출 좌표는 전부
        // 사전에 isChunkLoadedAt() 확인을 거치므로 언로드된 청크를 읽을 위험은 없다.

        if (!BlockSearch.isChunkLoadedAt(start.getX(), start.getZ())) return;

        int sx = start.getX(), sz = start.getZ();
        long anchorCell = findAnchorCell(sx, start.getY(), sz, world.getBottomY());
        if (anchorCell == NO_ID) return;
        int sy = BlockPos.unpackLongY(anchorCell);

        // 규칙1: 발밑 셀이 미방문이면 새 루트 색 시드.
        long startCell = BlockPos.asLong(sx, sy, sz);
        if (cellColor.get(startCell) == NO_ID && BlockSearch.isStandable(sx, sy, sz, false)) {
            boolean seedIsNarrow = MCRiderMinimap.EXCLUDE_NARROW_PATHS
                    && (BlockSearch.isNarrowPassage(sx, sy, sz, 1, 0) || BlockSearch.isNarrowPassage(sx, sy, sz, 0, 1));
            if (!seedIsNarrow) {
                long c = ColorGraph.newColor(NO_ID);
                paintCell(sx, sy, sz, c);
                FrontierQueue.push(startCell, sx, sz);
            }
        }
        // 프론티어 확장을 "플레이어가 서 있는 영역 + 자손"으로 제한한다.
        // 플레이어 영역을 특정 못 하면(resolvePlayerCell 실패) 필터를 꺼서 안전 우선 무제한
        // 확장한다. resolvePlayerCell은 여기서 한 번만 호출해 재사용한다.
        long playerCell = resolvePlayerCell(start);
        updateActiveColorFromCell(playerCell); // 표시용(히스테리시스 유지) activeColor 갱신
        activeColorUpdatedThisTick = true;
        rebuildActiveSet();

        // 탐색용: 히스테리시스 없이 "지금 이 틱에 서 있는 셀의 색"을 즉시 기준으로 삼는다.
        long liveRoot = (playerCell != NO_ID) ? ColorGraph.resolve(cellColor.get(playerCell)) : NO_ID;
        rebuildSearchActiveSet(liveRoot);
        final boolean containToActive = playerCell != NO_ID;

        // deadline은 exile 부활 단계와 메인 탐색 루프가 예산을 공유하도록 앞에서 선언한다.
        // 뷰 반경 안에 처리 못 한 프론티어가 남아있으면(=착지 직후) 이번 틱만 예산을 키운다.
        boolean urgent = FrontierQueue.hasPendingWithin(URGENT_SEARCH_RANGE, sx, sz);
        final long deadline = System.nanoTime() + (urgent ? URGENT_SEARCH_TIME_BUDGET_NANOS : STAGING_TIME_BUDGET_NANOS);
        if (urgent && updatePixel < URGENT_SEARCH_BUDGET_PER_TICK) {
            updatePixel = URGENT_SEARCH_BUDGET_PER_TICK;
        }
        final long debugCallStart = DEBUG_BUDGET_LOG ? System.nanoTime() : 0;
        final int debugBudgetAtStart = updatePixel;

        // 보류 프론티어 복귀. FrontierQueue가 range/loaded 조건에 맞는 exile 청크를 찾아
        // revivedScratch에 모아주면, 여기서는 각 셀의 활성 여부만 판정해 재분류한다.
        boolean revivalTimedOut = FrontierQueue.drainExiledWithinRange(sx, sz, maxRange, deadline);
        var revivedCells = FrontierQueue.revivedScratch;
        for (int i = 0, n = revivedCells.size(); i < n; i++) {
            long cell = revivedCells.getLong(i);
            int cellX = BlockPos.unpackLongX(cell);
            int cellZ = BlockPos.unpackLongZ(cell);
            // 이미 exile 맵에서 꺼낸 셀이므로 deadline 초과 시 그냥 버리면 유실된다.
            // park으로 되돌려 넣어 다음 틱 재평가로 미룬다.
            if (!revivalTimedOut && System.nanoTime() >= deadline) revivalTimedOut = true;
            if (revivalTimedOut) {
                FrontierQueue.park(cell, cellX, cellZ);
                continue;
            }
            long curColor = activeColorOrPark(cell, cellX, cellZ, containToActive);
            if (curColor == NO_ID) continue;
            FrontierQueue.enqueue(cell, cellX, cellZ, sx, sz, maxRange);
        }

        // 프론티어를 청크 단위로 묶어 처리하되, 매 라운드마다 대기 청크를 플레이어와의
        // 거리순으로 정렬해 가까운 청크부터 비운다. 라운드 도중 새로 생긴 청크는 다음 라운드
        // 스냅샷에 자연스럽게 포함되므로, 시간/예산이 허용하는 한 유실 없이 계속 처리된다.
        boolean stop = false;
        while (!stop && !FrontierQueue.frontierChunkKeys.isEmpty()) {
            int n = FrontierQueue.sortChunkKeysByDistance(sx, sz);

            for (int ci = 0; ci < n; ci++) {
                long chunkKey = FrontierQueue.sortSnap[(int) (FrontierQueue.sortPacked[ci] & 0xFFFFFFFFL)];
                var bucket = FrontierQueue.frontierByChunk.get(chunkKey);
                if (bucket == null) continue; // 이미 다른 경로로 비워졌을 수 있음(방어적)

                while (!bucket.isEmpty()) {
                    // 이 루프의 반복 하나(셀 하나 처리)는 이미 수 μs급이라 nanoTime() 호출
                    // 오버헤드(수십 ns)가 무시할 수준이다. 배칭하면 오히려 그 배치 크기만큼
                    // 예산을 초과해서 흘려보내게 되므로(실측: 256개 배칭 시 최대 ~1ms 초과),
                    // 매 반복 정확히 체크한다.
                    if (System.nanoTime() >= deadline) {
                        stop = true;
                        break;
                    }

                    // 청크 버킷은 스택처럼 뒤에서 꺼낸다(배열 기반이라 앞에서 꺼내면 매번
                    // O(n) 시프트. 순서 자체는 정확성에 영향 없음).
                    long curPacked = bucket.removeLong(bucket.size() - 1);
                    int cx = BlockPos.unpackLongX(curPacked);
                    int cy = BlockPos.unpackLongY(curPacked);
                    int cz = BlockPos.unpackLongZ(curPacked);

                    if (maxRange < FrontierQueue.taxiDistance2D(cx, cz, sx, sz)) {
                        FrontierQueue.park(curPacked, cx, cz);
                        continue;
                    }
                    if (!BlockSearch.isChunkLoadedAt(cx, cz)) {
                        FrontierQueue.park(curPacked, cx, cz);
                        continue;
                    }

                    // 플레이어 영역(+자손)이 아니면 exile로 되돌린다. exile은 매 틱 복귀 루프가
                    // 자동 재평가하므로, 다시 활성이 되거나 병합되면 확장이 재개된다. 비활성인
                    // 동안은 실제 확장 없이 dequeue, 검사, 재park만 반복해 CPU를 아낀다.
                    long curColor = activeColorOrPark(curPacked, cx, cz, containToActive);
                    if (curColor == NO_ID) continue;

                    boolean hasBlockAt2Meter = !BlockSearch.isAirAt(cx, cy + 2, cz);
                    boolean parkedSelf = false;

                    for (int[] d : BlockSearch.DIRECTIONS) {
                        int nx = cx + d[0];
                        int nz = cz + d[1];

                        if (!BlockSearch.isChunkLoadedAt(nx, nz)) {
                            // (nx,nz)는 아직 색 없는 미확정 좌표라 그대로 park하면 exile 복귀 시
                            // curColor==NO_ID로 버려진다. 대신 색이 확정된 부모 셀(curPacked)을
                            // park해, 청크 로딩 시 4방향을 처음부터 재검사하게 한다.
                            // exile 버킷은 중복을 거르지 않는 리스트라, 미로딩 방향이 여러 개여도
                            // 한 번만 park한다(복귀 시 어차피 4방향 전부 재검사됨).
                            if (!parkedSelf) {
                                FrontierQueue.park(curPacked, cx, cz);
                                parkedSelf = true;
                            }
                            continue;
                        }

                        // (nx,cy,nz)의 air/wall을 한 번만 조회해 게이트와 resolveTargetY에 재사용한다.
                        boolean baseIsAir = BlockSearch.isAirAt(nx, cy, nz);
                        boolean baseIsWall = !baseIsAir && BlockSearch.isWallAt(nx, cy, nz);
                        if (baseIsWall) {
                            // 목적지 몸체가 벽일 때만 차단("올라서기"만 막고 "내려가기"는
                            // resolveTargetY에서 정상 처리).
                            continue;
                        }

                        int ty = BlockSearch.resolveTargetY(nx, cy, nz, baseIsAir, baseIsWall, hasBlockAt2Meter, world.getBottomY());
                        if (ty == Integer.MIN_VALUE) continue;

                        // 폭 1칸 통로(1칸 너비 수직굴 포함) 차단: 몸이 실제로 지나가는 높이
                        // 구간(cy~ty, 낙하면 그 사이 전부 / 계단이면 ty 한 층)을 스캔해 옆으로
                        // 비켜설 공간이 전혀 없는 지점이 하나라도 있으면 아예 확장하지 않는다
                        // ("탐색 후 숨김"이 아니라 "탐색 안 함": cellColor/FrontierQueue 어디에도
                        // 등록되지 않는다).
                        if (MCRiderMinimap.EXCLUDE_NARROW_PATHS
                                && BlockSearch.isNarrowPassageInRange(nx, cy, ty, nz, d[0], d[1])) {
                            continue;
                        }

                        boolean twoWay = BlockSearch.canMoveBetween(nx, ty, nz, cx, cy, cz, world.getBottomY());
                        handleReach(cx, cy, cz, curColor, nx, ty, nz, twoWay, sx, sz, maxRange);
                    }

                    // 실제 확장 작업(4방향 검사)을 마친 셀만 예산을 소모한다. 재park된 값싼
                    // 셀은 예산을 안 먹으므로, 비활성 영역 churn이 활성 트랙 탐색을 굶기지 않는다.
                    if (--updatePixel <= 0) {
                        stop = true;
                        break;
                    }
                }

                if (bucket.isEmpty()) {
                    FrontierQueue.removeChunk(chunkKey);
                }
                if (stop) break;
            }
        }

        if (DEBUG_BUDGET_LOG) {
            long elapsedNanos = System.nanoTime() - debugCallStart;
            int cellsUsed = debugBudgetAtStart - updatePixel;
            boolean countStop = updatePixel <= 0;
            boolean timeStop = stop && !countStop;
            debugRecordBudgetUsage(urgent, elapsedNanos, cellsUsed, timeStop, countStop);
        }
    }

    static void handleReach(int cx, int cy, int cz, long curColor, int tx, int ty, int tz, boolean twoWay,
                            int sx, int sz, int maxRange) {
        long targetCell = BlockPos.asLong(tx, ty, tz);
        long existing = cellColor.get(targetCell);
        if (existing == NO_ID) {
            // 평지(ty==cy), 계단(ty==cy+1)은 resolveTargetY가 이미 같은 좌표의 머리 공간을
            // 확인했으므로 재조회하지 않는다. 낙하(ty<cy)는 착지 높이를 아직 안 봤으므로 재확인.
            boolean headAlreadyChecked = (ty >= cy);
            if (!BlockSearch.isStandable(tx, ty, tz, headAlreadyChecked)) return;
            long color;
            if (twoWay) {
                color = curColor;
            } else {
                color = ColorGraph.newColor(curColor);
                // 부모가 활성 집합에 있으면 방금 만든 자식도 즉시 두 activeSet(표시/탐색)
                // 모두에 넣는다. 표시용만 넣으면 다음 프론티어 처리에서 searchActiveSet
                // 기준으로 곧바로 걸러져 exile되고, 정상화까지 한 틱 지연이 생긴다.
                long curRoot = ColorGraph.resolve(curColor);
                long childRoot = ColorGraph.resolve(color);
                if (activeSet.contains(curRoot)) activeSet.add(childRoot);
                if (searchActiveSet.contains(curRoot)) searchActiveSet.add(childRoot);
            }
            paintCell(tx, ty, tz, color);
            FrontierQueue.enqueue(targetCell, tx, tz, sx, sz, maxRange);
        } else {
            if (twoWay) {
                ColorGraph.mergeColors(curColor, existing);
            } else {
                // 단방향으로 이미 다른 경로에서 칠해진 칸에 도달한 경우. 색은 합치지 않되
                // (규칙3 유지) 부모-자식 엣지만 추가해 표시 판정(활성+자손)에 반영한다.
                // 안 남기면 활성 트랙 아래 이미 칠해진 저지대가 "자손"으로 인정 안 돼 화면에
                // 표시되지 않는다.
                long parentRoot = ColorGraph.resolve(curColor);
                long childRoot = ColorGraph.resolve(existing);
                if (parentRoot != childRoot) {
                    // 사이클 방지: childRoot가 이미 parentRoot의 조상이면 엣지를 추가하지 않는다.
                    // birth(자식이 부모보다 나중에 태어남) 기반 지름길은 안 쓴다. 병합
                    // (ColorGraph.absorbInto)이 survivor를 최소 birth로 골라 간선을 재배선하면
                    // 이 불변식이 깨질 수 있어, 그 경우 지름길이 실제 사이클을 놓칠 수 있다.
                    // 조상 집합 계산은 보통 짧은 조상 사슬 하나만 훑으므로 비용도 낮다.
                    LongOpenHashSet parentAncestors = ColorGraph.scratchParentAncestors;
                    parentAncestors.clear();
                    ColorGraph.collectAncestors(parentRoot, parentAncestors);
                    boolean isCycleMergeRequired = parentAncestors.contains(childRoot);
                    if (isCycleMergeRequired) {
                        ColorGraph.mergeColors(parentRoot, childRoot);
                    }
                    else {
                        ColorGraph.addEdge(parentRoot, childRoot);
                        if (activeSet.contains(parentRoot)) {
                            activeSet.add(childRoot);
                            // childRoot는 오래전 칠해져 dirtyColumns에서 빠졌으므로, 지금
                            // 활성 편입된 컬럼만 다시 표시 대상으로 표시한다.
                            markColumnsDirtyForRoot(childRoot);
                        }
                        // childRoot 아래 아직 못 탐색한 가지가 exile에 남아있었다면
                        // searchActiveSet에도 즉시 반영해 다음 틱까지 안 기다리고 복귀시킨다.
                        if (searchActiveSet.contains(parentRoot)) {
                            searchActiveSet.add(childRoot);
                        }
                    }
                }
            }
        }
    }

    // 방문 데이터

    static void paintCell(int x, int y, int z, long color) {
        long cell = BlockPos.asLong(x, y, z);
        cellColor.put(cell, color);
        long colKey = packColumn(x, z);
        IntOpenHashSet ys = visitedColumns.get(colKey);
        if (ys == null) {
            ys = new IntOpenHashSet(4);
            visitedColumns.put(colKey, ys);
        }
        ys.add(y);
        // 이 컬럼을 방금 칠한 색의 루트 아래 역인덱스에도 등록한다(나중에 병합되면 함께 이전됨).
        long root = ColorGraph.resolve(color);
        columnsByRoot.computeIfAbsent(root, k -> new LongOpenHashSet()).add(colKey);
        if (MCRiderMinimap.isDebugColors()) {
            MinimapRenderer.plotColumn(x, z); // 즉시 그 컬럼만 도색(디버그 전용 역방향 참조)
        } else {
            dirtyColumns.add(colKey); // 새로 칠해진 컬럼만 추가(기존 컬럼 재도색은 markColumnsDirtyForRoot가 담당)
        }
    }

    // 좌표 유틸

    static long packColumn(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
    static int unpackColumnX(long key) { return (int) (key >> 32); }
    static int unpackColumnZ(long key) { return (int) key; }

    static void reset() {
        cellColor.clear();
        visitedColumns.clear();
        dirtyColumns.clear();
        columnsByRoot.clear();
        FrontierQueue.reset();
        activeColor = NO_ID;
        activeSet.clear();
        activeSetVersion = -1;
        activeSetSnapshotColor = NO_ID;
        searchActiveSet.clear();
        searchActiveSetVersion = -1;
        searchActiveSetSnapshotRoot = NO_ID;
        pendingActiveColorCandidate = NO_ID;
        pendingActiveColorStreak = 0;
        activeColorUpdatedThisTick = false;
        BlockSearch.invalidateChunkCache();
    }
}
