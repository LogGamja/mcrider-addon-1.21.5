package loggamja.mcrider.minimap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import java.util.function.Predicate;

/**
 * 월드 블록 조회 전담: 벽/공기/void 태그 판정, 청크 블록 캐시, "서 있을 수 있는 칸"과
 * "두 칸 사이 이동 가능 여부" 판정. 색/방문 상태(cellColor 등)는 전혀 모르는 순수 지형 질의
 */
final class BlockSearch {
    private BlockSearch() {}

    static final TagKey<Block> KART_WALL = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "stones"));
    static final Predicate<BlockState> isWall = state -> state.isIn(KART_WALL);
    static final TagKey<Block> KART_AIR = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "ignoreblock"));
    static final Predicate<BlockState> isAir = state -> state.isIn(KART_AIR);
    private static final Predicate<BlockState> isVoid = state -> state.isOf(Blocks.STRUCTURE_VOID);

    static final int[][] DIRECTIONS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
    static final int MAX_ANCHOR_DROP_SEARCH = 64;

    // 4슬롯을 최근 사용으로 유지하는 move-to-front LRU 청크 캐시.
    private static final int CHUNK_CACHE_SLOTS = 4;
    private static final long[] cacheKeys = new long[CHUNK_CACHE_SLOTS];
    private static final Chunk[] cacheChunks = new Chunk[CHUNK_CACHE_SLOTS];
    private static final BlockPos.Mutable MUTABLE = new BlockPos.Mutable();

    static void invalidateChunkCache() {
        java.util.Arrays.fill(cacheKeys, Long.MIN_VALUE);
        java.util.Arrays.fill(cacheChunks, null);
    }

    private static BlockState stateAt(int x, int y, int z) {
        long key = ChunkPos.toLong(x >> 4, z >> 4);
        for (int i = 0; i < CHUNK_CACHE_SLOTS; i++) {
            if (cacheKeys[i] == key && cacheChunks[i] != null) {
                if (i != 0) {
                    // 히트한 슬롯을 맨 앞으로 옮긴다.
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

        // miss: 가장 오래된 슬롯을 밀어내고 새 청크를 맨 앞에 넣는다.
        System.arraycopy(cacheKeys, 0, cacheKeys, 1, CHUNK_CACHE_SLOTS - 1);
        System.arraycopy(cacheChunks, 0, cacheChunks, 1, CHUNK_CACHE_SLOTS - 1);
        cacheKeys[0] = key;
        cacheChunks[0] = chunk;
        return chunk.getBlockState(MUTABLE.set(x, y, z));
    }

    /** world==null 가드 + 좌표의 BlockState에 predicate 적용을 묶은 헬퍼. */
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
        return MCRiderMinimap.client.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }

    /** 서 있을 수 있는 칸: 머리(y+1) 공간 + void 바닥 아님 + 발밑 지지(y,y-1 둘 다 공기 아님).
     *  headAlreadyChecked=true면 머리 공간 검사를 건너뛴다(평지/계단은 resolveTargetY가 이미
     *  확인했음; 낙하는 착지 높이를 아직 안 봤으므로 반드시 false로 호출). */
    static boolean isStandable(int x, int y, int z, boolean headAlreadyChecked) {
        if (!headAlreadyChecked && !isAirAt(x, y + 1, z)) return false;
        if (isVoidFloorUnder(x, y, z)) return false;
        if (isAirAt(x, y, z) && isAirAt(x, y - 1, z)) return false;
        return true;
    }

    /** (nx,cy,nz)의 air/wall 판정을 호출부에서 미리 구해 넘겨받아 중복 조회를 피한다. */
    static int resolveTargetY(int nx, int cy, int nz, boolean baseIsAir, boolean baseIsWall,
                              boolean fromHasBlockAt2Meter, int bottomY) {
        if (!isAirAt(nx, cy + 1, nz)) return Integer.MIN_VALUE;
        if (!baseIsAir) {
            // 기저가 벽 태그면 절대 올라설 수 없다(단방향 예외 없음).
            if (baseIsWall) return Integer.MIN_VALUE;
            if (isAirAt(nx, cy + 2, nz) && !fromHasBlockAt2Meter) return cy + 1;
            return Integer.MIN_VALUE;
        } else if (isAirAt(nx, cy - 1, nz)) {
            int fy = cy;
            int dropped = 0;
            while (isAirAt(nx, fy - 1, nz) && fy > bottomY) {
                fy--;
                if (++dropped >= MAX_ANCHOR_DROP_SEARCH) return Integer.MIN_VALUE;
            }
            return fy;
        } else {
            return cy;
        }
    }

    // 폭 판정 전용
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
    static boolean isNarrowPassageInRange(int nx, int cy, int ty, int nz, int dx, int dz) {
        if (ty >= cy) {
            return isNarrowPassage(nx, ty, nz, dx, dz);
        }
        for (int y = cy; y >= ty; y--) {
            // 낙하 중엔 이동 방향이 수직이라 두 수평축 모두를 봐야 함
            if (isNarrowPassage(nx, y, nz, 1, 0) || isNarrowPassage(nx, y, nz, 0, 1)) return true;
        }
        return false;
    }

    static boolean canMoveBetween(int tx, int ty, int tz, int fx, int fy, int fz, int bottomY) {
        boolean baseIsWall = isWallAt(fx, ty, fz); // 되돌아갈 칸 기저가 벽이면 역방향 불가
        if (baseIsWall) return false;
        boolean baseIsAir = isAirAt(fx, ty, fz);
        boolean tHasBlockAt2 = !isAirAt(tx, ty + 2, tz);
        int back = resolveTargetY(fx, ty, fz, baseIsAir, baseIsWall, tHasBlockAt2, bottomY);
        return back == fy;
    }
}
