package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;

import static loggamja.mcrider.minimap.ColorGraph.NO_ID;

// 플로드필 탐색 엔진이자 방문 셀/컬럼의 저장소. 어디를 탐색했고 어떤 색인지(cellColor/visitedColumns/
// columnsByRoot/dirtyColumns)와 지금 뭘 보여줄지(activeColor/activeSet/searchActiveSet)가 여기 있다.
// ColorGraph와 MinimapRenderer가 참조하는 미니맵 핵심.
final class FrontierSearch {
    private FrontierSearch() {}

    // 탐색 상태
    // 방문 셀의 색 ID. 키 BlockPos.asLong, 값 색 ID(불변)
    static Long2LongOpenHashMap cellColor = new Long2LongOpenHashMap();
    // packColumn(x,z)가 방문한 Y 집합(텍스처 도색 대상).
    static Long2ObjectOpenHashMap<IntOpenHashSet> visitedColumns = new Long2ObjectOpenHashMap<>();
    // 이번 틱(또는 병합/활성색 변경)으로 다시 그려야 할 컬럼 집합. columnsByRoot 역인덱스 덕분에 재도색 비용이 실제 변경분에만 비례한다
    static LongOpenHashSet dirtyColumns = new LongOpenHashSet();
    // 루트 색이 칠한 컬럼들의 역인덱스(paintCell이 채움). 병합 시 ColorGraph.absorbInto가 생존 루트로 이전한다
    static Long2ObjectOpenHashMap<LongOpenHashSet> columnsByRoot = new Long2ObjectOpenHashMap<>();

    static long activeColor = NO_ID;

    static { cellColor.defaultReturnValue(NO_ID); }

    // 예산
    static final int STAGING_BUDGET_PER_TICK = 1024;
    static final long STAGING_TIME_BUDGET_NANOS = 1_000_000L;

    // activeColor 전환 히스테리시스: 같은 후보 색이 이 값만큼 연속으로 나와야 전환한다
    // 벽 경계처럼 매 틱 흔들리는 위치에서 잡음성 전환을 막는다
    static final int ACTIVE_COLOR_SWITCH_STREAK = 5;

    // 표시/탐색용 activeSet

    // root와 colorGraphVersion을 키로 하는 메모이즈드 재계산 상태. activeSet과 searchActiveSet이
    // 같은 패턴을 쓰므로 값 타입으로 뽑았다. 실제 집합은 static 필드 유지하고 캐시 로직만 담당.
    private static final class SubtreeCache {
        long snapshotRoot = NO_ID;
        long version = -1;
        final LongOpenHashSet prevForDiff = new LongOpenHashSet();

        // 캐시가 유효하면(루트도 그래프도 안 바뀜) 아무것도 안 하고 false를 돌려준다.
        // 아니면 prevForDiff에 재계산 전 out 내용을 담아두고(keepDiff일 때만) out을 새로 채운다.
        boolean recompute(long root, LongOpenHashSet out, boolean keepDiff) {
            if (snapshotRoot == root && version == ColorGraph.colorGraphVersion) return false;
            prevForDiff.clear();
            if (keepDiff) prevForDiff.addAll(out);
            out.clear();
            collectColorSubtree(root, out);
            snapshotRoot = root;
            version = ColorGraph.colorGraphVersion;
            return true;
        }

        void reset() {
            snapshotRoot = NO_ID;
            version = -1;
            prevForDiff.clear();
        }

        // 다른 캐시가 같은 (root, colorGraphVersion)으로 이미 계산해 둔 집합이 있으면 BFS를 다시 돌지 않고 복사한다.
        // diff 버퍼 관리는 recompute(keepDiff=true)와 동일
        boolean recomputeFrom(long root, LongOpenHashSet out, LongOpenHashSet precomputed) {
            if (snapshotRoot == root && version == ColorGraph.colorGraphVersion) return false;
            prevForDiff.clear();
            prevForDiff.addAll(out);
            out.clear();
            out.addAll(precomputed);
            snapshotRoot = root;
            version = ColorGraph.colorGraphVersion;
            return true;
        }
    }

