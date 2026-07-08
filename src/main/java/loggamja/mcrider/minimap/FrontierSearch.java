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

// 플로드필 탐색 엔진 + 방문 셀/컬럼 저장소
// 지금까지 어디를 탐색했고 어떤 색인지(cellColor/visitedColumns/columnsByRoot/dirtyColumns)와
// 지금 뭘 화면에 보여줄지(activeColor/activeSet/searchActiveSet)가 전부 여기 있다
// ColorGraph와 MinimapRenderer 양쪽이 참조하는 미니맵의 핵심 엔진
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

    // "활성 색 + 그 자손"의 resolve된 집합(표시용, 비디버그 모드). 틱당 1회 계산해 캐시
    static final LongOpenHashSet activeSet = new LongOpenHashSet();
    private static long activeSetVersion = -1;
    private static long activeSetSnapshotColor = NO_ID;
    // rebuildActiveSet 재계산 시 "직전 activeSet"을 담아두는 diff용 버퍼
    private static final LongOpenHashSet prevActiveSetForDiff = new LongOpenHashSet();

    // 탐색 필터용 activeSet. 표시용 히스테리시스와 분리해 deadlock 방지
    static final LongOpenHashSet searchActiveSet = new LongOpenHashSet();
    private static long searchActiveSetSnapshotRoot = NO_ID;
    private static long searchActiveSetVersion = -1;
    // rebuildSearchActiveSet 재계산 시 "직전 searchActiveSet"과 diff하기 위한 버퍼.
    // colorGraphVersion은 liveRoot와 무관한 곳의 엣지/병합에도 오르는 전역 카운터라,
    // 버전만 보고 재검사 여부를 판단하면 서브트리와 무관한 변경에도 매번 반응하게 된다.
    // 재계산된 집합의 실제 내용이 바뀌었을 때만 true를 돌려줘야 inactiveColorParked 전수
    // 재검사가 진짜로 필요한 경우로 좁혀진다.
    private static final LongOpenHashSet prevSearchActiveSetForDiff = new LongOpenHashSet();

    // ColorGraph.absorbInto가 searchActiveSet 소속 survivor로 병합을 수행했을 때 켜진다.
    // 자손 없는 loser가 흡수되면 collectColorSubtree 결과 "내용"은 병합 전후로 동일해
    // diff가 변경을 못 잡아내지만, loser 색으로 파킹된 셀들은 resolve()를 거쳐 이제 활성
    // 트리에 속하므로 revive가 필요하다 - 그 갭을 diff와 별도로 메우는 신호
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

    // 반환값: searchActiveSet의 실제 내용이 바뀌었는지, 또는 그와 무관하게 revive가 필요한 병합이
    // 있었는지. colorGraphVersion은 liveRoot와 무관한 곳의 변경에도 오르므로, 재계산 자체가 아니라
    // 재계산 결과가 이전과 달라졌을 때만 true를 돌려준다 - inactiveColorParked 전수 재검사는
    // 그럴 가치가 있을 때만 트리거되어야 한다.
    //
    // diff만으로는 못 잡는 경우(자식 없는 loser가 이미 활성 서브트리인 survivor에 흡수되는 경우)는
    // ColorGraph.absorbInto가 noteMergeSurvivor로 세워둔 searchActiveSetTouchedByMerge로 보완한다.
    private static boolean rebuildSearchActiveSet(long liveRoot) {
        if (searchActiveSetSnapshotRoot == liveRoot && searchActiveSetVersion == ColorGraph.colorGraphVersion) {
            return false;
        }
        prevSearchActiveSetForDiff.clear();
        prevSearchActiveSetForDiff.addAll(searchActiveSet);

        searchActiveSet.clear();
        collectColorSubtree(liveRoot, searchActiveSet);
        searchActiveSetSnapshotRoot = liveRoot;
        searchActiveSetVersion = ColorGraph.colorGraphVersion;

        boolean contentChanged = !longSetsEqual(searchActiveSet, prevSearchActiveSetForDiff);
        boolean mergeTouchedActive = searchActiveSetTouchedByMerge;
        searchActiveSetTouchedByMerge = false;
        return contentChanged || mergeTouchedActive;
    }

    // inactiveColorParked 되살리기 전용 스크래치(매 틱 new 방지). 세션 내내 누적될 수 있는 크기라
    // 한 틱에 몰아서 처리하면 안 된다 — inactiveRevivalIndex/InProgress로 진행 상태를 들고 있다가
    // 예산이 부족하면 다음 틱에 이어서 처리한다.
    private static final LongArrayList inactiveRevivalScratch = new LongArrayList();
    private static int inactiveRevivalIndex = 0;
    private static boolean inactiveRevivalInProgress = false;

    // searchActiveSet이 바뀐 틱에 새로 드레인을 시작하거나(진행 중이 아닐 때만), 이미 진행 중인 드레인을
    // 이어간다. 색이 안 맞아 보류됐던 셀들을 activeColorOrPark로 재검사해, 이제 활성 트리에 속하면
    // 프론티어로, 아니면 다시 보류(inactiveColorParked)로 되돌린다. 다른 단계와 같은 deadline을 공유해
    // 이 단계가 이번 틱의 시간 예산을 독점하지 않게 한다.
    private static void reviveInactiveColorParked(int sx, int sz, int maxRange, boolean containToActive,
                                                   boolean searchActiveSetChanged, long deadline) {
        if (searchActiveSetChanged && !inactiveRevivalInProgress) {
            inactiveRevivalScratch.clear();
            FrontierQueue.reviveInactiveColorParked(inactiveRevivalScratch);
            inactiveRevivalIndex = 0;
            inactiveRevivalInProgress = !inactiveRevivalScratch.isEmpty();
        }
        if (!inactiveRevivalInProgress) return;

        int sinceTimeCheck = 0;
        int n = inactiveRevivalScratch.size();
        while (inactiveRevivalIndex < n) {
            long cell = inactiveRevivalScratch.getLong(inactiveRevivalIndex);
            inactiveRevivalIndex++;
            long curColor = activeColorOrPark(cell, containToActive);
            if (curColor != NO_ID) {
                int cx = BlockPos.unpackLongX(cell);
                int cz = BlockPos.unpackLongZ(cell);
                FrontierQueue.enqueue(cell, cx, cz, sx, sz, maxRange);
            }
            if ((++sinceTimeCheck & 0xFF) == 0 && System.nanoTime() >= deadline) return; // 다음 틱에 이어서
        }
        inactiveRevivalScratch.clear();
        inactiveRevivalIndex = 0;
        inactiveRevivalInProgress = false;
    }

    // FrontierQueue.revivedScratch 처리 재개 상태(매 틱 새로 드레인하지 않고 이어간다).
    // 타임아웃으로 못 다 처리한 셀은 이미 "청크 로딩됨 + 범위 안"이 확인된 상태이므로
    // exiledByChunk로 되돌리지 않는다 - 되돌리면 다음 틱 drainExiledWithinRange가 같은 조건을
    // 또 확인해서 꺼냈다가 또 시간이 없어 못 처리하는 왕복이 생긴다. 대신 인덱스만 들고 있다가
    // 다음 틱에 바로 이어서 처리한다(reviveInactiveColorParked와 동일 패턴).
    private static int revivedProcessIndex = 0;
    private static boolean revivedProcessInProgress = false;

    private static void processRevivedExiledCells(int sx, int sz, int maxRange, boolean containToActive, long deadline) {
        if (!revivedProcessInProgress) {
            FrontierQueue.drainExiledWithinRange(sx, sz, maxRange, deadline);
            revivedProcessIndex = 0;
            revivedProcessInProgress = !FrontierQueue.revivedScratch.isEmpty();
        }
        if (!revivedProcessInProgress) return;

        var revivedCells = FrontierQueue.revivedScratch;
        int sinceTimeCheck = 0;
        int n = revivedCells.size();
        while (revivedProcessIndex < n) {
            long cell = revivedCells.getLong(revivedProcessIndex);
            revivedProcessIndex++;
            long curColor = activeColorOrPark(cell, containToActive);
            if (curColor != NO_ID) {
                int cellX = BlockPos.unpackLongX(cell);
                int cellZ = BlockPos.unpackLongZ(cell);
                FrontierQueue.enqueue(cell, cellX, cellZ, sx, sz, maxRange);
            }
            if ((++sinceTimeCheck & 0xFF) == 0 && System.nanoTime() >= deadline) return; // 다음 틱에 이어서
        }
        revivedCells.clear();
        revivedProcessIndex = 0;
        revivedProcessInProgress = false;
    }

    static void rebuildActiveSet() {
        if (activeSetSnapshotColor == activeColor && activeSetVersion == ColorGraph.colorGraphVersion) {
            return; // 캐시 유효: activeColor도 그래프도 안 바뀜
        }
        // 재계산 전 이전 activeSet을 보존해 새 집합과 diff, 실제 변경된 루트의 컬럼만 dirty 표시한다(전체 재도색 방지)
        // 디버그 모드는 activeSet과 무관하므로 diff 불필요
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

    // root 색에 속한 컬럼들을 전부 dirtyColumns에 추가한다. columnsByRoot 버킷만 훑으므로
    // "이 색이 칠한 컬럼 수"에만 비례한다. ColorGraph.absorbInto(병합)에서도 호출한다
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

    // 이번 floodFillWithVertical 호출이 activeColor를 갱신했으면 true. 초기 리턴 시(청크 미로딩 등)
    // false로 남아 onTickStart가 그때만 보정하며, 중복 갱신(히스테리시스 이중 증가)을 막는다
    static boolean activeColorUpdatedThisTick = false;

    static void updateActiveColor(BlockPos start) {
        updateActiveColorFromCell(resolvePlayerCell(start));
    }

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

    static long resolvePlayerCell(BlockPos start) {
        // findAnchorCell로 실제로 내려가며 확인하므로, 플레이어가 트랙 위 얼마나 떠 있든 그 아래 트랙을 정확히 찾는다
        var world = MCRiderMinimap.client.world;
        if (world == null) return NO_ID;
        if (!BlockSearch.isChunkLoadedAt(start.getX(), start.getZ())) return NO_ID;
        long anchor = findAnchorCell(start.getX(), start.getY(), start.getZ(), world.getBottomY());
        return resolvePlayerCellFromAnchor(anchor);
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
        activeColorUpdatedThisTick = false;
        var world = MCRiderMinimap.client.world;
        if (world == null) return;

        if (!BlockSearch.isChunkLoadedAt(start.getX(), start.getZ())) return;

        int sx = start.getX(), sz = start.getZ();
        long anchorCell = findAnchorCell(sx, start.getY(), sz, world.getBottomY());
        if (anchorCell == NO_ID) return;
        int sy = BlockPos.unpackLongY(anchorCell);

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
        long playerCell = resolvePlayerCellFromAnchor(anchorCell);
        updateActiveColorFromCell(playerCell);
        activeColorUpdatedThisTick = true;
        rebuildActiveSet();

        long liveRoot = (playerCell != NO_ID) ? ColorGraph.resolve(cellColor.get(playerCell)) : NO_ID;
        boolean searchActiveSetChanged = rebuildSearchActiveSet(liveRoot);
        final boolean containToActive = playerCell != NO_ID;

        final long deadline = System.nanoTime() + STAGING_TIME_BUDGET_NANOS;

        // searchActiveSet이 바뀐 틱에 새로 시작하거나, 이미 진행 중인 재검사를 이어간다(예산 초과 시 다음 틱)
        reviveInactiveColorParked(sx, sz, maxRange, containToActive, searchActiveSetChanged, deadline);

        // exile에서 되살아난 셀 처리도 같은 재개형 패턴: 못 다 처리한 나머지는 exiledByChunk로
        // 되돌리지 않고 인덱스만 들고 다음 틱에 이어간다(processRevivedExiledCells 참고)
        processRevivedExiledCells(sx, sz, maxRange, containToActive, deadline);

        boolean stop = false;
        while (!stop && !FrontierQueue.frontierChunkKeys.isEmpty()) {
            int n = FrontierQueue.sortChunkKeysByDistance(sx, sz);

            for (int ci = 0; ci < n; ci++) {
                long chunkKey = FrontierQueue.sortSnap[(int) (FrontierQueue.sortPacked[ci] & 0xFFFFFFFFL)];
                var bucket = FrontierQueue.frontierByChunk.get(chunkKey);
                if (bucket == null) continue;

                while (!bucket.isEmpty()) {
                    if (System.nanoTime() >= deadline) {
                        stop = true;
                        break;
                    }

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

                    long curColor = activeColorOrPark(curPacked, containToActive);
                    if (curColor == NO_ID) continue;

                    boolean hasBlockAt2Meter = !BlockSearch.isAirAt(cx, cy + 2, cz);
                    boolean parkedSelf = false;

                    for (int[] d : BlockSearch.DIRECTIONS) {
                        int nx = cx + d[0];
                        int nz = cz + d[1];

                        if (!BlockSearch.isChunkLoadedAt(nx, nz)) {
                            // 실제로 안 뜬 건 이웃(nx,nz)이므로 그 청크 키로 park해야 revive 조건이
                            // "그 이웃이 로딩됨"이 된다. cx,cz(자기 청크)로 걸면 이미 로딩된 상태라
                            // drainExiledWithinRange가 매 틱 즉시 되살렸다가 이웃이 여전히 안 뜬 걸
                            // 보고 다시 park하는 핑퐁이 생긴다(청크 로딩 경계/낮은 렌더 거리에서 흔함)
                            if (!parkedSelf) {
                                FrontierQueue.park(curPacked, nx, nz);
                                parkedSelf = true;
                            }
                            continue;
                        }

                        boolean baseIsAir = BlockSearch.isAirAt(nx, cy, nz);
                        boolean baseIsWall = !baseIsAir && BlockSearch.isWallAt(nx, cy, nz);
                        if (baseIsWall) continue;

                        int ty = BlockSearch.resolveTargetY(nx, cy, nz, baseIsAir, baseIsWall, hasBlockAt2Meter, world.getBottomY());
                        if (ty == Integer.MIN_VALUE) continue;

                        if (MCRiderMinimap.EXCLUDE_NARROW_PATHS
                                && BlockSearch.isNarrowPassageInRange(nx, cy, ty, nz, d[0], d[1])) {
                            continue;
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
                if (activeSet.contains(curRoot)) activeSet.add(childRoot);
                if (searchActiveSet.contains(curRoot)) searchActiveSet.add(childRoot);
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
                    // 엣지가 이미 있으면 그건 예전에 추가될 때 이미 이 사이클 검사를 통과했고,
                    // 그 이후 사이클이 생겼다면 rescanCycles가 두 루트를 병합해 resolve가 같아졌을 것
                    // (그럼 위 parentRoot != childRoot에서 이미 걸러짐)이므로, 지금 엣지가 남아 있다는 건
                    // childRoot가 여전히 parentRoot의 자손(=조상 아님)이라는 뜻이다. 따라서 조상 BFS를
                    // 생략해도 결과가 같다. 단방향 긴 트랙에서 이 BFS가 O(색 수)까지 커지는 걸 막는다.
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
                        if (activeSet.contains(parentRoot)) {
                            activeSet.add(childRoot);
                            markColumnsDirtyForRoot(childRoot);
                        }
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
        long root = ColorGraph.resolve(color);
        columnsByRoot.computeIfAbsent(root, k -> new LongOpenHashSet()).add(colKey);
        if (MCRiderMinimap.isDebugColors()) {
            MinimapRenderer.plotColumn(x, z);
        } else {
            dirtyColumns.add(colKey);
        }
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
        activeSetVersion = -1;
        activeSetSnapshotColor = NO_ID;
        searchActiveSet.clear();
        searchActiveSetVersion = -1;
        searchActiveSetSnapshotRoot = NO_ID;
        searchActiveSetTouchedByMerge = false;
        pendingActiveColorCandidate = NO_ID;
        pendingActiveColorStreak = 0;
        activeColorUpdatedThisTick = false;
        inactiveRevivalScratch.clear();
        inactiveRevivalIndex = 0;
        inactiveRevivalInProgress = false;
        revivedProcessIndex = 0;
        revivedProcessInProgress = false;
        BlockSearch.invalidateChunkCache();
    }
}