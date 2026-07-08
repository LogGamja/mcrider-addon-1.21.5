package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.util.math.ChunkPos;

// 활성 프론티어(청크별 대기 셀 목록)와 exile(범위 밖/미로딩 청크에 보류된 셀) 큐 관리 전담.
// 어떤 셀이 지금 확장 대상인지 판단(활성 트리 소속 여부 등)은 FrontierSearch가 하고 이 클래스는 그 판단 결과를 어느 버킷에 넣고 뺄지만 담당한다.

final class FrontierQueue {
    private FrontierQueue() {}

    // 셀 보류 사유: 보류 저장소 선택을 명시적으로
    enum ParkReason {
        CHUNK_NOT_LOADED,     // 미로딩 청크 → exiledByChunk로
        OUT_OF_RANGE,         // 범위 밖 → exiledByChunk로
        COLOR_INACTIVE        // 활성 색 아님 → inactiveColorParked로
    }

    // 활성 프론티어를 청크 단위로 묶어 보관: chunkKey(ChunkPos.toLong)에 대기 셀 목록
    // 청크별로 몰아 처리하면 BlockSearch의 청크 캐시(4슬롯) 히트율이 오른다
    // 모든 셀은 frontierByChunk, exiledByChunk, inactiveColorParked 중 하나에만 존재한다
    static Long2ObjectOpenHashMap<LongArrayList> frontierByChunk = new Long2ObjectOpenHashMap<>();
    // frontierByChunk에 대기 셀이 있는 청크 키 집합(거리순 정렬 스냅샷용)
    static LongOpenHashSet frontierChunkKeys = new LongOpenHashSet();
    // 미로딩/범위 밖 청크에 보류된 프론티어: ChunkPos.toLong에 셀 목록
    static Long2ObjectOpenHashMap<LongArrayList> exiledByChunk = new Long2ObjectOpenHashMap<>();

    // 색이 없어 보류된 셀 전용 저장소. 청크는 이미 로드돼 있고 범위 안이라 exiledByChunk에
    // 넣으면 매 틱 되살렸다가 다시 파킹하는 무한 반복이 생긴다. 그래서 별도 저장소로 분리.
    static final LongOpenHashSet inactiveColorParked = new LongOpenHashSet();

    // drainExiledWithinRange가 채우는 재사용 리스트(매 틱 new 방지). floodFill은 재진입하지 않는다
    static final LongArrayList revivedScratch = new LongArrayList();

    // 청크 거리순 정렬용 재사용 스크래치
    // sortPacked[i] = (거리<<32 | sortSnap 안의 인덱스)로 패킹해 Arrays.sort로 O(n log n) 정렬한다
    // 호출부는 정렬 후 sortSnap[(int)(sortPacked[i] & 0xFFFFFFFFL)] 형태로 i번째로 가까운 청크 키를 읽는다
    static long[] sortSnap = new long[0];
    static long[] sortPacked = new long[0];

    // frontierChunkKeys에 청크가 추가/제거될 때마다 증가하는 카운터
    // sortChunkKeysByDistance가 "지난 정렬 이후 집합이 그대로면 재정렬을 건너뛴다"를 판단하는 데 쓴다
    // add/remove가 상쇄되는 경우까지 잡으려고 size 대신 카운터를 쓴다
    private static long chunkKeysVersion = 0;
    // 마지막 정렬 시점의 (버전, 기준 좌표)와 그때의 n. 셋이 다 같으면 정렬 결과를 재사용한다
    private static long lastSortVersion = -1;
    private static int lastSortSx = Integer.MIN_VALUE, lastSortSz = Integer.MIN_VALUE;
    private static int lastSortN = 0;

    static int taxiDistance2D(int ax, int az, int bx, int bz) {
        return Math.abs(ax - bx) + Math.abs(az - bz);
    }

    static int taxiDistanceFromChunkToPos(int chunkX, int chunkZ, int bx, int bz) {
        int minX = chunkX << 4, maxX = minX + 15;
        int minZ = chunkZ << 4, maxZ = minZ + 15;
        int dx = bx < minX ? minX - bx : (bx > maxX ? bx - maxX : 0);
        int dz = bz < minZ ? minZ - bz : (bz > maxZ ? bz - maxZ : 0);
        return dx + dz;
    }

    private static LongArrayList getOrCreateBucket(Long2ObjectOpenHashMap<LongArrayList> map, long key, int initialCapacity) {
        LongArrayList bucket = map.get(key);
        if (bucket == null) {
            bucket = new LongArrayList(initialCapacity);
            map.put(key, bucket);
        }
        return bucket;
    }

    static void push(long cell, int cx, int cz) {
        long chunkKey = ChunkPos.toLong(cx >> 4, cz >> 4);
        getOrCreateBucket(frontierByChunk, chunkKey, 8).add(cell);
        if (frontierChunkKeys.add(chunkKey)) chunkKeysVersion++; // 실제로 새 청크가 들어왔을 때만
    }

