package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

// 규칙1: 고아 진입(TP)은 새 루트 색
// 규칙2: 양방향 이동은 색 유지
// 규칙3: 단방향 이동은 새 색을 만들고 직전 색의 자식으로 둠
// 규칙4: 양방향 인접 시 혹은 자식이 조상에게 단방향 인접 시 병합

final class ColorGraph {
    private ColorGraph() {}

    static final long NO_ID = Long.MIN_VALUE;

    static final Long2LongOpenHashMap colorParentPtr = new Long2LongOpenHashMap();
    static final Long2ObjectOpenHashMap<LongOpenHashSet> childToParents = new Long2ObjectOpenHashMap<>();
    static final Long2ObjectOpenHashMap<LongOpenHashSet> parentToChildren = new Long2ObjectOpenHashMap<>();
    static final Long2IntOpenHashMap colorBirth = new Long2IntOpenHashMap();

    static long nextColorId = 0;
    static int birthCounter = 0;

    // 그래프 구조가 바뀔 때(엣지 추가 / 병합) 증가. FrontierSearch의 activeSet 캐시 무효화 판단용
    static long colorGraphVersion = 0;
    static int actualColorCount = 0;

    static {
        colorParentPtr.defaultReturnValue(NO_ID);
    }

    static void bumpColorGraphVersion() {
        colorGraphVersion++;
    }

    static void ensureColor(long id) {
        if (!colorParentPtr.containsKey(id)) {
            colorParentPtr.put(id, id);
            colorBirth.put(id, birthCounter++);
            actualColorCount++;
        }
    }

    static long resolve(long id) {
        long root = id;
        long p;
        while ((p = colorParentPtr.get(root)) != root) {
            if (p == NO_ID) return id;
            root = p;
        }
        long cur = id;
        while (cur != root) {
            long next = colorParentPtr.get(cur);
            colorParentPtr.put(cur, root);
            cur = next;
        }
        return root;
    }

    static long newColor(long parentId) {
        long id = nextColorId++;
        ensureColor(id);
        if (parentId != NO_ID) {
            long pr = resolve(parentId);
            addEdge(pr, id);
        }
        return id;
    }

    static void addEdge(long parent, long child) {
        if (parent == child) return;

        // 엣지가 새로 추가된 경우에만 버전을 올린다
        boolean isNew = parentToChildren.computeIfAbsent(parent, k -> new LongOpenHashSet()).add(child);
        childToParents.computeIfAbsent(child, k -> new LongOpenHashSet()).add(parent);
        if (isNew) bumpColorGraphVersion();
    }

    // true는 확실하지만 false는 미정규화로 인한 false negative일 수 있다.
    // 호출부가 느린 경로로 폴백하면 안전하다.
    static boolean hasEdge(long parent, long child) {
        LongOpenHashSet kids = parentToChildren.get(parent);
        return kids != null && kids.contains(child);
    }

    // -- 병합 --

    // 병합 / 사이클 재검사용 스크래치. 역할별로 하나씩만 두고, 각 함수는 호출 시작 시 자기 몫을 clear() 후 쓴다
    private static final LongOpenHashSet scratchGroup = new LongOpenHashSet();
    private static final LongOpenHashSet scratchReachable = new LongOpenHashSet();
    private static final LongArrayFIFOQueue scratchReachQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet scratchAncestorsOfTo = new LongOpenHashSet();
    private static final LongArrayFIFOQueue scratchAncestorsOfToQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet scratchSeen = new LongOpenHashSet();
    private static final LongArrayFIFOQueue scratchSeenQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet scratchDescendants = new LongOpenHashSet();
    private static final LongOpenHashSet scratchAncestors = new LongOpenHashSet();

    // FrontierSearch.handleReach 단방향-재조우 분기(사이클 방지 조상 검사) 전용 스크래치
    static final LongOpenHashSet scratchParentAncestors = new LongOpenHashSet();

