package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

import java.util.function.Predicate;

final class BlockSearch {
    private BlockSearch() {}

    static final TagKey<Block> KART_WALL = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "stones"));
    static final Predicate<BlockState> isWall = state -> state.isIn(KART_WALL);
    static final TagKey<Block> KART_AIR = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "ignoreblock"));
    static final Predicate<BlockState> isAir = state -> state.isIn(KART_AIR);
    private static final Predicate<BlockState> isVoid = state -> state.isOf(Blocks.STRUCTURE_VOID);

    static final int[][] DIRECTIONS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
    static final int MAX_ANCHOR_DROP_SEARCH = 16;

    private static final int CHUNK_CACHE_SLOTS = 4;
    private static final long[] cacheKeys = new long[CHUNK_CACHE_SLOTS];
    private static final Chunk[] cacheChunks = new Chunk[CHUNK_CACHE_SLOTS];
    private static final BlockPos.Mutable MUTABLE = new BlockPos.Mutable();

    static void invalidateChunkCache() {
        java.util.Arrays.fill(cacheKeys, Long.MIN_VALUE);
        java.util.Arrays.fill(cacheChunks, null);
    }

    // 언로드된 청크 캐시 제거 (stale 상태 방지)
    static void invalidateChunkCacheAt(int chunkX, int chunkZ) {
        long key = ChunkPos.toLong(chunkX, chunkZ);
        for (int i = 0; i < CHUNK_CACHE_SLOTS; i++) {
            if (cacheKeys[i] == key) {
                cacheKeys[i] = Long.MIN_VALUE;
                cacheChunks[i] = null;
                return;
            }
        }
    }


    private static BlockState stateAt(int x, int y, int z) {
        long key = ChunkPos.toLong(x >> 4, z >> 4);
        for (int i = 0; i < CHUNK_CACHE_SLOTS; i++) {
            if (cacheKeys[i] == key && cacheChunks[i] != null) {
                if (i != 0) {
                    long hitKey = cacheKeys[i];
                    Chunk hitChunk = cacheChunks[i];
                    System.arraycopy(cacheKeys, 0, cacheKeys, 1, i);
                    System.arraycopy(cacheChunks, 0, cacheChunks, 1, i);
                    cacheKeys[0] = hitKey;
                    cacheChunks[0] = hitChunk;
                }
                return cacheChunks[0].getBlockState(MUTABLE.set(x, y, z));
            }
        }
        Chunk chunk = MCRiderMinimap.client.world.getChunk(x >> 4, z >> 4);
        if (chunk instanceof EmptyChunk) {
            // 미로딩 청크의 EmptyChunk는 캐시하지 않는다 - 캐시 무효화가 언로드 이벤트에만 걸려 있어,
            // 여기서 캐시하면 그 청크가 나중에 로드돼도 계속 빈 청크를 읽는다
            return chunk.getBlockState(MUTABLE.set(x, y, z));
        }
        System.arraycopy(cacheKeys, 0, cacheKeys, 1, CHUNK_CACHE_SLOTS - 1);
        System.arraycopy(cacheChunks, 0, cacheChunks, 1, CHUNK_CACHE_SLOTS - 1);
        cacheKeys[0] = key;
        cacheChunks[0] = chunk;
        return chunk.getBlockState(MUTABLE.set(x, y, z));
    }

    private static boolean testAt(Predicate<BlockState> predicate, int x, int y, int z) {
        if (MCRiderMinimap.client.world == null) return false;
        return predicate.test(stateAt(x, y, z));
    }

    static boolean isAirAt(int x, int y, int z) {
        return testAt(isAir, x, y, z);
    }
    static boolean isWallAt(int x, int y, int z) {
        return testAt(isWall, x, y, z);
    }
    static boolean isVoidAt(int x, int y, int z) {
        return testAt(isVoid, x, y, z);
    }
    static boolean isVoidFloorUnder(int x, int y, int z) {
        return isVoidAt(x, y - 1, z);
    }
    static boolean isChunkLoadedAt(int x, int z) {
        if (MCRiderMinimap.client.world == null) return false;
        // 4슬롯 캐시에 있는 청크는 반드시 로딩된 청크다(EmptyChunk는 캐시에 안 들어가고, 언로드 시 즉시 제거됨)
        long key = ChunkPos.toLong(x >> 4, z >> 4);
        for (int i = 0; i < CHUNK_CACHE_SLOTS; i++) {
            if (cacheKeys[i] == key && cacheChunks[i] != null) return true;
        }
        return MCRiderMinimap.client.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }
    static boolean isStandable(int x, int y, int z, boolean headAlreadyChecked) {
        if (!headAlreadyChecked && !isAirAt(x, y + 1, z)) return false;
        if (isVoidFloorUnder(x, y, z)) return false;
        if (isAirAt(x, y, z) && isAirAt(x, y - 1, z)) return false;
        return true;
    }

    // (nx, cy, nz)의 air/wall 판정을 호출부에서 미리 구해 넘겨받아 중복 조회를 피한다
    static int resolveTargetY(int nx, int cy, int nz, boolean baseIsAir, boolean baseIsWall,
                              boolean fromHasBlockAt2Meter, int bottomY) {
        if (!isAirAt(nx, cy + 1, nz)) return Integer.MIN_VALUE;
        if (!baseIsAir) {
            if (baseIsWall) return Integer.MIN_VALUE;
            if (isAirAt(nx, cy + 2, nz) && !fromHasBlockAt2Meter) return cy + 1;
            return Integer.MIN_VALUE;
        } else if (isAirAt(nx, cy - 1, nz)) {
            int fy = cy;
            int dropped = 0;
            while (isAirAt(nx, fy - 1, nz) && fy > bottomY) {
                fy--;
                if (++dropped >= MAX_ANCHOR_DROP_SEARCH) {
                    return Integer.MIN_VALUE;
                }
            }
            return fy;
        } else {
            return cy;
        }
    }

    // 폭 판정 결과: OPEN/NARROW는 sentinel 값이고, 그 외 값은 판정 불가 사유가 된 미로딩 청크의
    // 월드 좌표(packWorldXZ로 패킹)다. 호출부(FrontierSearch)가 이 좌표로 FrontierQueue.park를
    // 걸어 그 청크가 로딩될 때까지 현재 셀 탐색을 보류한다. 반환값 자체에 좌표를 실어 static
    // 필드로 넘기지 않으므로, 호출 사이 순서에 의존하는 숨은 계약이 없다.
    //
    // sentinel은 packWorldXZ(0, Integer.MIN_VALUE) 계열을 쓴다. nx/nz는 항상 이미 로딩된 청크
    // 안의 좌표(플레이어 주변, 기본 월드 보더 ±30,000,000 이내)에서 유래하므로 z가
    // Integer.MIN_VALUE에 도달할 수 없어 실제 좌표와 절대 충돌하지 않는다.
    private static long packWorldXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
    static final long PASSAGE_OPEN = packWorldXZ(0, Integer.MIN_VALUE);
    static final long PASSAGE_NARROW = packWorldXZ(0, Integer.MIN_VALUE + 1);

    private static final int LATERAL_OPEN = 0;
    private static final int LATERAL_BLOCKED = 1;
    private static final int LATERAL_UNKNOWN = 2;

    // 폭 판정 전용. loaded를 호출부가 이미 알고 있을 때(루프에서 y-불변인 로딩 여부를 미리 구해둔 경우) 재확인을 피한다
    private static int lateralStateKnownLoaded(boolean loaded, int x, int y, int z) {
        if (!loaded) return LATERAL_UNKNOWN;
        return isAirAt(x, y, z) ? LATERAL_OPEN : LATERAL_BLOCKED;
    }

    // 한 축(양쪽 측면 x1,z1 / x2,z2)의 폭 판정. 로딩 여부는 호출부가 넘겨준 값을 그대로 쓴다
    private static long axisResult(boolean loaded1, int x1, int z1, boolean loaded2, int x2, int z2, int y) {
        int s1 = lateralStateKnownLoaded(loaded1, x1, y, z1);
        // 한쪽이 열려 있으면 나머지가 미로딩이어도 결론(안 좁음)은 안 바뀐다. 굳이 확인 안 해도 됨
        if (s1 == LATERAL_OPEN) return PASSAGE_OPEN;
        int s2 = lateralStateKnownLoaded(loaded2, x2, y, z2);
        if (s2 == LATERAL_OPEN) return PASSAGE_OPEN;
        if (s1 == LATERAL_UNKNOWN) return packWorldXZ(x1, z1);
        if (s2 == LATERAL_UNKNOWN) return packWorldXZ(x2, z2);
        return PASSAGE_NARROW;
    }

    static long isNarrowPassage(int nx, int ny, int nz, int dx, int dz) {
        int x1, z1, x2, z2;
        if (dx != 0) {
            x1 = nx; z1 = nz - 1;
            x2 = nx; z2 = nz + 1;
        } else {
            x1 = nx - 1; z1 = nz;
            x2 = nx + 1; z2 = nz;
        }
        return axisResult(isChunkLoadedAt(x1, z1), x1, z1, isChunkLoadedAt(x2, z2), x2, z2, ny);
    }

    static long isNarrowPassageInRange(int nx, int cy, int ty, int nz, int dx, int dz) {
        if (ty >= cy) {
            return isNarrowPassage(nx, ty, nz, dx, dz);
        }
        // 청크 로딩 여부는 y와 무관하므로 루프 밖에서 축당 한 번만 판정한다
        // (예전엔 y 레벨마다 매번 재조회해 미로딩 경계에서 낙하 깊이만큼 헛조회가 반복됐다)
        boolean zMinusLoaded = isChunkLoadedAt(nx, nz - 1);
        boolean zPlusLoaded = isChunkLoadedAt(nx, nz + 1);
        boolean xMinusLoaded = isChunkLoadedAt(nx - 1, nz);
        boolean xPlusLoaded = isChunkLoadedAt(nx + 1, nz);

        boolean sawUnknown = false;
        long unknownResult = 0;
        for (int y = cy; y >= ty; y--) {
            // 낙하 중엔 동서남북 다봐야함. 확정적 NARROW를 찾으면 즉시 반환하되
            // 그 전까지 만난 UNKNOWN은 기억해뒀다가 NARROW를 못 찾고 끝나면 UNKNOWN으로 반환한다.
            long r1 = axisResult(zMinusLoaded, nx, nz - 1, zPlusLoaded, nx, nz + 1, y);
            if (r1 == PASSAGE_NARROW) return PASSAGE_NARROW;
            if (r1 != PASSAGE_OPEN && !sawUnknown) { sawUnknown = true; unknownResult = r1; }

            long r2 = axisResult(xMinusLoaded, nx - 1, nz, xPlusLoaded, nx + 1, nz, y);
            if (r2 == PASSAGE_NARROW) return PASSAGE_NARROW;
            if (r2 != PASSAGE_OPEN && !sawUnknown) { sawUnknown = true; unknownResult = r2; }
        }
        return sawUnknown ? unknownResult : PASSAGE_OPEN;
    }

    // air 여부를 먼저 보고, air가 아닐 때만 wall을 본다(순서를 한 곳에 고정해 호출부 간 비대칭을 방지).
    // 순서가 반대면 한 블록이 air/wall 태그를 동시에 갖는 경우(설정 오류 등) 호출부마다 다른 결론이 날 수 있다.
    static boolean isWallGivenAir(boolean isAir, int x, int y, int z) {
        return !isAir && isWallAt(x, y, z);
    }

    static boolean canMoveBetween(int tx, int ty, int tz, int fx, int fy, int fz, int bottomY) {
        boolean baseIsAir = isAirAt(fx, ty, fz);
        boolean baseIsWall = isWallGivenAir(baseIsAir, fx, ty, fz); // 역방향 못 감
        if (baseIsWall) return false;
        boolean tHasBlockAt2 = !isAirAt(tx, ty + 2, tz);
        int back = resolveTargetY(fx, ty, fz, baseIsAir, baseIsWall, tHasBlockAt2, bottomY);
        return back == fy;
    }
}
