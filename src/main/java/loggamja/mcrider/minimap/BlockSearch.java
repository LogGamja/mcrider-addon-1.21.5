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
            // EmptyChunk는 캐시하지 않음 (로드 후 stale 방지)
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

    // OPEN/NARROW는 sentinel. 그 외 값은 미로딩 청크 좌표 (패킹됨)
    private static long packWorldXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
    static final long PASSAGE_OPEN = packWorldXZ(0, Integer.MIN_VALUE);
    static final long PASSAGE_NARROW = packWorldXZ(0, Integer.MIN_VALUE + 1);

    private static final int LATERAL_OPEN = 0;
    private static final int LATERAL_BLOCKED = 1;
    private static final int LATERAL_UNKNOWN = 2;

    private static int lateralStateKnownLoaded(boolean loaded, int x, int y, int z) {
        if (!loaded) return LATERAL_UNKNOWN;
        return isAirAt(x, y, z) ? LATERAL_OPEN : LATERAL_BLOCKED;
    }

    // 한 쪽(좌 또는 우)은 직접 칸과 후방 칸이 "둘 다" air일 때만 open이다.
    // 벽이 한 칸씩 어긋나게(지그재그) 설치된 경우 직접 칸만 보면 open으로 오판하므로,
    // 후방 칸까지 막혀 있으면 그 쪽은 blocked로 취급한다.
    private static int sideState(boolean directLoaded, int dx, int dz,
                                  boolean rearLoaded, int rx, int rz, int y) {
        int direct = lateralStateKnownLoaded(directLoaded, dx, y, dz);
        if (direct == LATERAL_BLOCKED) return LATERAL_BLOCKED;
        int rear = lateralStateKnownLoaded(rearLoaded, rx, y, rz);
        if (rear == LATERAL_BLOCKED) return LATERAL_BLOCKED;
        if (direct == LATERAL_UNKNOWN || rear == LATERAL_UNKNOWN) return LATERAL_UNKNOWN;
        return LATERAL_OPEN;
    }

    // 수직굴에서 벽이 한 칸씩 아래로 어긋나는(지그재그) 경우까지 잡기 위해,
    // 이 행(y)뿐 아니라 한 칸 아래(y-1)도 같은 direct/rear 기준으로 확인해 OR로 묶는다.
    // 청크 로딩 여부는 y와 무관하므로 loaded 플래그는 그대로 재사용한다.
    private static int sideStateWithBelow(boolean directLoaded, int dx, int dz,
                                           boolean rearLoaded, int rx, int rz, int y) {
        int here = sideState(directLoaded, dx, dz, rearLoaded, rx, rz, y);
        if (here == LATERAL_OPEN) return LATERAL_OPEN;
        int below = sideState(directLoaded, dx, dz, rearLoaded, rx, rz, y - 1);
        if (below == LATERAL_OPEN) return LATERAL_OPEN;
        if (here == LATERAL_UNKNOWN || below == LATERAL_UNKNOWN) return LATERAL_UNKNOWN;
        return LATERAL_BLOCKED;
    }

    private static long axisResult(boolean loaded1, int x1, int z1, boolean loaded1r, int x1r, int z1r,
                                    boolean loaded2, int x2, int z2, boolean loaded2r, int x2r, int z2r, int y,
                                    boolean includeBelow) {
        int s1 = includeBelow
                ? sideStateWithBelow(loaded1, x1, z1, loaded1r, x1r, z1r, y)
                : sideState(loaded1, x1, z1, loaded1r, x1r, z1r, y);
        if (s1 == LATERAL_OPEN) return PASSAGE_OPEN;
        int s2 = includeBelow
                ? sideStateWithBelow(loaded2, x2, z2, loaded2r, x2r, z2r, y)
                : sideState(loaded2, x2, z2, loaded2r, x2r, z2r, y);
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
        int x1r = x1 - dx, z1r = z1 - dz;
        int x2r = x2 - dx, z2r = z2 - dz;
        return axisResult(isChunkLoadedAt(x1, z1), x1, z1, isChunkLoadedAt(x1r, z1r), x1r, z1r,
                isChunkLoadedAt(x2, z2), x2, z2, isChunkLoadedAt(x2r, z2r), x2r, z2r, ny, false);
    }

    // 플레이어가 1칸은 못 들어간다고 가정
    static long isNarrowPassageInRange(int nx, int cy, int ty, int nz, int dx, int dz) {
        if (ty >= cy) return isNarrowPassage(nx, ty, nz, dx, dz);

        // 청크 로딩 여부는 y와 무관하므로 루프 밖에서 좌표당 한 번만 판정한다
        int x1 = nx, z1 = nz - 1;
        int x2 = nx, z2 = nz + 1;
        int x3 = nx - 1, z3 = nz;
        int x4 = nx + 1, z4 = nz;
        int x1r = x1 - dx, z1r = z1 - dz;
        int x2r = x2 - dx, z2r = z2 - dz;
        int x3r = x3 - dx, z3r = z3 - dz;
        int x4r = x4 - dx, z4r = z4 - dz;

        boolean zMinusLoaded = isChunkLoadedAt(x1, z1);
        boolean zPlusLoaded = isChunkLoadedAt(x2, z2);
        boolean xMinusLoaded = isChunkLoadedAt(x3, z3);
        boolean xPlusLoaded = isChunkLoadedAt(x4, z4);
        boolean zMinusRearLoaded = isChunkLoadedAt(x1r, z1r);
        boolean zPlusRearLoaded = isChunkLoadedAt(x2r, z2r);
        boolean xMinusRearLoaded = isChunkLoadedAt(x3r, z3r);
        boolean xPlusRearLoaded = isChunkLoadedAt(x4r, z4r);

        boolean sawUnknown = false;
        long unknownResult = 0;
        for (int y = cy; y >= ty; y--) {
            // 낙하 중 NARROW 발견 시 즉시 반환. 그 전까지 UNKNOWN 기억했다가 반환
            long r1 = axisResult(zMinusLoaded, x1, z1, zMinusRearLoaded, x1r, z1r,
                    zPlusLoaded, x2, z2, zPlusRearLoaded, x2r, z2r, y, true);
            if (r1 == PASSAGE_NARROW) return PASSAGE_NARROW;
            if (r1 != PASSAGE_OPEN && !sawUnknown) { sawUnknown = true; unknownResult = r1; }

            long r2 = axisResult(xMinusLoaded, x3, z3, xMinusRearLoaded, x3r, z3r,
                    xPlusLoaded, x4, z4, xPlusRearLoaded, x4r, z4r, y, true);
            if (r2 == PASSAGE_NARROW) return PASSAGE_NARROW;
            if (r2 != PASSAGE_OPEN && !sawUnknown) { sawUnknown = true; unknownResult = r2; }
        }
        return sawUnknown ? unknownResult : PASSAGE_OPEN;
    }

    // air 먼저 검사 (tag 중복 시 일관성 유지)
    static boolean isWallGivenAir(boolean isAir, int x, int y, int z) {
        return !isAir && isWallAt(x, y, z);
    }

    static boolean canMoveBetween(int tx, int ty, int tz, int fx, int fy, int fz, int bottomY) {
        boolean baseIsAir = isAirAt(fx, ty, fz);
        boolean baseIsWall = isWallGivenAir(baseIsAir, fx, ty, fz);
        if (baseIsWall) return false;
        boolean tHasBlockAt2 = !isAirAt(tx, ty + 2, tz);
        int back = resolveTargetY(fx, ty, fz, baseIsAir, baseIsWall, tHasBlockAt2, bottomY);
        return back == fy;
    }
}
