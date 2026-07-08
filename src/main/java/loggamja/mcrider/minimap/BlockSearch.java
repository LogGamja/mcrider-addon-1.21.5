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

// 월드 블록 조회 등
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

    // 청크가 언로드될 때 그 청크만 캐시에서 뽑아낸다. 이게 없으면 언로드된 뒤 재로드된 청크를
    // 여전히 옛 Chunk 객체로 읽어 stale 블록 상태를 반환할 수 있다(ClientChunkEvents.CHUNK_UNLOAD에서 호출)
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

    // 로딩되지 않은 청크를 위한 3상태
    static final int PASSAGE_OPEN = 0;
    static final int PASSAGE_NARROW = 1;
    static final int PASSAGE_UNKNOWN = 2;

    // PASSAGE_UNKNOWN을 반환했을 때 판정 불가 사유가 된 미로딩 청크의 월드 좌표.
    // 호출부(FrontierSearch)가 여기로 FrontierQueue.park(CHUNK_NOT_LOADED)를 걸고 로딩될 때까지 현재 셀 탐색을 보류한다.
    static int lastUnknownChunkX;
    static int lastUnknownChunkZ;

    private static final int LATERAL_OPEN = 0;
    private static final int LATERAL_BLOCKED = 1;
    private static final int LATERAL_UNKNOWN = 2;

    // 폭 판정 전용
    private static int lateralState(int x, int y, int z) {
        if (!isChunkLoadedAt(x, z)) return LATERAL_UNKNOWN;
        return isAirAt(x, y, z) ? LATERAL_OPEN : LATERAL_BLOCKED;
    }

    static int isNarrowPassage(int nx, int ny, int nz, int dx, int dz) {
        int x1, z1, x2, z2;
        if (dx != 0) {
            x1 = nx; z1 = nz - 1;
            x2 = nx; z2 = nz + 1;
        } else {
            x1 = nx - 1; z1 = nz;
            x2 = nx + 1; z2 = nz;
        }
        int s1 = lateralState(x1, ny, z1);
        // 한쪽이 열려 있으면 나머지가 미로딩이어도 결론(안 좁음)은 안 바뀐다. 굳이 확인 안 해도 됨
        if (s1 == LATERAL_OPEN) return PASSAGE_OPEN;
        int s2 = lateralState(x2, ny, z2);
        if (s2 == LATERAL_OPEN) return PASSAGE_OPEN;
        if (s1 == LATERAL_UNKNOWN) { lastUnknownChunkX = x1; lastUnknownChunkZ = z1; return PASSAGE_UNKNOWN; }
        if (s2 == LATERAL_UNKNOWN) { lastUnknownChunkX = x2; lastUnknownChunkZ = z2; return PASSAGE_UNKNOWN; }
        return PASSAGE_NARROW;
    }

    static int isNarrowPassageInRange(int nx, int cy, int ty, int nz, int dx, int dz) {
        if (ty >= cy) {
            return isNarrowPassage(nx, ty, nz, dx, dz);
        }
        boolean sawUnknown = false;
        int unknownX = 0, unknownZ = 0;
        for (int y = cy; y >= ty; y--) {
            // 낙하 중엔 동서남북 다봐야함. 확정적 NARROW를 찾으면 즉시 반환하되
            // 그 전까지 만난 UNKNOWN은 기억해뒀다가 NARROW를 못 찾고 끝나면 UNKNOWN으로 반환한다.
            int r1 = isNarrowPassage(nx, y, nz, 1, 0);
            if (r1 == PASSAGE_NARROW) return PASSAGE_NARROW;
            if (r1 == PASSAGE_UNKNOWN && !sawUnknown) {
                sawUnknown = true;
                unknownX = lastUnknownChunkX;
                unknownZ = lastUnknownChunkZ;
            }
            int r2 = isNarrowPassage(nx, y, nz, 0, 1);
            if (r2 == PASSAGE_NARROW) return PASSAGE_NARROW;
            if (r2 == PASSAGE_UNKNOWN && !sawUnknown) {
                sawUnknown = true;
                unknownX = lastUnknownChunkX;
                unknownZ = lastUnknownChunkZ;
            }
        }
        if (sawUnknown) {
            lastUnknownChunkX = unknownX;
            lastUnknownChunkZ = unknownZ;
            return PASSAGE_UNKNOWN;
        }
        return PASSAGE_OPEN;
    }
    static boolean canMoveBetween(int tx, int ty, int tz, int fx, int fy, int fz, int bottomY) {
        boolean baseIsAir = isAirAt(fx, ty, fz);
        boolean baseIsWall = !baseIsAir && isWallAt(fx, ty, fz); // 역방향 못 감
        if (baseIsWall) return false;
        boolean tHasBlockAt2 = !isAirAt(tx, ty + 2, tz);
        int back = resolveTargetY(fx, ty, fz, baseIsAir, baseIsWall, tHasBlockAt2, bottomY);
        return back == fy;
    }
}
