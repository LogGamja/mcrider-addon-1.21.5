package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * 색(트랙 조각) 간 부모/자식 관계 그래프 + union-find(경로 압축).
 *
 * 규칙0: 양방향 이동 → 색 유지. 규칙1: 고아 진입(TP/리스폰 포함) → 새 루트 색.
 * 규칙2: 단방향 이동 → 새 색(직전 색의 자식). 규칙3: 양방향 인접 시만 병합.
 *
 * 색 "관계"만 다룬다. 색이 실제로 어디 칠해졌는지(cellColor 등)나 화면 표시(activeColor 등)는
 * {@link FrontierSearch} 책임. 단 병합(mergeColors/absorbInto/rescanCycles)은 양쪽에 영향을
 * 주므로, 여기서만 예외적으로 FrontierSearch의 정적 상태를 직접 참조/갱신한다.
 */
final class ColorGraph {
    private ColorGraph() {}

    static final long NO_ID = Long.MIN_VALUE;

    static final Long2LongOpenHashMap colorParentPtr = new Long2LongOpenHashMap();
    static final Long2ObjectOpenHashMap<LongOpenHashSet> childToParents = new Long2ObjectOpenHashMap<>();
    static final Long2ObjectOpenHashMap<LongOpenHashSet> parentToChildren = new Long2ObjectOpenHashMap<>();
    static final Long2IntOpenHashMap colorBirth = new Long2IntOpenHashMap();

    static long nextColorId = 0;
    static int birthCounter = 0;

