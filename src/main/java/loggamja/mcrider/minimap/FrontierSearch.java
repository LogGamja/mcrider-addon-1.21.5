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

// 플로드필 탐색 엔진. 탐색 상태(cellColor 등)와 표시 필터(activeColor, activeSet)를 관리한다.
final class FrontierSearch {
    private FrontierSearch() {}

    private static final Long2LongOpenHashMap cellColor = new Long2LongOpenHashMap();
    static Long2ObjectOpenHashMap<IntOpenHashSet> visitedColumns = new Long2ObjectOpenHashMap<>();
    static LongOpenHashSet dirtyColumns = new LongOpenHashSet();
    static Long2ObjectOpenHashMap<LongOpenHashSet> columnsByRoot = new Long2ObjectOpenHashMap<>();
    static final LongOpenHashSet activeSet = new LongOpenHashSet();

    static long activeColor = NO_ID;

    static { cellColor.defaultReturnValue(NO_ID); }

    static final int STAGING_BUDGET_PER_TICK = 1024;
    private static final long STAGING_TIME_BUDGET_NANOS = 1_000_000L;

    // 히스테리시스: 경계에서 흔들리는 색 전환 방지
    private static final int ACTIVE_COLOR_SWITCH_STREAK = 5;

    // ClientChunkEvents.CHUNK_LOAD 훅에서 호출. 실제 드레인은 다음 floodFillWithVertical에서 처리
    static void notifyChunkLoaded() {
        chunkLoadedSinceLastDrain = true;
    }

    static void markColumnsDirtyForRoot(long root) {
        LongOpenHashSet cols = columnsByRoot.get(root);
        if (cols != null) dirtyColumns.addAll(cols);
    }

    static void markAllColumnsDirty() {
        dirtyColumns.addAll(visitedColumns.keySet());
    }

    // ColorGraph.absorbInto에서 호출. 자손 없는 색 병합 시 diff로 못 잡는 변화를 신호로 표시
    static void noteMergeSurvivor(long survivor) {
        if (searchActiveSet.contains(survivor)) {
            searchActiveSetTouchedByMerge = true;
        }
    }

    // Renderer가 dirty 컬럼 반영을 끝내면 호출
    static void clearDirtyColumns() {
        dirtyColumns.clear();
    }

    static long resolvedRootAt(int x, int y, int z) {
        long id = cellColor.get(BlockPos.asLong(x, y, z));
        return id == NO_ID ? NO_ID : ColorGraph.resolve(id);
    }

