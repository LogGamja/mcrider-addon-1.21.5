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

// 플로드필 탐색 엔진 및 방문 셀/컬럼 저장소.
// 탐색 상태(cellColor, visitedColumns, dirtyColumns)와 표시 필터(activeColor, activeSet)를 관리.
final class FrontierSearch {
    private FrontierSearch() {}

    // 탐색 상태
    static Long2LongOpenHashMap cellColor = new Long2LongOpenHashMap();
    static Long2ObjectOpenHashMap<IntOpenHashSet> visitedColumns = new Long2ObjectOpenHashMap<>();
    static LongOpenHashSet dirtyColumns = new LongOpenHashSet();
    static Long2ObjectOpenHashMap<LongOpenHashSet> columnsByRoot = new Long2ObjectOpenHashMap<>();

    static long activeColor = NO_ID;

    static { cellColor.defaultReturnValue(NO_ID); }

    static final int STAGING_BUDGET_PER_TICK = 1024;
    static final long STAGING_TIME_BUDGET_NANOS = 1_000_000L;

    // 히스테리시스: 경계에서 흔들리는 색 전환 방지
    static final int ACTIVE_COLOR_SWITCH_STREAK = 5;

    // activeSet과 searchActiveSet 재계산 상태 캐싱
    private static final class SubtreeCache {
        long snapshotRoot = NO_ID;
        long version = -1;
        final LongOpenHashSet prevForDiff = new LongOpenHashSet();

        // 캐시 유효 시 false 반환. 재계산 필요 시 prevForDiff에 이전 상태 저장 후 out 갱신
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

        // 기존 계산 결과가 있으면 BFS 스킵하고 복사
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

    static final LongOpenHashSet activeSet = new LongOpenHashSet();
    private static final SubtreeCache activeSetCache = new SubtreeCache();

    static final LongOpenHashSet searchActiveSet = new LongOpenHashSet();
    // 탐색 필터는 표시용과 분리해 deadlock 방지
    private static final SubtreeCache searchActiveSetCache = new SubtreeCache();

    // 자손 없는 색 병합 시 diff에서 못 잡는 변화를 신호로 표시
    private static boolean searchActiveSetTouchedByMerge = false;
    static void noteMergeSurvivor(long survivor) {
        if (searchActiveSet.contains(survivor)) {
            searchActiveSetTouchedByMerge = true;
        }
    }

    // rebuildActiveSet/rebuildSearchActiveSet이 공유하는 BFS 큐(매 틱 new 방지, 재진입 없어 안전)
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