    // "활성 색 + 그 자손"의 resolve된 집합(표시용, 비디버그 모드). 틱당 1회 계산해 캐시
    static final LongOpenHashSet activeSet = new LongOpenHashSet();
    private static final SubtreeCache activeSetCache = new SubtreeCache();

    // 탐색 필터용 activeSet. 표시용 히스테리시스와 분리해 deadlock 방지
    static final LongOpenHashSet searchActiveSet = new LongOpenHashSet();
    // colorGraphVersion은 무관한 곳의 변경에도 오르므로 버전만 보고 판단하면 무관한 변경에도
    // 반응한다. 재계산 결과의 내용이 실제로 바뀌었을 때만 true 반환. inactiveColorParked 재검사는 필요할 때만.
    private static final SubtreeCache searchActiveSetCache = new SubtreeCache();

    // 자손 없는 loser 흡수 후 collectColorSubtree 결과 내용은 같아 diff로 못 잡힌다.
    // loser 색 파킹 셀들은 이제 활성 트리에 속하므로 revive 필요. 그 빈틈을 diff와 별도로 메우는 신호.
    private static boolean searchActiveSetTouchedByMerge = false;

    // ColorGraph.absorbInto 전용 훅: survivor가 지금 searchActiveSet 소속이면 흡수된 loser
    // 쪽으로 파킹돼 있던 셀들도 재검사 대상이 되어야 하므로 플래그를 세운다
    static void noteMergeSurvivor(long survivor) {
        if (searchActiveSet.contains(survivor)) {
            searchActiveSetTouchedByMerge = true;
        }
    }

    // rebuildActiveSet/rebuildSearchActiveSet이 공유하는 BFS 큐(매 틱 new 방지, 재진입 없어 안전)
    private static final LongArrayFIFOQueue subtreeQueue = new LongArrayFIFOQueue();

    // root와 그 자손을 out에 추가 (out은 호출부가 clear)
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

    // 두 LongOpenHashSet의 내용이 같은지 박싱 없이 비교
    private static boolean longSetsEqual(LongOpenHashSet a, LongOpenHashSet b) {
        if (a.size() != b.size()) return false;
        LongIterator it = a.iterator();
        while (it.hasNext()) {
            if (!b.contains(it.nextLong())) return false;
        }
        return true;
    }

    // 반환값은 searchActiveSet의 실제 내용이 바뀌었는지 또는 revive가 필요한 병합이 있었는지.
    // diff만으로는 못 잡는 경우는 ColorGraph.absorbInto가 noteMergeSurvivor로 세워둔
    // searchActiveSetTouchedByMerge로 보완한다.
    private static boolean rebuildSearchActiveSet(long liveRoot) {
        // 정상 상태에선 liveRoot == activeColor여서 rebuildActiveSet이 방금 같은 서브트리를 계산해 뒀다.
        // 그 결과를 복사해 동일한 BFS를 두 번 도는 것을 피한다. (propagateActiveMembership의 직접
        // add는 항상 버전 bump가 선행되거나 기존 엣지에 대한 no-op이므로, 버전이 같으면 activeSet은
        // 정확히 subtree(activeColor)와 일치한다)
        boolean recomputed = (activeSetCache.snapshotRoot == liveRoot
                && activeSetCache.version == ColorGraph.colorGraphVersion)
                ? searchActiveSetCache.recomputeFrom(liveRoot, searchActiveSet, activeSet)
                : searchActiveSetCache.recompute(liveRoot, searchActiveSet, true);
        if (!recomputed) return false;

        boolean contentChanged = !longSetsEqual(searchActiveSet, searchActiveSetCache.prevForDiff);
        boolean mergeTouchedActive = searchActiveSetTouchedByMerge;
        searchActiveSetTouchedByMerge = false;
        return contentChanged || mergeTouchedActive;
    }

