package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

// 활성 프론티어(청크별 대기 셀)과 exile(보류된 셀) 관리
// FrontierSearch가 판단한 셀을 어느 저장소에 넣고 뺄지 담당

final class FrontierQueue {
    private FrontierQueue() {}

    // 셀 보류 사유: 저장소 선택을 명시적으로
    enum ParkReason {
        CHUNK_NOT_LOADED,     // 미로딩 청크 → exiledByChunk로
        OUT_OF_RANGE,         // 범위 밖 → exiledByChunk로
        COLOR_INACTIVE        // 활성 색 아님 → inactiveColorParked로
    }

    // 청크별 활성 프론티어 (청크 캐시 효율 향상)
    static Long2ObjectOpenHashMap<LongArrayList> frontierByChunk = new Long2ObjectOpenHashMap<>();
    // 미로딩/범위 밖 청크의 보류 셀
    static Long2ObjectOpenHashMap<LongArrayList> exiledByChunk = new Long2ObjectOpenHashMap<>();
    // 비활성 색 셀 (별도 저장소: ping-pong 방지, 매 틱 재처리 회피)
    static final LongOpenHashSet inactiveColorParked = new LongOpenHashSet();

    static final LongArrayList revivedScratch = new LongArrayList();

    // 거리순 정렬용 (거리<<32 | 인덱스로 패킹)
    static long[] sortSnap = new long[0];
    static long[] sortPacked = new long[0];

    // 청크 집합 변경 감지 (정렬 캐시 무효화)
    private static long chunkKeysVersion = 0;
    // 정렬 결과 캐싱 (지난 정렬 재사용)
    private static long lastSortVersion = -1;
    private static int lastSortSx = Integer.MIN_VALUE, lastSortSz = Integer.MIN_VALUE;
    private static int lastSortN = 0;

    // System.nanoTime()은 절대값이 아니라 임의 원점 기준이라 오버플로우를 감안한 뺄셈 비교가 필요하다
    // (System.nanoTime() >= deadline 형태는 nanoTime()이 오버플로우로 음수가 되는 순간 deadline보다
    // 항상 작다고 잘못 판정될 수 있다). 여러 파일에서 반복되는 이 비교를 한 곳에 이름 붙여둔다.
    static boolean deadlineReached(long deadline) {
        return System.nanoTime() - deadline >= 0;
    }

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
        LongArrayList bucket = frontierByChunk.get(chunkKey);
        if (bucket == null) {
            bucket = new LongArrayList(8);
            frontierByChunk.put(chunkKey, bucket);
            chunkKeysVersion++; // 실제로 새 청크가 들어왔을 때만
        }
        bucket.add(cell);
    }

    static void removeChunk(long chunkKey) {
        if (frontierByChunk.remove(chunkKey) != null) chunkKeysVersion++;
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

    // 청크를 거리순으로 정렬 (캐싱으로 O(n log n) 정렬 회피)
    static int sortChunkKeysByDistance(int sx, int sz) {
        if (chunkKeysVersion == lastSortVersion && sx == lastSortSx && sz == lastSortSz) {
            return lastSortN;
        }
        int n = frontierByChunk.size();
        if (sortSnap.length < n) {
            sortSnap = new long[n];
            sortPacked = new long[n];
        }
        int idx = 0;
        LongIterator keyIt = frontierByChunk.keySet().iterator();
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

    // 범위 내 exile 셀을 revivedScratch에 모아 복구 (deadline 예산으로 분산 처리)
    static boolean drainExiledWithinRange(int sx, int sz, int maxRange, long deadline) {
        revivedScratch.clear();
        boolean timedOut = false;
        ObjectIterator<Long2ObjectMap.Entry<LongArrayList>> exiledIt = exiledByChunk.long2ObjectEntrySet().iterator();
        while (exiledIt.hasNext()) {
            if (deadlineReached(deadline)) {
                timedOut = true;
                break;
            }
            Long2ObjectMap.Entry<LongArrayList> e = exiledIt.next();
            long chunkKey = e.getLongKey();
            int chunkX = ChunkPos.getPackedX(chunkKey);
            int chunkZ = ChunkPos.getPackedZ(chunkKey);
            if (taxiDistanceFromChunkToPos(chunkX, chunkZ, sx, sz) <= maxRange
                    && MCRiderMinimap.client.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                // 청크는 범위 안이지만(가장 가까운 변 기준) 청크 코너 쪽 셀은 여전히 maxRange
                // 밖일 수 있다. 그런 셀까지 되살리면 곧바로 enqueue의 셀 단위 거리 재검사에서
                // 다시 OUT_OF_RANGE로 park되어 경계 청크 전체가 매 틱 revive/park를 반복한다.
                // 여기서 셀 단위로 한 번 더 걸러 진짜 범위 안인 것만 꺼내고, 나머지는 그대로 남긴다.
                LongArrayList pending = e.getValue();
                int keep = 0;
                for (int i = 0, n = pending.size(); i < n; i++) {
                    long cell = pending.getLong(i);
                    int cx = BlockPos.unpackLongX(cell);
                    int cz = BlockPos.unpackLongZ(cell);
                    if (taxiDistance2D(cx, cz, sx, sz) <= maxRange) {
                        revivedScratch.add(cell);
                    } else {
                        pending.set(keep++, cell);
                    }
                }
                if (keep == 0) {
                    exiledIt.remove();
                } else {
                    pending.size(keep);
                }
            }
        }
        return timedOut;
    }

    static void reset() {
        frontierByChunk.clear();
        exiledByChunk.clear();
        inactiveColorParked.clear();
        revivedScratch.clear();
        chunkKeysVersion++; // 캐시된 정렬 결과를 무효화
        lastSortVersion = -1;
    }
}