    // 반환값은 searchActiveSet의 실제 내용이 바뀌었는지 또는 revive가 필요한 병합이 있었는지.
    // diff만으로는 못 잡는 경우는 ColorGraph.absorbInto가 noteMergeSurvivor로 세워둔
    // searchActiveSetTouchedByMerge로 보완한다.
    private static boolean rebuildSearchActiveSet(long liveRoot) {
        // liveRoot가 activeColor와 같은 정상 상태라면 rebuildActiveSet이 같은 서브트리를 방금
        // 계산해 뒀으므로 그 결과를 복사해서 동일한 BFS를 두 번 도는 걸 피한다. 이 복사가 안전한
        // 이유는 propagateActiveMembership이 activeSet에 직접 넣는 경우가 항상 버전 증가를
        // 동반하거나 이미 있는 엣지에 대한 아무 동작도 하지 않는 경우뿐이라서, 버전이 같다면
        // activeSet은 언제나 activeColor의 서브트리와 정확히 일치하기 때문이다.
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

    // 예산 초과 시 다음 틱에 이어가는 재개 드레인 패턴
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

    // 비활성 색 되살리기 스크래치 (예산 분산 처리)
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

    // 복구된 exile 셀 처리 재개 상태 (이미 로딩 확인됨)
    private static final ResumableDrain revivedProcessDrain = new ResumableDrain();

    // exiledByChunk엔 CHUNK_NOT_LOADED(청크 로딩 필요)와 OUT_OF_RANGE(플레이어 접근 필요)가
    // 섞여 있어서 트리거는 둘 다 봐야 한다. 위치는 청크 단위로만 비교해도 충분한데, maxRange에
    // 여유가 있어 청크 단위 오차 정도는 문제가 되지 않기 때문이다.
    private static boolean chunkLoadedSinceLastDrain = false;
    private static int lastDrainChunkX = Integer.MIN_VALUE, lastDrainChunkZ = Integer.MIN_VALUE;

    // 이전 drainExiledWithinRange 호출이 deadline에 걸려 exiledByChunk를 다 못 훑었으면 true.
    // chunkMoved나 chunkLoadedSinceLastDrain 같은 새 트리거가 없어도 다음 틱에 스캔을 재시도해야 한다.
    private static boolean exiledScanTimedOut = false;

    // ClientChunkEvents.CHUNK_LOAD 훅(MCRiderMinimap)에서 호출
    // 실제 드레인은 다음 floodFillWithVertical 호출(다음 틱)에서 이 신호를 소비해 처리한다
    static void notifyChunkLoaded() {
        chunkLoadedSinceLastDrain = true;
    }

    private static void processRevivedExiledCells(int sx, int sz, int maxRange, boolean containToActive, long deadline) {
        int chunkX = sx >> 4, chunkZ = sz >> 4;
        boolean chunkMoved = chunkX != lastDrainChunkX || chunkZ != lastDrainChunkZ;
        boolean trigger = chunkMoved || chunkLoadedSinceLastDrain || exiledScanTimedOut;
        if (trigger) {
            // beginIfIdle 내부의 pendingTrigger가 이 사실을 래치해두므로(진행 중이라 이번 틱에
            // 못 써도 다음 유휴 틱에 소비됨) 여기서 바로 리셋해도 신호가 유실되지 않는다
            lastDrainChunkX = chunkX;
            lastDrainChunkZ = chunkZ;
            chunkLoadedSinceLastDrain = false;
        }
        revivedProcessDrain.beginIfIdle(trigger, FrontierQueue.revivedScratch,
                () -> exiledScanTimedOut = FrontierQueue.drainExiledWithinRange(sx, sz, maxRange, deadline));
        revivedProcessDrain.drain(FrontierQueue.revivedScratch, sx, sz, maxRange, containToActive, deadline);
    }

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

    static void markColumnsDirtyForRoot(long root) {
        LongOpenHashSet cols = columnsByRoot.get(root);
        if (cols != null) dirtyColumns.addAll(cols);
    }

    static void markAllColumnsDirty() {
        dirtyColumns.addAll(visitedColumns.keySet());
    }

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

    // Renderer가 dirty 컬럼 반영 완료 시 호출
    static void clearDirtyColumns() {
        dirtyColumns.clear();
    }

    static long resolvedRootAt(int x, int y, int z) {
        long id = cellColor.get(BlockPos.asLong(x, y, z));
        return id == NO_ID ? NO_ID : ColorGraph.resolve(id);
    }

    private static long pendingActiveColorCandidate = NO_ID;
    private static int pendingActiveColorStreak = 0;

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

    // anchor가 실제로 칠해진 셀일 때만 플레이어 셀로 인정한다
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
            // 비활성 색은 별도 보류 (park/revive 반복 방지)
            FrontierQueue.parkInactiveColor(cell);
            return NO_ID;
        }
        return curColor;
    }