    static void mergeColors(long aId, long bId) {
        long a = resolve(aId);
        long b = resolve(bId);
        if (a == b) return;

        LongOpenHashSet group = scratchGroup;
        group.clear();
        group.add(a);
        group.add(b);
        collectChainIfAncestorBFS(a, b, group);
        collectChainIfAncestorBFS(b, a, group);

        long survivor = NO_ID;
        int bestBirth = Integer.MAX_VALUE;
        LongIterator git = group.iterator();
        while (git.hasNext()) {
            long c = git.nextLong();
            int birth = colorBirth.get(c);
            if (birth < bestBirth) { bestBirth = birth; survivor = c; }
        }

        git = group.iterator();
        while (git.hasNext()) {
            long c = git.nextLong();
            if (c != survivor) {
                absorbInto(c, survivor); // c의 컬럼을 dirty 표시 후 survivor로 이전
            }
        }
        bumpColorGraphVersion();

        if (group.contains(FrontierSearch.activeColor) && FrontierSearch.activeColor != survivor) {
            FrontierSearch.activeColor = survivor;
        }

        // 불변식: mergeColors는 항상 rescanCycles(do-while)로 끝나므로 모든 사이클이 완전히 해결됨.
        // 따라서 FrontierSearch.hasEdge 스킵이 안전한 상태가 됨.
        rescanCycles(survivor);
    }

    // 양방향 BFS로 무관계 증명 비용을 양쪽 중 작은 쪽으로 제한한다.
    // from에서 도달 가능한 노드 또는 to의 조상 중 먼저 소진되는 쪽이 끝나면 관계 판정 가능.
    static void collectChainIfAncestorBFS(long from, long to, LongOpenHashSet out) {
        from = resolve(from);
        to = resolve(to);
        if (from == to) return;

        LongOpenHashSet reachable = scratchReachable;
        reachable.clear();
        LongArrayFIFOQueue qFwd = scratchReachQueue;
        qFwd.clear();
        reachable.add(from);
        qFwd.enqueue(from);

        LongOpenHashSet ancestorsOfTo = scratchAncestorsOfTo;
        ancestorsOfTo.clear();
        LongArrayFIFOQueue qBack = scratchAncestorsOfToQueue;
        qBack.clear();
        ancestorsOfTo.add(to);
        qBack.enqueue(to);

        boolean fwdDone = false, backDone = false;
        while (!fwdDone || !backDone) {
            if (!fwdDone) {
                if (qFwd.isEmpty()) {
                    fwdDone = true;
                    if (!reachable.contains(to)) return;
                } else {
                    long cur = qFwd.dequeueLong();
                    LongOpenHashSet kids = parentToChildren.get(cur);
                    if (kids != null) {
                        LongIterator kit = kids.iterator();
                        while (kit.hasNext()) {
                            long kid = resolve(kit.nextLong());
                            if (kid != cur && reachable.add(kid)) qFwd.enqueue(kid);
                        }
                    }
                }
            }
            if (!backDone) {
                if (qBack.isEmpty()) {
                    backDone = true;
                    if (!ancestorsOfTo.contains(from)) return;
                } else {
                    long cur = qBack.dequeueLong();
                    LongOpenHashSet parents = childToParents.get(cur);
                    if (parents != null) {
                        LongIterator pit = parents.iterator();
                        while (pit.hasNext()) {
                            long par = resolve(pit.nextLong());
                            if (par != cur && ancestorsOfTo.add(par)) qBack.enqueue(par);
                        }
                    }
                }
            }
        }

        LongIterator it = reachable.iterator();
        while (it.hasNext()) {
            long n = it.nextLong();
            if (ancestorsOfTo.contains(n)) out.add(n);
        }
    }

    static void absorbInto(long loser, long survivor) {
        actualColorCount--; // loser는 호출 시점에 항상 resolve된(자기 자신을 가리키던) 루트였다
        colorBirth.remove(loser); // birth는 루트끼리의 survivor 선정에만 쓰이고 loser는 다시 루트가 될 수 없다
        // 불변식: columnsByRoot 이전 전에 dirty 마킹. 순서 중요 (컬럼 activeSet 상태 전환 감지).
        FrontierSearch.markColumnsDirtyForRoot(loser);
        // 자손 없는 loser 흡수는 subtree 내용이 안 바뀌어 diff로 못 잡힌다. 여기서 직접 revive 신호를 준다.
        FrontierSearch.noteMergeSurvivor(survivor);
        LongOpenHashSet cols = FrontierSearch.columnsByRoot.remove(loser);
        if (cols != null) {
            FrontierSearch.columnsByRoot.computeIfAbsent(survivor, k -> new LongOpenHashSet()).addAll(cols);
        }
        // parentToChildren / childToParents의 loser 키 버킷은 resolve()로 자동 정규화되지 않으므로 survivor 키로 직접 옮긴다.
        // 그 전에 stale loser id의 메모리 누수 방지 차우너에서 loser를 참조하는 반대편 항목들도 survivor로 치환해야 한다.
        replaceValueInReverseMap(parentToChildren, childToParents, loser, survivor);
        replaceValueInReverseMap(childToParents, parentToChildren, loser, survivor);
        migrateAdjacency(parentToChildren, loser, survivor);
        migrateAdjacency(childToParents, loser, survivor);
        colorParentPtr.put(loser, survivor);
    }