    static long packColumn(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
    static int unpackColumnX(long key) { return (int) (key >> 32); }
    static int unpackColumnZ(long key) { return (int) key; }

    static void rebuildActiveSet() {
        // 변경된 루트의 컬럼만 dirty 표시 (전체 재도색 방지)
        boolean debug = MCRiderMinimap.isDebugColors();
        if (!activeSetCache.recompute(activeColor, activeSet, !debug)) return;

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

    static void floodFillWithVertical(BlockPos start, int maxRange, int cellBudget) {
        var world = MCRiderMinimap.client.world;
        if (world == null) return;

        if (!BlockSearch.isChunkLoadedAt(start.getX(), start.getZ())) return;

        int sx = start.getX(), sz = start.getZ();
        long anchorCell = findAnchorCell(sx, start.getY(), sz, world.getBottomY());
        if (anchorCell == NO_ID) return;
        int sy = BlockPos.unpackLongY(anchorCell);

        if (cellColor.get(anchorCell) == NO_ID && BlockSearch.isStandableAt(sx, sy, sz, false)) {
            boolean seedIsNarrow = false;
            if (MCRiderMinimap.EXCLUDE_NARROW_PATHS) {
                // 미로딩 청크는 NARROW로 취급해 시딩 보류 (다음 틱 재시도)
                seedIsNarrow = BlockSearch.isNarrowPassage(sx, sy, sz, 1, 0) != BlockSearch.PASSAGE_OPEN
                        || BlockSearch.isNarrowPassage(sx, sy, sz, 0, 1) != BlockSearch.PASSAGE_OPEN;
            }
            if (!seedIsNarrow) {
                long c = ColorGraph.newColor(NO_ID);
                paintCell(sx, sy, sz, c);
                FrontierQueue.push(anchorCell, sx, sz);
            }
        }
        long playerCell = resolvePlayerCellFromAnchor(anchorCell);
        updateActiveColorFromCell(playerCell);
        rebuildActiveSet();

        // 앵커 미해결 틱엔 색이 불확실하므로 필터를 끈다. 불확실한 필터로 park했다가
        // 다음 틱에 되살리는 낭비(핑퐁) 방지. searchActiveSet은 재계산 없이 그대로 둔다.
        final boolean containToActive = playerCell != NO_ID;
        boolean searchActiveSetChanged = containToActive
                && rebuildSearchActiveSet(ColorGraph.resolve(cellColor.get(playerCell)));

        final long deadline = System.nanoTime() + STAGING_TIME_BUDGET_NANOS;

        reviveInactiveColorParked(sx, sz, maxRange, containToActive, searchActiveSetChanged, deadline);
        processRevivedExiledCells(sx, sz, maxRange, containToActive, deadline);

        boolean stop = false;

        while (!stop && !FrontierQueue.frontierByChunk.isEmpty()) {
            int n = FrontierQueue.sortChunkKeysByDistance(sx, sz);

            for (int ci = 0; ci < n; ci++) {
                long chunkKey = FrontierQueue.sortSnap[(int) (FrontierQueue.sortPacked[ci] & 0xFFFFFFFFL)];
                var bucket = FrontierQueue.frontierByChunk.get(chunkKey);
                if (bucket == null) continue;

                while (!bucket.isEmpty()) {
                    if (FrontierQueue.deadlineReached(deadline)) {
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
                        parkedSelf = processNeighbor(curPacked, cx, cy, cz, curColor, hasBlockAt2Meter,
                                d, parkedSelf, sx, sz, maxRange, world.getBottomY());
                    }

                    if (--cellBudget <= 0) {
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

    static void reset() {
        cellColor.clear();
        visitedColumns.clear();
        dirtyColumns.clear();
        columnsByRoot.clear();
        BlockSearch.clearFakeBlocks();
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
        chunkLoadedSinceLastDrain = false;
        exiledScanTimedOut = false;
        lastDrainChunkX = Integer.MIN_VALUE;
        lastDrainChunkZ = Integer.MIN_VALUE;
        BlockSearch.invalidateChunkCache();
    }

    // activeSet/searchActiveSet 서브트리 재계산 캐시
    private static final class SubtreeCache {
        long snapshotRoot = NO_ID;
        long version = -1;
        final LongOpenHashSet prevForDiff = new LongOpenHashSet();

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

    private static final SubtreeCache activeSetCache = new SubtreeCache();

    private static final LongOpenHashSet searchActiveSet = new LongOpenHashSet();
    // 탐색 필터는 표시용과 분리해 deadlock 방지
    private static final SubtreeCache searchActiveSetCache = new SubtreeCache();

    private static boolean searchActiveSetTouchedByMerge = false;

    // rebuildActiveSet/rebuildSearchActiveSet이 공유하는 BFS 큐 (매 틱 new 방지, 재진입 없어 안전)
    private static final LongArrayFIFOQueue subtreeQueue = new LongArrayFIFOQueue();

    // out은 호출부가 clear
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

    private static boolean longSetsEqual(LongOpenHashSet a, LongOpenHashSet b) {
        if (a.size() != b.size()) return false;
        LongIterator it = a.iterator();
        while (it.hasNext()) {
            if (!b.contains(it.nextLong())) return false;
        }
        return true;
    }

    // 반환값은 searchActiveSet 내용이 바뀌었는지, 또는 revive가 필요한 병합이 있었는지다.
    // diff로 못 잡는 경우는 searchActiveSetTouchedByMerge로 보완한다.
    private static boolean rebuildSearchActiveSet(long liveRoot) {
        // liveRoot==activeColor면 rebuildActiveSet이 같은 서브트리를 방금 계산해 뒀으므로 복사해서 재사용한다.
        // 버전이 같은 한 activeSet은 항상 activeColor의 서브트리와 정확히 일치한다.
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

    // 예산 초과 시 다음 틱에 이어가는 재개 드레인
    private static final class ResumableDrain {
        int index = 0;
        boolean inProgress = false;
        private boolean pendingTrigger = false;

        void beginIfIdle(boolean trigger, LongArrayList scratch, Runnable fill) {
            pendingTrigger |= trigger;
            if (pendingTrigger && !inProgress) {
                pendingTrigger = false;
                fill.run();
                index = 0;
                inProgress = !scratch.isEmpty();
            }
        }

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
                if ((++sinceTimeCheck & 0xFF) == 0 && FrontierQueue.deadlineReached(deadline)) return; // 다음 틱에 이어서
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

    private static final LongArrayList inactiveRevivalScratch = new LongArrayList();
    private static final ResumableDrain inactiveRevivalDrain = new ResumableDrain();

    private static void reviveInactiveColorParked(int sx, int sz, int maxRange, boolean containToActive,
                                                  boolean searchActiveSetChanged, long deadline) {
        inactiveRevivalDrain.beginIfIdle(searchActiveSetChanged, inactiveRevivalScratch, () -> {
            inactiveRevivalScratch.clear();
            FrontierQueue.reviveInactiveColorParked(inactiveRevivalScratch);
        });
        inactiveRevivalDrain.drain(inactiveRevivalScratch, sx, sz, maxRange, containToActive, deadline);
    }

    private static final ResumableDrain revivedProcessDrain = new ResumableDrain();

    // exiledByChunk엔 CHUNK_NOT_LOADED와 OUT_OF_RANGE가 섞여 있어 트리거는 둘 다 봐야 한다.
    // 위치는 청크 단위로만 비교해도 maxRange 여유 안에서 충분하다.
    private static boolean chunkLoadedSinceLastDrain = false;
    private static int lastDrainChunkX = Integer.MIN_VALUE, lastDrainChunkZ = Integer.MIN_VALUE;

    // 새 트리거 없어도 다음 틱에 재시도해야 한다
    private static boolean exiledScanTimedOut = false;
    
    // beginIfIdle의 fill 콜백 비캡처 람다화
    private static int revivedDrainSx, revivedDrainSz, revivedDrainMaxRange;
    private static long revivedDrainDeadline;

    private static void fillRevivedDrain() {
        exiledScanTimedOut = FrontierQueue.drainExiledWithinRange(
            revivedDrainSx, revivedDrainSz, revivedDrainMaxRange, revivedDrainDeadline);
    }

    private static void processRevivedExiledCells(int sx, int sz, int maxRange, boolean containToActive, long deadline) {
        int chunkX = sx >> 4, chunkZ = sz >> 4;
        boolean chunkMoved = chunkX != lastDrainChunkX || chunkZ != lastDrainChunkZ;
        boolean trigger = chunkMoved || chunkLoadedSinceLastDrain || exiledScanTimedOut;
        if (trigger) {
            // pendingTrigger가 이 신호를 래치해두므로(다음 유휴 틱에 소비됨) 바로 리셋해도 안전하다
            lastDrainChunkX = chunkX;
            lastDrainChunkZ = chunkZ;
            chunkLoadedSinceLastDrain = false;
        }
        revivedDrainSx = sx;
        revivedDrainSz = sz;
        revivedDrainMaxRange = maxRange;
        revivedDrainDeadline = deadline;
        revivedProcessDrain.beginIfIdle(trigger, FrontierQueue.revivedScratch, FrontierSearch::fillRevivedDrain);
        revivedProcessDrain.drain(FrontierQueue.revivedScratch, sx, sz, maxRange, containToActive, deadline);
    }

    // activeColor 히스테리시스
    private static long pendingActiveColorCandidate = NO_ID;
    private static int pendingActiveColorStreak = 0;

    private static void updateActiveColorFromCell(long cell) {
        if (cell == NO_ID) return;
        long id = cellColor.get(cell);
        if (id == NO_ID) return;
        long candidate = ColorGraph.resolve(id);

        if (candidate == activeColor) {
            pendingActiveColorCandidate = NO_ID;
            pendingActiveColorStreak = 0;
            return;
        }

        // 히스테리시스는 이미 정해진 색 사이의 흔들림 방지용이라 최초 진입(NO_ID)엔 적용하지 않는다.
        if (activeColor == NO_ID) {
            activeColor = candidate;
            pendingActiveColorCandidate = NO_ID;
            pendingActiveColorStreak = 0;
            return;
        }

        if (candidate == pendingActiveColorCandidate) {
            pendingActiveColorStreak++;
        } else {
            // 스트릭 진행 중 pendingActiveColorCandidate가 다른 색에 흡수(merge)됐을 수 있으므로
            // 곧바로 새 후보로 리셋하기 전에 재해석해서 같은 논리적 색이면 스트릭을 이어간다.
            long resolvedPending = pendingActiveColorCandidate == NO_ID
                    ? NO_ID
                    : ColorGraph.resolve(pendingActiveColorCandidate);
            if (candidate == resolvedPending) {
                pendingActiveColorCandidate = resolvedPending;
                pendingActiveColorStreak++;
            } else {
                pendingActiveColorCandidate = candidate;
                pendingActiveColorStreak = 1;
            }
        }

        if (pendingActiveColorStreak >= ACTIVE_COLOR_SWITCH_STREAK) {
            activeColor = candidate;
            pendingActiveColorCandidate = NO_ID;
            pendingActiveColorStreak = 0;
        }
    }

    private static long resolvePlayerCellFromAnchor(long anchor) {
        if (anchor == NO_ID) return NO_ID;
        if (cellColor.get(anchor) == NO_ID) return NO_ID;
        return anchor;
    }

    private static long findAnchorCell(int x, int y, int z, int bottomY) {
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

    // 프론티어 처리 / 셀 페인팅
    private static long activeColorOrPark(long cell, boolean containToActive) {
        long curColor = cellColor.get(cell);
        if (curColor == NO_ID) return NO_ID;
        if (containToActive && !searchActiveSet.contains(ColorGraph.resolve(curColor))) {
            // park/revive 반복(핑퐁) 방지
            FrontierQueue.parkInactiveColor(cell);
            return NO_ID;
        }
        return curColor;
    }

    // 전제: 이 호출 전에 addEdge가 이미 colorGraphVersion을 올려뒀어야 한다.
    // 그래야 activeSet에 대한 아래의 직접 수정이 SubtreeCache의 버전 캐시와 계속 일치한다.
    private static void propagateActiveMembership(long parentRoot, long childRoot, boolean markDirty) {
        if (activeSet.contains(parentRoot)) {
            if (activeSet.add(childRoot) && markDirty) markColumnsDirtyForRoot(childRoot);
        }
        if (searchActiveSet.contains(parentRoot)) {
            boolean newlyAdded = searchActiveSet.add(childRoot);
            // 기존 색이 새로 편입되면 파킹 셀 되살리기 신호 필요
            if (markDirty && newlyAdded) searchActiveSetTouchedByMerge = true;
        }
    }

    // 방향 d로 갈 수 있는지 판정해 handleReach로 넘긴다. 반환값은 갱신된 parkedSelf다.
    // 이 셀은 한 틱에 한 번만 park해야 해서(핑퐁 방지) 방향 루프 전체에 걸쳐 이어받아야 한다.
    private static boolean processNeighbor(long curPacked, int cx, int cy, int cz, long curColor,
                                            boolean hasBlockAt2Meter, int[] d, boolean parkedSelf,
                                            int sx, int sz, int maxRange, int bottomY) {
        int nx = cx + d[0];
        int nz = cz + d[1];

        if (!BlockSearch.isChunkLoadedAt(nx, nz)) {
            // 이웃 청크 키로 park해야 그 청크 로딩이 revive 조건이 된다.
            // 자기 청크로 걸면 이미 로딩된 상태라 매 틱 즉시 되살렸다가 다시 park하는 핑퐁이 생긴다.
            if (!parkedSelf) {
                FrontierQueue.park(curPacked, nx, nz, FrontierQueue.ParkReason.CHUNK_NOT_LOADED);
                parkedSelf = true;
            }
            return parkedSelf;
        }

        boolean baseIsAir = BlockSearch.isAirAt(nx, cy, nz);
        boolean baseIsWall = BlockSearch.isWallIfNotAir(baseIsAir, nx, cy, nz);
        if (baseIsWall) return parkedSelf;

        int ty = BlockSearch.resolveTargetY(nx, cy, nz, baseIsAir, baseIsWall, hasBlockAt2Meter, bottomY);
        if (ty == Integer.MIN_VALUE) return parkedSelf;

        // cy-ty==1은 resolveTargetY가 정확히 1칸만 하강했다는 뜻이다(불변식: 그 아래엔 항상 블록이 있음)
        // narrow 체크가 꺼지면 안전망도 같이 빠지므로 이 블록도 narrow 설정을 따른다.
        if (MCRiderMinimap.EXCLUDE_NARROW_PATHS && cy - ty == 1) {
            long unloaded = BlockSearch.firstUnloadedLateralChunk(nx, nz);
            if (unloaded != BlockSearch.ALL_LATERAL_LOADED) {
                // narrow 체크와 동일하게, 판정에 필요한 청크가 로딩될 때까지 이 셀을 보류한다.
                if (!parkedSelf) {
                    int ux = (int) (unloaded >> 32), uz = (int) unloaded;
                    FrontierQueue.park(curPacked, ux, uz, FrontierQueue.ParkReason.CHUNK_NOT_LOADED);
                    parkedSelf = true;
                }
                return parkedSelf;
            }

            if (BlockSearch.isIsolatedPit(nx, ty, nz, d[0], d[1])) {
                BlockSearch.addFakeBlock(nx, ty, nz);
                eraseCellIfPainted(nx, ty, nz);
                // resolveTargetY를 다시 돌리면 ty가 cy로 보정되고
                // 이후 흐름(narrow 체크, canMoveBetween, handleReach)이 그대로 이어받아 가짜 블록 위에 셀을 놓는다.
                ty = BlockSearch.resolveTargetY(nx, cy, nz, baseIsAir, baseIsWall, hasBlockAt2Meter, bottomY);
                if (ty == Integer.MIN_VALUE) return parkedSelf;
            }
        }

        if (MCRiderMinimap.EXCLUDE_NARROW_PATHS) {
            long narrow = BlockSearch.isNarrowPassageInRange(nx, cy, ty, nz, d[0], d[1]);
            if (!BlockSearch.isPassageResultResolved(narrow)) {
                // "좁음 확정"이 아니라 "이웃 청크 미로딩"일 수 있어 곧장 막지 않고, 그 청크가 로딩될 때까지 보류한다.
                if (!parkedSelf) {
                    int unknownX = (int) (narrow >> 32);
                    int unknownZ = (int) narrow;
                    FrontierQueue.park(curPacked, unknownX, unknownZ, FrontierQueue.ParkReason.CHUNK_NOT_LOADED);
                    parkedSelf = true;
                }
                return parkedSelf;
            }
            if (narrow == BlockSearch.PASSAGE_NARROW) return parkedSelf;
        }

        boolean twoWay = BlockSearch.canMoveBetween(nx, ty, nz, cx, cy, cz, bottomY);
        handleReach(cx, cy, cz, curColor, nx, ty, nz, twoWay, sx, sz, maxRange);
        return parkedSelf;
    }

    private static void handleReach(int cx, int cy, int cz, long curColor, int tx, int ty, int tz, boolean twoWay,
                            int sx, int sz, int maxRange) {
        long targetCell = BlockPos.asLong(tx, ty, tz);
        long existing = cellColor.get(targetCell);
        if (existing == NO_ID) {
            boolean headAlreadyChecked = (ty >= cy);
            if (!BlockSearch.isStandableAt(tx, ty, tz, headAlreadyChecked)) return;
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

    private static void paintCell(int x, int y, int z, long color) {
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
        dirtyColumns.add(colKey);
    }
    private static void eraseCellIfPainted(int x, int y, int z) {
        long cell = BlockPos.asLong(x, y, z);
        long removedColor = cellColor.remove(cell);
        if (removedColor == NO_ID) return;
        long colKey = packColumn(x, z);
        IntOpenHashSet ys = visitedColumns.get(colKey);
        if (ys != null) {
            ys.remove(y);
            if (ys.isEmpty()) {
                visitedColumns.remove(colKey);
                // columnsByRoot에서 죽은 colKey 걷어내기
                long root = ColorGraph.resolve(removedColor);
                LongOpenHashSet cols = columnsByRoot.get(root);
                if (cols != null) {
                    cols.remove(colKey);
                    if (cols.isEmpty()) columnsByRoot.remove(root);
                }
            }
        }
        dirtyColumns.add(colKey);
    }
}
