package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.util.math.ChunkPos;

/**
 * 활성 프론티어(청크별 대기 셀 목록)와 exile(범위 밖/미로딩 청크에 보류된 셀) 큐 관리 전담.
 * "어떤 셀이 지금 확장 대상인지" 판단(활성 트리 소속 여부 등)은 {@link FrontierSearch}가
 * 하고, 이 클래스는 그 판단 결과를 어느 버킷에 넣고 뺄지만 담당한다.
 */
final class FrontierQueue {
    private FrontierQueue() {}

    /** 활성 프론티어를 청크 단위로 묶어 보관: chunkKey(ChunkPos.toLong)에 대기 셀 목록.
     *  청크별로 몰아 처리하면 BlockSearch의 청크 캐시(4슬롯) 히트율이 오른다. 처리 "순서"만
     *  바꿀 뿐 정확성엔 영향 없음. 모든 셀은 frontierByChunk 또는 exiledByChunk 둘 중
     *  하나에만 존재한다. */
    static Long2ObjectOpenHashMap<LongArrayList> frontierByChunk = new Long2ObjectOpenHashMap<>();
    /** frontierByChunk에 대기 셀이 있는 청크 키 집합(거리순 정렬 스냅샷용). */
    static LongOpenHashSet frontierChunkKeys = new LongOpenHashSet();
    /** 미로딩/범위 밖 청크에 보류된 프론티어: ChunkPos.toLong에 셀 목록. */
    static Long2ObjectOpenHashMap<LongArrayList> exiledByChunk = new Long2ObjectOpenHashMap<>();

    /** drainExiledWithinRange가 채우는 재사용 리스트(매 틱 new 방지). floodFill은 재진입하지 않는다. */
    static final LongArrayList revivedScratch = new LongArrayList();

    // 청크 거리순 정렬용 재사용 스크래치. sortPacked[i] = (거리<<32 | sortSnap 안의 인덱스)로
    // 패킹해 Arrays.sort로 O(n log n) 정렬한다. 호출부는 정렬 후
    // sortSnap[(int)(sortPacked[i] & 0xFFFFFFFFL)] 형태로 i번째로 가까운 청크 키를 읽는다.
    static long[] sortSnap = new long[0];
    static long[] sortPacked = new long[0];

    /** frontierChunkKeys에 청크가 추가/제거될 때마다 증가하는 카운터. sortChunkKeysByDistance가
     *  "지난 정렬 이후 집합이 그대로면 재정렬을 건너뛴다"를 판단하는 데 쓴다(add/remove가
     *  상쇄되는 경우까지 잡으려고 size 대신 카운터를 쓴다). */
    private static long chunkKeysVersion = 0;
    // 마지막 정렬 시점의 (버전, 기준 좌표)와 그때의 n. 셋이 다 같으면 정렬 결과를 재사용한다.
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
            park(cell, cx, cz);
        }
    }

    static void park(long packedPos, int worldX, int worldZ) {
        long key = ChunkPos.toLong(worldX >> 4, worldZ >> 4);
        getOrCreateBucket(exiledByChunk, key, 4).add(packedPos);
    }

    /** 뷰 반경(range) 안에 아직 처리 못 한 프론티어 청크가 남아있는지 청크 단위로 싸게
     *  확인한다(대기 청크 수는 보통 수십~수백 개). true면 이번 틱 URGENT_SEARCH_* 예산을 쓴다. */
    static boolean hasPendingWithin(int range, int sx, int sz) {
        LongIterator it = frontierChunkKeys.iterator();
        while (it.hasNext()) {
            long k = it.nextLong();
            if (taxiDistanceFromChunkToPos(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k), sx, sz) <= range) {
                return true;
            }
        }
        ObjectIterator<Long2ObjectMap.Entry<LongArrayList>> eIt = exiledByChunk.long2ObjectEntrySet().iterator();
        while (eIt.hasNext()) {
            long k = eIt.next().getLongKey();
            if (taxiDistanceFromChunkToPos(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k), sx, sz) <= range) {
                return true;
            }
        }
        return false;
    }

    /** 대기 중인 프론티어 청크 키를 sx,sz 기준 거리순으로 정렬해 sortSnap/sortPacked에 채운다.
     *  둘 다 재사용 배열이라 다음 호출 전까지만(같은 틱 안에서 바로 소비) 유효하다.
     *  지난 정렬 이후 청크 집합도 기준 좌표도 안 바뀌었으면 재정렬 없이 지난 결과를 그대로 쓴다
     *  (거대 트랙에서 메인 루프가 매 라운드 O(n log n) 정렬을 반복하던 낭비를 없앤다). */
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

    /** 로딩됐고 sx,sz 기준 maxRange 안에 든 exile 청크의 셀을 모두 revivedScratch에 모으고
     *  그 청크들을 exile 맵에서 제거한다. exile 맵을 순회하며 직접 enqueue/park하면 항목이
     *  누락될 수 있어, 후보만 여기서 안전하게 모아 반환한다. deadline을 넘기면 그때까지 모은
     *  것만 두고 true(타임아웃)를 돌려준다. 호출부는 타임아웃이면 이미 꺼낸 셀들을 park으로
     *  되돌려 다음 틱 재평가로 미룬다. */
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
        revivedScratch.clear();
        chunkKeysVersion++;   // 캐시된 정렬 결과를 무효화
        lastSortVersion = -1;
    }
}
