package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 규칙1: 고아 진입(TP)은 새 루트 색
// 규칙2: 양방향 이동은 색 유지
// 규칙3: 단방향 이동은 새 색을 만들고 직전 색의 자식으로 둠
// 규칙4: 양방향 인접 시 혹은 자식이 조상에게 단방향 인접 시 병합

final class ColorGraph {
    private ColorGraph() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("mcrider");

    static final long NO_ID = Long.MIN_VALUE;

    static final Long2LongOpenHashMap colorParentPtr = new Long2LongOpenHashMap();
    static final Long2ObjectOpenHashMap<LongOpenHashSet> childToParents = new Long2ObjectOpenHashMap<>();
    static final Long2ObjectOpenHashMap<LongOpenHashSet> parentToChildren = new Long2ObjectOpenHashMap<>();
    static final Long2IntOpenHashMap colorBirth = new Long2IntOpenHashMap();

    static long nextColorId = 0;
    static int birthCounter = 0;

    // 그래프 변경 감지 (캐시 무효화)
    static long colorGraphVersion = 0;
    static int actualColorCount = 0;

    static {
        colorParentPtr.defaultReturnValue(NO_ID);
        colorBirth.defaultReturnValue(Integer.MAX_VALUE);
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
        boolean isNew = parentToChildren.computeIfAbsent(parent, k -> new LongOpenHashSet()).add(child);
        childToParents.computeIfAbsent(child, k -> new LongOpenHashSet()).add(parent);
        if (isNew) bumpColorGraphVersion();
    }

    static boolean hasEdge(long parent, long child) {
        LongOpenHashSet kids = parentToChildren.get(parent);
        return kids != null && kids.contains(child);
    }

    private static final LongOpenHashSet scratchGroup = new LongOpenHashSet();
    private static final LongOpenHashSet scratchReachable = new LongOpenHashSet();
    private static final LongArrayFIFOQueue scratchReachQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet scratchAncestorsOfTo = new LongOpenHashSet();
    private static final LongArrayFIFOQueue scratchAncestorsOfToQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet scratchSeen = new LongOpenHashSet();
    private static final LongArrayFIFOQueue scratchSeenQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet scratchDescendants = new LongOpenHashSet();
    private static final LongOpenHashSet scratchAncestors = new LongOpenHashSet();
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
            if (c != survivor) absorbInto(c, survivor);
        }
        bumpColorGraphVersion();

        if (group.contains(FrontierSearch.activeColor) && FrontierSearch.activeColor != survivor) {
            FrontierSearch.activeColor = survivor;
        }

        // 불변식: mergeColors는 항상 rescanCycles(do-while)로 끝나므로 모든 사이클이 완전히 해결됨.
        // 따라서 FrontierSearch.hasEdge 스킵이 안전한 상태가 됨.
        rescanCycles(survivor);
    }

    // 양방향 BFS로 무관계 증명 비용 최소화
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
        actualColorCount--;
        colorBirth.remove(loser);
        FrontierSearch.markColumnsDirtyForRoot(loser); // columnsByRoot 이전 전에 dirty 마킹
        FrontierSearch.noteMergeSurvivor(survivor); // 자손 없는 색 흡수 신호
        LongOpenHashSet cols = FrontierSearch.columnsByRoot.remove(loser);
        if (cols != null) {
            FrontierSearch.columnsByRoot.computeIfAbsent(survivor, k -> new LongOpenHashSet()).addAll(cols);
        }
        // 반대편 맵에 남은 loser 역참조도 함께 정리해야 한다. 정리하지 않으면 죽은 참조가 쌓여서
        // rescanCycles가 매번 도는 BFS 비용이 세션이 길어질수록 계속 늘어난다.
        migrateDirection(parentToChildren, childToParents, loser, survivor);
        migrateDirection(childToParents, parentToChildren, loser, survivor);
        colorParentPtr.put(loser, survivor);
    }

    // v가 survivor와 같다면 둘 사이에 원래 직접 엣지가 있었다는 뜻이다. 병합 후엔 자기 자신을
    // 향한 엣지가 되므로 제거만 하고 다시 넣지 않는다.
    private static void migrateDirection(Long2ObjectOpenHashMap<LongOpenHashSet> ownAdj,
                                          Long2ObjectOpenHashMap<LongOpenHashSet> otherAdj,
                                          long loser, long survivor) {
        LongOpenHashSet own = ownAdj.remove(loser);
        if (own == null || own.isEmpty()) return;
        LongOpenHashSet target = ownAdj.computeIfAbsent(survivor, k -> new LongOpenHashSet());
        LongIterator it = own.iterator();
        while (it.hasNext()) {
            long v = it.nextLong();
            if (v != survivor) target.add(v);
            LongOpenHashSet other = otherAdj.get(v);
            if (other != null && other.remove(loser) && v != survivor) {
                other.add(survivor);
            }
        }
    }

    // deadline 없이 모든 사이클을 완전히 해결할 때까지 도는 무한 루프다. 이건 의도된 설계다.
    // FrontierSearch.handleReach의 hasEdge 빠른 경로는 이 함수가 반환하는 시점엔 사이클이 전부
    // 해결돼 있다는 전제에 기댄다. 여기서 중간에 끊으면 병합이 누락되고, 색이 계속 조각나면서
    // actualColorCount가 폭증하게 된다.
    static void rescanCycles(long survivor) {
        survivor = resolve(survivor);
        long rescanStartNanos = System.nanoTime();
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

        long elapsed = System.nanoTime() - rescanStartNanos;
        if (elapsed > 5_000_000L) {
            LOGGER.warn("[MCRider] rescanCycles took {}ms for survivor={}", elapsed / 1_000_000.0, survivor);
        }
    }

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