    static void removeChunk(long chunkKey) {
        frontierByChunk.remove(chunkKey);
        if (frontierChunkKeys.remove(chunkKey)) chunkKeysVersion++;
    }

    static void enqueue(long cell, int cx, int cz, int sx, int sz, int maxRange) {
        if (taxiDistance2D(cx, cz, sx, sz) <= maxRange) {
            push(cell, cx, cz);
        } else {
            park(cell, cx, cz, ParkReason.OUT_OF_RANGE);
        }
    }

    static void park(long packedPos, int worldX, int worldZ, ParkReason reason) {
        if (reason == ParkReason.COLOR_INACTIVE) {
            inactiveColorParked.add(packedPos);
        } else {
            long key = ChunkPos.toLong(worldX >> 4, worldZ >> 4);
            getOrCreateBucket(exiledByChunk, key, 4).add(packedPos);
        }
    }

    // 편의 오버로드: COLOR_INACTIVE 전용 (호출부 간결함)
    static void parkInactiveColor(long packedPos) {
        park(packedPos, 0, 0, ParkReason.COLOR_INACTIVE);
    }

    // searchActiveSet이 실제로 재계산됐을 때만 호출된다. 지금까지 색이 안 맞아 보류됐던 셀을
    // 전부 꺼내 out에 담고 저장소를 비운다 - 호출부가 다시 activeColorOrPark로 재검사한다.
    static void reviveInactiveColorParked(LongArrayList out) {
        if (inactiveColorParked.isEmpty()) return;
        LongIterator it = inactiveColorParked.iterator();
        while (it.hasNext()) out.add(it.nextLong());
        inactiveColorParked.clear();
    }

    // 청크 키를 거리순으로 정렬해 sortSnap과 sortPacked에 채운다. 청크 집합과 기준 좌표가
    // 안 바뀌었으면 재정렬 없이 지난 결과 사용. 거대 트랙에서 매 라운드 O(n log n) 정렬을 없앤다.
    static int sortChunkKeysByDistance(int sx, int sz) {
        if (chunkKeysVersion == lastSortVersion && sx == lastSortSx && sz == lastSortSz) {
            return lastSortN;
        }
        int n = frontierChunkKeys.size();
        if (sortSnap.length < n) {
            sortSnap = new long[n];
            sortPacked = new long[n];
        }
        int idx = 0;
        LongIterator keyIt = frontierChunkKeys.iterator();
        while (keyIt.hasNext()) {
            long k = keyIt.nextLong();
            sortSnap[idx] = k;
            int d = taxiDistanceFromChunkToPos(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k), sx, sz);
            sortPacked[idx] = ((long) d << 32) | (idx & 0xFFFFFFFFL);
            idx++;
        }
        java.util.Arrays.sort(sortPacked, 0, n);
        lastSortVersion = chunkKeysVersion;
        lastSortSx = sx;
        lastSortSz = sz;
        lastSortN = n;
        return n;
    }

    // 로딩되고 범위 안의 exile 청크 셀을 revivedScratch에 모으고 제거. 직접 enqueue하면
    // 누락되므로 후보만 모아 반환. deadline 초과 시 타임아웃 반환. 못 다 처리한 잔여분은 다음 틱에.
    static boolean drainExiledWithinRange(int sx, int sz, int maxRange, long deadline) {
        revivedScratch.clear();
        boolean timedOut = false;
        ObjectIterator<Long2ObjectMap.Entry<LongArrayList>> exiledIt = exiledByChunk.long2ObjectEntrySet().iterator();
        while (exiledIt.hasNext()) {
            if (System.nanoTime() >= deadline) {
                timedOut = true;
                break;
            }
            Long2ObjectMap.Entry<LongArrayList> e = exiledIt.next();
            long chunkKey = e.getLongKey();
            int chunkX = ChunkPos.getPackedX(chunkKey);
            int chunkZ = ChunkPos.getPackedZ(chunkKey);
            if (taxiDistanceFromChunkToPos(chunkX, chunkZ, sx, sz) <= maxRange
                    && MCRiderMinimap.client.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                LongArrayList pending = e.getValue();
                for (int i = 0, n = pending.size(); i < n; i++) {
                    revivedScratch.add(pending.getLong(i));
                }
                exiledIt.remove();
            }
        }
        return timedOut;
    }

    static void reset() {
        frontierByChunk.clear();
        frontierChunkKeys.clear();
        exiledByChunk.clear();
        inactiveColorParked.clear();
        revivedScratch.clear();
        chunkKeysVersion++; // 캐시된 정렬 결과를 무효화
        lastSortVersion = -1;
    }
}
