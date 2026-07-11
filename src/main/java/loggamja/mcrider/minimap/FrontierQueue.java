package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

// 프론티어 및 보류 셀 저장소 관리

final class FrontierQueue {
    private FrontierQueue() {}

    // park()의 동작은 reason이 아닌 호출자가 넘기는 좌표에 따른다
    enum ParkReason {
        CHUNK_NOT_LOADED,
        OUT_OF_RANGE,
        COLOR_INACTIVE
    }

    static Long2ObjectOpenHashMap<LongArrayList> frontierByChunk = new Long2ObjectOpenHashMap<>();
    static Long2ObjectOpenHashMap<LongArrayList> exiledByChunk = new Long2ObjectOpenHashMap<>();
    static final LongOpenHashSet inactiveColorParked = new LongOpenHashSet();

    static final LongArrayList revivedScratch = new LongArrayList();

    // 거리순 정렬용 (거리<<32 | 인덱스로 패킹)
    static long[] sortSnap = new long[0];
    static long[] sortPacked = new long[0];

    // 청크 집합이 바뀔 때마다 증가. sortChunkKeysByDistance가 이 값으로 정렬 캐시 재사용 여부를 판단한다.
    private static long chunkKeysVersion = 0;
    private static long lastSortVersion = -1;
    private static int lastSortSx = Integer.MIN_VALUE, lastSortSz = Integer.MIN_VALUE;
    private static int lastSortN = 0;

    // nanoTime() 오버플로우 안전 비교
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

    // getOrCreateBucket을 안 쓰는 이유: frontierByChunk에 새 키가 생길 때마다 chunkKeysVersion을 올려야 한다.
    static void push(long cell, int cx, int cz) {
        long chunkKey = ChunkPos.toLong(cx >> 4, cz >> 4);
        LongArrayList bucket = frontierByChunk.get(chunkKey);
        if (bucket == null) {
            bucket = new LongArrayList(8);
            frontierByChunk.put(chunkKey, bucket);
            chunkKeysVersion++;
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

    static void parkInactiveColor(long packedPos) {
        park(packedPos, 0, 0, ParkReason.COLOR_INACTIVE);
    }

    // searchActiveSet 재계산 후에만 호출해야 한다. 보류된 셀을 복구한다.
    static void reviveInactiveColorParked(LongArrayList out) {
        if (inactiveColorParked.isEmpty()) return;
        LongIterator it = inactiveColorParked.iterator();
        while (it.hasNext()) out.add(it.nextLong());
        inactiveColorParked.clear();
    }

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

    // 패스 중간에 타임아웃되면 다음 호출은 스냅샷의 이어지는 인덱스부터 재개한다.
    // 패스를 완주해야만(끝까지 돌아야만) index를 0으로 되돌려 새 스냅샷을 뜬다.
    // 그래야 스캔 도중 park()가 새 청크를 추가해도 진행 중인 패스가 앞부분만 반복하며 굶지 않는다.
    private static long[] exiledScanKeys = new long[0];
    private static int exiledScanLen = 0;
    private static int exiledScanIndex = 0;

    static boolean drainExiledWithinRange(int sx, int sz, int maxRange, long deadline) {
        revivedScratch.clear();
        if (exiledScanIndex == 0) {
            int n = exiledByChunk.size();
            if (exiledScanKeys.length < n) exiledScanKeys = new long[n];
            int idx = 0;
            LongIterator keyIt = exiledByChunk.keySet().iterator();
            while (keyIt.hasNext()) exiledScanKeys[idx++] = keyIt.nextLong();
            exiledScanLen = idx;
        }

        while (exiledScanIndex < exiledScanLen) {
            if (deadlineReached(deadline)) return true; // 다음 호출에서 이 인덱스부터 재개

            long chunkKey = exiledScanKeys[exiledScanIndex];
            exiledScanIndex++;

            LongArrayList pending = exiledByChunk.get(chunkKey);
            if (pending == null || pending.isEmpty()) continue; // 스냅샷 이후 이미 소진/제거됨

            int chunkX = ChunkPos.getPackedX(chunkKey);
            int chunkZ = ChunkPos.getPackedZ(chunkKey);
            if (taxiDistanceFromChunkToPos(chunkX, chunkZ, sx, sz) <= maxRange
                    && MCRiderMinimap.client.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                // 청크 코너 셀도 거리 재검사 (park/revive 반복 회피)
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
                    exiledByChunk.remove(chunkKey);
                } else {
                    pending.size(keep);
                }
            }
        }

        exiledScanIndex = 0; // 패스 완주. 다음 호출은 새 스냅샷으로 시작
        return false;
    }

    static void reset() {
        frontierByChunk.clear();
        frontierByChunk.trim();
        exiledByChunk.clear();
        exiledByChunk.trim();

        inactiveColorParked.clear();
        inactiveColorParked.trim();

        revivedScratch.clear();
        revivedScratch.trim();

        chunkKeysVersion++;
        lastSortVersion = -1;
        exiledScanIndex = 0;
        exiledScanLen = 0;

        sortSnap = new long[0];
        sortPacked = new long[0];
        exiledScanKeys = new long[0];
    }
}
