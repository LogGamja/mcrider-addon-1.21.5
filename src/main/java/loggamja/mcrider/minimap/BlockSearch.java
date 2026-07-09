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

    // 호출부에서 미리 구한 air/wall 판정 재사용
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

    // 미로딩 이웃은 "열려있지 않음"으로 취급한다. 메인 루프에서 이미 nx,nz 청크 로딩 여부를
    // isChunkLoadedAt으로 확인하고 park 처리하므로, 여기서까지 UNKNOWN을 별도로 추적해 다시
    // park하면 청크 경계(렌더/시뮬레이션 거리 언저리)에서 병합 못지않게 자주 도는 핫패스에
    // exile 큐 등록/해제 왕복이 추가된다. 대신 그냥 막힌 것으로 보고, 청크가 로딩되면
    // 다음 재탐색(예: 플레이어 이동, floodFill 재시도) 때 다시 정상 판정된다.
    private static boolean isLaterallyOpen(int x, int y, int z) {
        if (!isChunkLoadedAt(x, z)) return false;
        return isAirAt(x, y, z);
    }

    static boolean isNarrowPassage(int nx, int ny, int nz, int dx, int dz) {
        if (dx != 0) {
            return !isLaterallyOpen(nx, ny, nz - 1) && !isLaterallyOpen(nx, ny, nz + 1);
        } else {
            return !isLaterallyOpen(nx - 1, ny, nz) && !isLaterallyOpen(nx + 1, ny, nz);
        }
    }

    // 플레이어가 1칸은 못 들어간다고 가정
    static boolean isNarrowPassageInRange(int nx, int cy, int ty, int nz, int dx, int dz) {
        if (ty >= cy) {
            return isNarrowPassage(nx, ty, nz, dx, dz);
        }
        for (int y = cy; y >= ty; y--) {
            // 낙하 중엔 동서남북 다봐야함
            if (isNarrowPassage(nx, y, nz, 1, 0) || isNarrowPassage(nx, y, nz, 0, 1)) return true;
        }
        return false;
    }

    // air 먼저 검사 (tag 중복 시 일관성 유지)
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