    /** 그래프 구조가 바뀔 때(엣지 추가/병합)만 증가. FrontierSearch의 activeSet 캐시 무효화 판단용. */
    static long colorGraphVersion = 0;

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
        parentToChildren.computeIfAbsent(parent, k -> new LongOpenHashSet()).add(child);
        childToParents.computeIfAbsent(child, k -> new LongOpenHashSet()).add(parent);
        bumpColorGraphVersion();
    }

    // ═══════════════════════════ 병합 ═══════════════════════════

    // 병합/사이클 재검사용 재사용 스크래치(GC 압박 방지). 역할별로 하나씩만 두고,
    // 각 함수는 호출 시작 시 자기 몫을 clear() 후 쓴다.
    private static final LongOpenHashSet scratchGroup = new LongOpenHashSet();
    private static final LongOpenHashSet scratchReachable = new LongOpenHashSet();
    private static final LongArrayFIFOQueue scratchReachQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet scratchAncestorsOfTo = new LongOpenHashSet();
    private static final LongOpenHashSet scratchSeen = new LongOpenHashSet();
    private static final LongArrayFIFOQueue scratchSeenQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet scratchDescendants = new LongOpenHashSet();
    private static final LongOpenHashSet scratchAncestors = new LongOpenHashSet();
    /** FrontierSearch.handleReach 단방향-재조우 분기(사이클 방지 조상 검사) 전용 스크래치. */
    static final LongOpenHashSet scratchParentAncestors = new LongOpenHashSet();

    static void mergeColors(long aId, long bId) {
        long a = resolve(aId);
        long b = resolve(bId);
        if (a == b) return;

        LongOpenHashSet group = scratchGroup;
        group.clear();
        group.add(a);
        group.add(b);
        collectChainIfAncestor(a, b, group);
        collectChainIfAncestor(b, a, group);

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

        rescanCycles(survivor);
    }

    /**
     * from이 to의 조상이면 그 경로 위 모든 색을 out에 추가한다.
     * birth(자식이 부모보다 항상 늦게 태어남)로 두 번 가지치기: (1) birth(from)>=birth(to)면
     * 즉시 반환, (2) 정방향 탐색 중 to보다 늦게 태어난 가지는 잘라낸다. 큐 기반이라 스택 오버플로 없음.
     */
    static void collectChainIfAncestor(long from, long to, LongOpenHashSet out) {
        from = resolve(from);
        to = resolve(to);
        if (from == to) return;

        int fromBirth = colorBirth.get(from);
        int toBirth = colorBirth.get(to);
        if (fromBirth >= toBirth) return; // from이 to보다 늦게(또는 같이) 태어남 → 조상 불가

        // from에서 자식 방향 도달 가능 노드(to보다 늦게 태어난 가지는 가지치기)
        LongOpenHashSet reachable = scratchReachable;
        reachable.clear();
        LongArrayFIFOQueue q = scratchReachQueue;
        q.clear();
        reachable.add(from);
        q.enqueue(from);
        while (!q.isEmpty()) {
            long cur = q.dequeueLong();
            LongOpenHashSet kids = parentToChildren.get(cur);
            if (kids == null) continue;
            LongIterator kit = kids.iterator();
            while (kit.hasNext()) {
                long kid = resolve(kit.nextLong());
                if (kid == cur) continue;
                if (colorBirth.get(kid) > toBirth) continue;
                if (reachable.add(kid)) q.enqueue(kid);
            }
        }
        if (!reachable.contains(to)) return; // to에 도달 불가 → 조상 아님

        // to의 조상 집합과 교집합 = from→to 경로
        LongOpenHashSet ancestorsOfTo = scratchAncestorsOfTo;
        ancestorsOfTo.clear();
        ancestorsOfTo.add(to);
        collectAncestors(to, ancestorsOfTo);

        LongIterator it = reachable.iterator();
        while (it.hasNext()) {
            long n = it.nextLong();
            if (ancestorsOfTo.contains(n)) out.add(n);
        }
    }

    /** loser의 컬럼을 survivor로 이전(dirty 표시 포함)하고, loser의 간선을 survivor로 옮긴 뒤
     *  loser의 부모 포인터를 survivor로 재지정한다. activeColor 갱신은 호출부 몫.
     *  columnsByRoot는 FrontierSearch 소유라 여기서 직접 참조한다. */
    static void absorbInto(long loser, long survivor) {
        FrontierSearch.markColumnsDirtyForRoot(loser);
        LongOpenHashSet cols = FrontierSearch.columnsByRoot.remove(loser);
        if (cols != null) {
            FrontierSearch.columnsByRoot.computeIfAbsent(survivor, k -> new LongOpenHashSet()).addAll(cols);
        }
        // parentToChildren/childToParents의 loser 키 버킷은 resolve()로 자동 정규화되지
        // 않으므로 survivor 키로 직접 옮긴다.
        migrateAdjacency(parentToChildren, loser, survivor);
        migrateAdjacency(childToParents, loser, survivor);
        colorParentPtr.put(loser, survivor);
    }

    /** adj[loser]의 이웃을 adj[survivor]로 합치고 loser 항목은 제거(self-loop 제외). */
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

    static void rescanCycles(long survivor) {
        survivor = resolve(survivor);
        LongOpenHashSet descendants = scratchDescendants;
        descendants.clear();
        collectDescendants(survivor, descendants);
        LongOpenHashSet ancestors = scratchAncestors;
        ancestors.clear();
        collectAncestors(survivor, ancestors);

        boolean merged = false;
        LongIterator it = descendants.iterator();
        while (it.hasNext()) {
            long d = it.nextLong();
            if (d != survivor && ancestors.contains(d)) {
                absorbInto(d, survivor);
                if (FrontierSearch.activeColor == d) FrontierSearch.activeColor = survivor;
                merged = true;
            }
        }
        if (merged) {
            bumpColorGraphVersion();
            rescanCycles(survivor);
        }
    }

    /** start에서 adj 방향으로 도달 가능한 (resolve된) 노드를 out에 모은다(start 자신 제외).
     *  collectDescendants/collectAncestors가 인접 맵만 달리해 공유하는 공용 BFS. */
    static void collectReachable(long start, Long2ObjectOpenHashMap<LongOpenHashSet> adj, LongOpenHashSet out) {
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
        collectReachable(start, parentToChildren, out);
    }

    static void collectAncestors(long start, LongOpenHashSet out) {
        collectReachable(start, childToParents, out);
    }

    static void reset() {
        colorParentPtr.clear();
        childToParents.clear();
        parentToChildren.clear();
        colorBirth.clear();
        nextColorId = 0;
        birthCounter = 0;
        colorGraphVersion = 0;
    }
}