    static void floodFillWithVertical(BlockPos start, int maxRange, int cellBudget) {
        var world = MCRiderMinimap.client.world;
        if (world == null) return;

        if (!BlockSearch.isChunkLoadedAt(start.getX(), start.getZ())) return;

        int sx = start.getX(), sz = start.getZ();
        long anchorCell = findAnchorCell(sx, start.getY(), sz, world.getBottomY());
        if (anchorCell == NO_ID) return;
        int sy = BlockPos.unpackLongY(anchorCell);

        if (cellColor.get(anchorCell) == NO_ID && BlockSearch.isStandable(sx, sy, sz, false)) {
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

        // 앵커 풀림 틱에는 이전 필터 유지 (파킹 셀 낭비 방지)
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
                        int nx = cx + d[0];
                        int nz = cz + d[1];

                        if (!BlockSearch.isChunkLoadedAt(nx, nz)) {
                            // 이웃 청크 키로 park해야 그 청크 로딩이 revive 조건이 된다. 자기 청크로
                            // 걸면 이미 로딩된 상태라 매 틱 즉시 되살렸다가 다시 park하는 핑퐁이 생긴다.
                            if (!parkedSelf) {
                                FrontierQueue.park(curPacked, nx, nz, FrontierQueue.ParkReason.CHUNK_NOT_LOADED);
                                parkedSelf = true;
                            }
                            continue;
                        }

                        boolean baseIsAir = BlockSearch.isAirAt(nx, cy, nz);
                        boolean baseIsWall = BlockSearch.isWallGivenAir(baseIsAir, nx, cy, nz);
                        if (baseIsWall) continue;

                        int ty = BlockSearch.resolveTargetY(nx, cy, nz, baseIsAir, baseIsWall, hasBlockAt2Meter, world.getBottomY());
                        if (ty == Integer.MIN_VALUE) continue;

                        // 가짜 블록은 narrow 경로를 걸러내는 설정이 켜져 있을 때만 동작한다 — 꺼져있으면
                        // narrow 체크가 안전망 역할을 못 해서, 실제로 이어지는 좁은 통로를 가짜 블록으로
                        // 덮어써 지워버릴 수 있다.
                        if (MCRiderMinimap.EXCLUDE_NARROW_PATHS && cy - ty == 1) {
                            // 측면 칸이 미로딩이면 EmptyChunk의 void_air를 "막힘"으로 오인해 가짜 블록을
                            // 잘못 놓을 수 있다(한번 놓이면 영구). narrow 체크와 동일하게 그 청크가 로딩될
                            // 때까지 이 셀을 보류하고, 로딩된 뒤 확정된 상태로 재판정한다.
                            boolean lateralUnloaded = false;
                            for (int[] pd : BlockSearch.DIRECTIONS) {
                                if (!BlockSearch.isChunkLoadedAt(nx + pd[0], nz + pd[1])) {
                                    if (!parkedSelf) {
                                        FrontierQueue.park(curPacked, nx + pd[0], nz + pd[1], FrontierQueue.ParkReason.CHUNK_NOT_LOADED);
                                        parkedSelf = true;
                                    }
                                    lateralUnloaded = true;
                                    break;
                                }
                            }
                            if (lateralUnloaded) continue;

                            boolean frontBlocked = !BlockSearch.isAirAt(nx + d[0], ty, nz + d[1]);
                            boolean backBlocked = !BlockSearch.isAirAt(nx - d[0], ty, nz - d[1]);
                            int blockedSides = 0;
                            for (int[] pd : BlockSearch.DIRECTIONS) {
                                if (!BlockSearch.isAirAt(nx + pd[0], ty, nz + pd[1])) blockedSides++;
                            }
                            // 주변 상하좌우 3면 이상이 (오르든 아니든) 막혀있거나, 진행 방향 앞뒤만 막히고
                            // 좌우는 열려있으면(진행축과 수직인 좁은 도랑) 실수로 판 듯한 1칸 구덩이로 보고
                            // 가상 블록으로 메운다. 밟고 지나가는 평평한 바닥으로 재계산된다.
                            boolean frontBackOnly = frontBlocked && backBlocked && blockedSides == 2;
                            if (blockedSides >= 3 || frontBackOnly) {
                                BlockSearch.addFakeBlock(nx, ty, nz);
                                ty = BlockSearch.resolveTargetY(nx, cy, nz, baseIsAir, baseIsWall, hasBlockAt2Meter, world.getBottomY());
                                if (ty == Integer.MIN_VALUE) continue;
                            }
                        }

                        if (MCRiderMinimap.EXCLUDE_NARROW_PATHS) {
                            long narrow = BlockSearch.isNarrowPassageInRange(nx, cy, ty, nz, d[0], d[1]);
                            if (narrow != BlockSearch.PASSAGE_OPEN && narrow != BlockSearch.PASSAGE_NARROW) {
                                // 좁은 길인지 확정할 수 없는 게 아니라 이웃 청크가 아직 안 로딩된 것
                                // 벽으로 오인해 탐색을 막는 대신 narrow에 패킹된 그 청크의 월드 좌표로 로딩될 때까지 이 셀을 보류한다.
                                if (!parkedSelf) {
                                    int unknownX = (int) (narrow >> 32);
                                    int unknownZ = (int) narrow;
                                    FrontierQueue.park(curPacked, unknownX, unknownZ, FrontierQueue.ParkReason.CHUNK_NOT_LOADED);
                                    parkedSelf = true;
                                }
                                continue;
                            }
                            if (narrow == BlockSearch.PASSAGE_NARROW) continue;
                        }

                        boolean twoWay = BlockSearch.canMoveBetween(nx, ty, nz, cx, cy, cz, world.getBottomY());
                        handleReach(cx, cy, cz, curColor, nx, ty, nz, twoWay, sx, sz, maxRange);
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
        dirtyColumns.add(colKey); // 디버그 모드도 dirty 경로 필요 (back 리빌드 중 동기화)
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
}