    private static void migrateAdjacency(Long2ObjectOpenHashMap<LongOpenHashSet> adj, long loser, long survivor) {
        LongOpenHashSet moved = adj.remove(loser);
        if (moved == null || moved.isEmpty()) return;
        LongOpenHashSet target = adj.computeIfAbsent(survivor, k -> new LongOpenHashSet());
        LongIterator it = moved.iterator();
        while (it.hasNext()) {
            long v = it.nextLong();
            if (v != survivor) target.add(v);
        }
    }

    // ownAdj[loser](마이그레이션 전 loser 자신의 버킷)의 각 원소 v에 대해, v가 반대 방향으로 loser를
    // 가리키고 있는 otherAdj[v]에서 loser를 survivor로 치환한다. v == survivor인 경우(둘 사이에 직접
    // 엣지가 있던 경우)는 병합 후 자기 자신을 향한 엣지가 되므로 survivor를 다시 추가하지 않고 제거만 한다.
    private static void replaceValueInReverseMap(Long2ObjectOpenHashMap<LongOpenHashSet> ownAdj,
                                                  Long2ObjectOpenHashMap<LongOpenHashSet> otherAdj,
                                                  long loser, long survivor) {
        LongOpenHashSet own = ownAdj.get(loser);
        if (own == null || own.isEmpty()) return;
        LongIterator it = own.iterator();
        while (it.hasNext()) {
            long v = it.nextLong();
            LongOpenHashSet other = otherAdj.get(v);
            if (other != null && other.remove(loser) && v != survivor) {
                other.add(survivor);
            }
        }
    }

    // 루프 사용: 재귀 깊이 제한 없음. deadline 검사 없이 동기 실행되므로 극단적 사이클 케이스에서 프레임 스파이크 가능
    static void rescanCycles(long survivor) {
        survivor = resolve(survivor);
        boolean merged;
        do {
            LongOpenHashSet descendants = scratchDescendants;
            descendants.clear();
            collectDescendants(survivor, descendants);
            LongOpenHashSet ancestors = scratchAncestors;
            ancestors.clear();
            collectAncestors(survivor, ancestors);

            merged = false;
            LongIterator it = descendants.iterator();
            while (it.hasNext()) {
                long d = it.nextLong();
                if (d != survivor && ancestors.contains(d)) {
                    absorbInto(d, survivor);
                    if (FrontierSearch.activeColor == d) FrontierSearch.activeColor = survivor;
                    merged = true;
                }
            }
            if (merged) bumpColorGraphVersion();
        } while (merged);
    }

    // start에서 adj 방향으로 도달 가능한 (resolve된) 노드를 out에 모은다(start 자신 제외)
    // collectDescendants / collectAncestors가 인접 맵만 달리해 공유하는 공용 BFS
    static void collectReachableBFS(long start, Long2ObjectOpenHashMap<LongOpenHashSet> adj, LongOpenHashSet out) {
        start = resolve(start);
        LongArrayFIFOQueue q = scratchSeenQueue;
        q.clear();
        LongOpenHashSet seen = scratchSeen;
        seen.clear();
        q.enqueue(start); seen.add(start);
        while (!q.isEmpty()) {
            long cur = q.dequeueLong();
            LongOpenHashSet nbrs = adj.get(cur);
            if (nbrs == null) continue;
            LongIterator it = nbrs.iterator();
            while (it.hasNext()) {
                long nb = resolve(it.nextLong());
                if (seen.add(nb)) { out.add(nb); q.enqueue(nb); }
            }
        }
    }

    static void collectDescendants(long start, LongOpenHashSet out) {
        collectReachableBFS(start, parentToChildren, out);
    }

    static void collectAncestors(long start, LongOpenHashSet out) {
        collectReachableBFS(start, childToParents, out);
    }

    static void reset() {
        colorParentPtr.clear();
        childToParents.clear();
        parentToChildren.clear();
        colorBirth.clear();
        nextColorId = 0;
        birthCounter = 0;
        colorGraphVersion = 0;
        actualColorCount = 0;
    }
}