    // 예산 초과 시 인덱스를 들고 다음 틱에 이어가는 재개형 드레인 패턴을 캡슐화한 값 타입.
    // reviveInactiveColorParked와 processRevivedExiledCells가 같은 형태라 로직을 한 곳으로 모았다.
    // scratch 리스트는 복사 없이 소유권 이전 없이 그대로 받아 써야 두 저장소를 똑같이 다룬다.
    private static final class ResumableDrain {
        int index = 0;
        boolean inProgress = false;
        // 드레인 진행 중에 들어온 trigger를 버리지 않고 래치해 둔다 - 진행 중 새로 파킹된 셀들은
        // 다음 searchActiveSet 변경이 영영 안 올 수 있어 트리거를 놓치면 영구적으로 되살아나지 못한다
        private boolean pendingTrigger = false;

        // trigger를 래치했다가 진행 중이 아닐 때 fill로 scratch를 새로 채워 드레인을 시작한다
        void beginIfIdle(boolean trigger, LongArrayList scratch, Runnable fill) {
            pendingTrigger |= trigger;
            if (pendingTrigger && !inProgress) {
                pendingTrigger = false;
                fill.run();
                index = 0;
                inProgress = !scratch.isEmpty();
            }
        }

        // scratch를 index부터 소비하며 각 셀을 activeColorOrPark로 재검사해 활성이면 enqueue한다.
        // deadline을 넘기면 index를 남긴 채 반환하고 다음 호출(다음 틱)이 그 지점부터 이어간다.
        void drain(LongArrayList scratch, int sx, int sz, int maxRange, boolean containToActive, long deadline) {
            if (!inProgress) return;
            int sinceTimeCheck = 0;
            int n = scratch.size();
            while (index < n) {
                long cell = scratch.getLong(index);
                index++;
                long curColor = activeColorOrPark(cell, containToActive);
                if (curColor != NO_ID) {
                    int cx = BlockPos.unpackLongX(cell);
                    int cz = BlockPos.unpackLongZ(cell);
                    FrontierQueue.enqueue(cell, cx, cz, sx, sz, maxRange);
                }
                if ((++sinceTimeCheck & 0xFF) == 0 && System.nanoTime() - deadline >= 0) return; // 다음 틱에 이어서
            }
            scratch.clear();
            index = 0;
            inProgress = false;
        }

        void reset() {
            index = 0;
            inProgress = false;
            pendingTrigger = false;
        }
    }

    // inactiveColorParked 되살리기 전용 스크래치(매 틱 new 방지). 세션 내내 누적될 수 있는 크기라
    // 한 틱에 몰아서 처리하면 안 된다 — inactiveRevivalDrain이 진행 상태를 들고 있다가
    // 예산이 부족하면 다음 틱에 이어서 처리한다.
    private static final LongArrayList inactiveRevivalScratch = new LongArrayList();
    private static final ResumableDrain inactiveRevivalDrain = new ResumableDrain();

    // searchActiveSet이 바뀐 틱에 새로 드레인을 시작하거나 진행 중인 드레인을 이어간다.
    // 색이 안 맞아 보류됐던 셀들을 재검사해 활성이면 프론티어로 보내고 아니면 다시 보류.
    // 다른 단계와 deadline 공유해 이 단계가 시간 예산을 독점하지 않게 한다.
    private static void reviveInactiveColorParked(int sx, int sz, int maxRange, boolean containToActive,
                                                   boolean searchActiveSetChanged, long deadline) {
        inactiveRevivalDrain.beginIfIdle(searchActiveSetChanged, inactiveRevivalScratch, () -> {
            inactiveRevivalScratch.clear();
            FrontierQueue.reviveInactiveColorParked(inactiveRevivalScratch);
        });
        inactiveRevivalDrain.drain(inactiveRevivalScratch, sx, sz, maxRange, containToActive, deadline);
    }

    // FrontierQueue.revivedScratch 처리를 매 틱 새로 드레인하지 않고 이어가는 재개 상태.
    // 타임아웃한 셀은 이미 로딩 확인돼 있으므로 exile로 되돌리지 않는다. 되돌리면 다음 틱에 같은
    // 조건을 또 확인하는 왕복 발생. 대신 인덱스만 들고 다음 틱에 이어가서 처리한다.
    private static final ResumableDrain revivedProcessDrain = new ResumableDrain();

    private static void processRevivedExiledCells(int sx, int sz, int maxRange, boolean containToActive, long deadline) {
        revivedProcessDrain.beginIfIdle(true, FrontierQueue.revivedScratch,
                () -> FrontierQueue.drainExiledWithinRange(sx, sz, maxRange, deadline));
        revivedProcessDrain.drain(FrontierQueue.revivedScratch, sx, sz, maxRange, containToActive, deadline);
    }

    static void rebuildActiveSet() {
        // 재계산 전 이전 activeSet을 보존해 새 집합과 diff, 실제 변경된 루트의 컬럼만 dirty 표시한다(전체 재도색 방지)
        // 디버그 모드는 activeSet과 무관하므로 diff 불필요
        boolean debug = MCRiderMinimap.isDebugColors();
        if (!activeSetCache.recompute(activeColor, activeSet, !debug)) return; // 캐시 유효: activeColor도 그래프도 안 바뀜

        if (!debug) {
            LongIterator it = activeSetCache.prevForDiff.iterator();
            while (it.hasNext()) {
                long r = it.nextLong();
                if (!activeSet.contains(r)) markColumnsDirtyForRoot(r);
            }
            it = activeSet.iterator();
            while (it.hasNext()) {
                long r = it.nextLong();
                if (!activeSetCache.prevForDiff.contains(r)) markColumnsDirtyForRoot(r);
            }
        }
    }

    // root 색에 속한 컬럼들을 전부 dirtyColumns에 추가한다. columnsByRoot 버킷만 훑으므로
    // "이 색이 칠한 컬럼 수"에만 비례한다. ColorGraph.absorbInto(병합)에서도 호출한다
    static void markColumnsDirtyForRoot(long root) {
        LongOpenHashSet cols = columnsByRoot.get(root);
        if (cols != null) dirtyColumns.addAll(cols);
    }

    // 컬럼 색의 의미가 통째로 바뀔 때(디버그 모드 전환 등) 방문한 모든 컬럼의 재도색을 예약한다.
    // 실제 재도색은 repaintDirtyColumns의 기존 예산이 여러 틱에 나눠 처리한다
    static void markAllColumnsDirty() {
        dirtyColumns.addAll(visitedColumns.keySet());
    }

    // parentRoot가 activeSet/searchActiveSet 소속이면 새로 연결된 childRoot도 같은 집합에 편입시킨다.
    // handleReach가 새 색 생성 시(markDirty=false, 아직 칠해진 컬럼 없음)와 기존 색에 단방향 엣지
    // 추가 시(markDirty=true, 이미 칠해진 컬럼을 다시 그려야 함) 두 군데서 공유한다
    private static void propagateActiveMembership(long parentRoot, long childRoot, boolean markDirty) {
        if (activeSet.contains(parentRoot)) {
            activeSet.add(childRoot);
            if (markDirty) markColumnsDirtyForRoot(childRoot);
        }
        if (searchActiveSet.contains(parentRoot)) {
            boolean newlyAdded = searchActiveSet.add(childRoot);
            // 기존 색(markDirty=true)이 활성 트리에 새로 편입되면 그 색으로 파킹돼 있던 셀들을 되살려야
            // 한다. 그런데 이 직접 add는 다음 recompute의 diff에 안 잡힌다(prevForDiff에 이미 포함) -
            // childRoot가 자손 없는 색이면 diff가 영영 변화를 못 봐 파킹 셀이 얼어붙는다.
            // "자손 없는 loser 병합" 갭과 대칭인 케이스라 같은 우회 신호를 세운다.
            // (markDirty=false인 새 색은 아직 셀이 없어 파킹된 것도 없으므로 신호 불필요)
            if (markDirty && newlyAdded) {
                searchActiveSetTouchedByMerge = true;
            }
        }
    }

    // Renderer가 dirtyColumns를 전량 반영(markAllDirty)했을 때 소비 완료를 알리는 진입점.
    // Renderer가 FrontierSearch의 내부 컬렉션을 직접 clear()하지 않도록 캡슐화해
    // Search -> Renderer 단방향 의존성을 유지한다
    static void clearDirtyColumns() {
        dirtyColumns.clear();
    }

    static long resolvedRootAt(int x, int y, int z) {
        long id = cellColor.get(BlockPos.asLong(x, y, z));
        return id == NO_ID ? NO_ID : ColorGraph.resolve(id);
    }

    private static long pendingActiveColorCandidate = NO_ID;
    private static int pendingActiveColorStreak = 0;

    // 이번 floodFillWithVertical 호출이 activeColor를 갱신했으면 true. 초기 리턴 시(청크 미로딩 등)
    // false로 남는데, 그 경우들은 이번 틱에 다시 조회해도 항상 같은 이유로 실패하므로 별도 재시도는 하지 않는다.
    static boolean activeColorUpdatedThisTick = false;

    // 이미 구해둔 플레이어 앵커 셀로 activeColor를 갱신한다(resolvePlayerCell 중복 호출 회피)
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

    // 호출부가 findAnchorCell을 이미 직접 구해뒀을 때 재사용하는 버전(중복 findAnchorCell 호출 회피)
    private static long resolvePlayerCellFromAnchor(long anchor) {
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

    private static long activeColorOrPark(long cell, boolean containToActive) {
        long curColor = cellColor.get(cell);
        if (curColor == NO_ID) return NO_ID;
        if (containToActive && !searchActiveSet.contains(ColorGraph.resolve(curColor))) {
            // 청크 미로딩/범위 밖이 아니라 "색이 활성 트리 소속이 아님"이 사유이므로
            // exiledByChunk가 아닌 별도 저장소로 보내 매 틱 park/revive 반복을 피한다
            FrontierQueue.parkInactiveColor(cell);
            return NO_ID;
        }
        return curColor;
    }

    // 플러드필 탐색
    static void floodFillWithVertical(BlockPos start, int maxRange, int updatePixel) {
        var world = MCRiderMinimap.client.world;
        if (world == null) return;

        if (!BlockSearch.isChunkLoadedAt(start.getX(), start.getZ())) return;

        int sx = start.getX(), sz = start.getZ();
        long anchorCell = findAnchorCell(sx, start.getY(), sz, world.getBottomY());
        if (anchorCell == NO_ID) return;
        int sy = BlockPos.unpackLongY(anchorCell);

        long startCell = BlockPos.asLong(sx, sy, sz);
        if (cellColor.get(startCell) == NO_ID && BlockSearch.isStandable(sx, sy, sz, false)) {
            boolean seedIsNarrow = false;
            if (MCRiderMinimap.EXCLUDE_NARROW_PATHS) {
                int n1 = BlockSearch.isNarrowPassage(sx, sy, sz, 1, 0);
                int n2 = BlockSearch.isNarrowPassage(sx, sy, sz, 0, 1);
                // 미로딩 청크라 판정 불가면(UNKNOWN) 이번 틱은 시딩을 보류한다. 시드는 아직 큐에
                // 들어간 셀이 없어 park할 대상이 없으므로, 다음 틱에 이 블록이 다시 검사될 때
                // (cellColor가 여전히 NO_ID인 한 매 틱 재시도됨) 청크가 로딩됐으면 자연히 풀린다.
                seedIsNarrow = n1 == BlockSearch.PASSAGE_NARROW || n2 == BlockSearch.PASSAGE_NARROW
                        || n1 == BlockSearch.PASSAGE_UNKNOWN || n2 == BlockSearch.PASSAGE_UNKNOWN;
            }
            if (!seedIsNarrow) {
                long c = ColorGraph.newColor(NO_ID);
                paintCell(sx, sy, sz, c);
                FrontierQueue.push(startCell, sx, sz);
            }
        }
        long playerCell = resolvePlayerCellFromAnchor(anchorCell);
        updateActiveColorFromCell(playerCell);
        activeColorUpdatedThisTick = true;
        rebuildActiveSet();

        // 앵커가 잠깐 풀린 틱(점프/추락 등)에는 searchActiveSet을 재계산하지 않고 이전 필터를 유지한다.
        // NO_ID로 재계산하면 집합이 통째로 비었다 차는 왕복이 생겨, 파킹 셀 전체가 프론티어로
        // 쏟아졌다가 도로 파킹되는 낭비가 생긴다. 이런 틱은 containToActive=false라
        // activeColorOrPark가 searchActiveSet을 읽지 않으므로 stale 상태여도 안전하다
        final boolean containToActive = playerCell != NO_ID;
        boolean searchActiveSetChanged = containToActive
                && rebuildSearchActiveSet(ColorGraph.resolve(cellColor.get(playerCell)));

        final long deadline = System.nanoTime() + STAGING_TIME_BUDGET_NANOS;

        // searchActiveSet이 바뀐 틱에 새로 시작하거나, 이미 진행 중인 재검사를 이어간다(예산 초과 시 다음 틱)
        reviveInactiveColorParked(sx, sz, maxRange, containToActive, searchActiveSetChanged, deadline);

        // exile에서 되살아난 셀 처리도 같은 재개형 패턴: 못 다 처리한 나머지는 exiledByChunk로
        // 되돌리지 않고 인덱스만 들고 다음 틱에 이어간다(processRevivedExiledCells 참고)
        processRevivedExiledCells(sx, sz, maxRange, containToActive, deadline);

        boolean stop = false;
        while (!stop && !FrontierQueue.frontierByChunk.isEmpty()) {
            int n = FrontierQueue.sortChunkKeysByDistance(sx, sz);

            for (int ci = 0; ci < n; ci++) {
                long chunkKey = FrontierQueue.sortSnap[(int) (FrontierQueue.sortPacked[ci] & 0xFFFFFFFFL)];
                var bucket = FrontierQueue.frontierByChunk.get(chunkKey);
                if (bucket == null) continue;

                while (!bucket.isEmpty()) {
                    if (System.nanoTime() - deadline >= 0) {
                        stop = true;
                        break;
                    }

                    long curPacked = bucket.removeLong(bucket.size() - 1);
                    int cx = BlockPos.unpackLongX(curPacked);
                    int cy = BlockPos.unpackLongY(curPacked);
                    int cz = BlockPos.unpackLongZ(curPacked);

                    if (maxRange < FrontierQueue.taxiDistance2D(cx, cz, sx, sz)) {
                        FrontierQueue.park(curPacked, cx, cz, FrontierQueue.ParkReason.OUT_OF_RANGE);
                        continue;
                    }
                    if (!BlockSearch.isChunkLoadedAt(cx, cz)) {
                        FrontierQueue.park(curPacked, cx, cz, FrontierQueue.ParkReason.CHUNK_NOT_LOADED);
                        continue;
                    }

                    long curColor = activeColorOrPark(curPacked, containToActive);
                    if (curColor == NO_ID) continue;

                    boolean hasBlockAt2Meter = !BlockSearch.isAirAt(cx, cy + 2, cz);
                    boolean parkedSelf = false;

                    for (int[] d : BlockSearch.DIRECTIONS) {
                        int nx = cx + d[0];
                        int nz = cz + d[1];

                        if (!BlockSearch.isChunkLoadedAt(nx, nz)) {
                            // 로딩되지 않은 것은 이웃 청크라 그 청크 키로 park해야 그 이웃이 로딩되는 게 revive 조건.
                            // 자기 청크로 걸면 이미 로딩된 상태라 매 틱 즉시 되살렸다가 다시 park하는 핑퐁.
                            // 청크 로딩 경계나 낮은 렌더 거리에서 흔히 발생.
                            if (!parkedSelf) {
                                FrontierQueue.park(curPacked, nx, nz, FrontierQueue.ParkReason.CHUNK_NOT_LOADED);
                                parkedSelf = true;
                            }
                            continue;
                        }

                        boolean baseIsAir = BlockSearch.isAirAt(nx, cy, nz);
                        boolean baseIsWall = !baseIsAir && BlockSearch.isWallAt(nx, cy, nz);
                        if (baseIsWall) continue;

                        int ty = BlockSearch.resolveTargetY(nx, cy, nz, baseIsAir, baseIsWall, hasBlockAt2Meter, world.getBottomY());
                        if (ty == Integer.MIN_VALUE) continue;

                        if (MCRiderMinimap.EXCLUDE_NARROW_PATHS) {
                            int narrow = BlockSearch.isNarrowPassageInRange(nx, cy, ty, nz, d[0], d[1]);
                            if (narrow == BlockSearch.PASSAGE_UNKNOWN) {
                                // 좁은 길인지 확정할 수 없는 게 아니라 이웃 청크가 아직 안 로딩된 것.
                                // 벽으로 오인해 탐색을 막는 대신, 그 청크가 로딩될 때까지 이 셀을 보류한다.
                                if (!parkedSelf) {
                                    FrontierQueue.park(curPacked, BlockSearch.lastUnknownChunkX,
                                            BlockSearch.lastUnknownChunkZ, FrontierQueue.ParkReason.CHUNK_NOT_LOADED);
                                    parkedSelf = true;
                                }
                                continue;
                            }
                            if (narrow == BlockSearch.PASSAGE_NARROW) continue;
                        }

                        boolean twoWay = BlockSearch.canMoveBetween(nx, ty, nz, cx, cy, cz, world.getBottomY());
                        handleReach(cx, cy, cz, curColor, nx, ty, nz, twoWay, sx, sz, maxRange);
                    }

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
    }

    static void handleReach(int cx, int cy, int cz, long curColor, int tx, int ty, int tz, boolean twoWay,
                            int sx, int sz, int maxRange) {
        long targetCell = BlockPos.asLong(tx, ty, tz);
        long existing = cellColor.get(targetCell);
        if (existing == NO_ID) {
            boolean headAlreadyChecked = (ty >= cy);
            if (!BlockSearch.isStandable(tx, ty, tz, headAlreadyChecked)) return;
            long color;
            if (twoWay) {
                color = curColor;
            } else {
                color = ColorGraph.newColor(curColor);
                long curRoot = ColorGraph.resolve(curColor);
                long childRoot = ColorGraph.resolve(color);
                propagateActiveMembership(curRoot, childRoot, false);
            }
            paintCell(tx, ty, tz, color);
            FrontierQueue.enqueue(targetCell, tx, tz, sx, sz, maxRange);
        } else {
            if (twoWay) {
                ColorGraph.mergeColors(curColor, existing);
            } else {
                long parentRoot = ColorGraph.resolve(curColor);
                long childRoot = ColorGraph.resolve(existing);
                if (parentRoot != childRoot) {
                    // Cycle prevention: don't add edge if childRoot is already ancestor
                    // hasEdge(true) → 조상 BFS 스킵 안전. mergeColors는 항상 rescanCycles(do-while)로
                    // 모든 새 사이클을 완전히 해결하므로, 엣지 존재 = 사이클 없음.
                    boolean isCycleMergeRequired;
                    if (ColorGraph.hasEdge(parentRoot, childRoot)) {
                        isCycleMergeRequired = false;
                    } else {
                        LongOpenHashSet parentAncestors = ColorGraph.scratchParentAncestors;
                        parentAncestors.clear();
                        ColorGraph.collectAncestors(parentRoot, parentAncestors);
                        isCycleMergeRequired = parentAncestors.contains(childRoot);
                    }
                    if (isCycleMergeRequired) {
                        ColorGraph.mergeColors(parentRoot, childRoot);
                    }
                    else {
                        ColorGraph.addEdge(parentRoot, childRoot);
                        propagateActiveMembership(parentRoot, childRoot, true);
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
        long root = ColorGraph.resolve(color);
        columnsByRoot.computeIfAbsent(root, k -> new LongOpenHashSet()).add(colKey);
        // 디버그 모드도 dirtyColumns 경로를 탄다 - front에 직접 그리면 여러 틱에 걸친 back 리빌드 중
        // 커서가 이미 지나간 영역에 칠해진 픽셀이 mirrorToBack을 못 받아 스왑 후 사라진다
        dirtyColumns.add(colKey);
    }

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
        activeSetCache.reset();
        searchActiveSet.clear();
        searchActiveSetCache.reset();
        searchActiveSetTouchedByMerge = false;
        pendingActiveColorCandidate = NO_ID;
        pendingActiveColorStreak = 0;
        inactiveRevivalScratch.clear();
        inactiveRevivalDrain.reset();
        revivedProcessDrain.reset();
        BlockSearch.invalidateChunkCache();
    }
}