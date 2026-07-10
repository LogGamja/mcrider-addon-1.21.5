package loggamja.mcrider.minimap;

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

// narrow 통로 판정 규칙 (isNarrowPassage / isNarrowPassageInRange)
// 규칙1: 마주보는 두 방향 중 하나라도 open이면 통로는 open, 둘 다 막히면 narrow
// 규칙2: 한 방향이 open이려면 현재 칸과 뒷칸이 둘 다 air여야 한다
// 규칙3: 규칙2의 판정을 이 행뿐 아니라 한 칸 아래 행에도 적용해야 open이다
// 규칙4: rear(규칙2)는 가로 이동을 밟은 진입 행(cy)에서만 쓴다

final class BlockSearch {
    private BlockSearch() {}

    private static final TagKey<Block> KART_WALL = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "stones"));
    private static final Predicate<BlockState> isWall = state -> state.isIn(KART_WALL);
    private static final TagKey<Block> KART_AIR = TagKey.of(RegistryKeys.BLOCK, Identifier.of("kartmobil", "ignoreblock"));
    private static final Predicate<BlockState> isAir = state -> state.isIn(KART_AIR);
    private static final Predicate<BlockState> isVoid = state -> state.isOf(Blocks.STRUCTURE_VOID);

    static final int[][] DIRECTIONS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
    static final int MAX_ANCHOR_DROP_SEARCH = 16;

    private static final LongOpenHashSet fakeBlocks = new LongOpenHashSet();

    private static final int CHUNK_CACHE_SLOTS = 4;
    private static final long[] cacheKeys = new long[CHUNK_CACHE_SLOTS];
    private static final Chunk[] cacheChunks = new Chunk[CHUNK_CACHE_SLOTS];
    private static final BlockPos.Mutable MUTABLE = new BlockPos.Mutable();

    // 좌표 두 개를 long 하나로 압축 (narrow/가짜 블록 판정에서 "미확정 + 로딩 필요한 청크" 반환용)
    private static long packWorldXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
    // OPEN/NARROW/ALL_LATERAL_LOADED는 sentinel. 그 외 값은 미로딩 청크 좌표
    static final long PASSAGE_OPEN = packWorldXZ(0, Integer.MIN_VALUE);
    static final long PASSAGE_NARROW = packWorldXZ(0, Integer.MIN_VALUE + 1);
    static final long ALL_LATERAL_LOADED = packWorldXZ(0, Integer.MIN_VALUE + 2);

    // LATERAL_OPEN/BLOCKED는 sentinel. 그 외 값(threeStateIfLoaded가 만드는)은 "이 칸이 속한
    // 미로딩 청크 좌표" 그 자체다 — 어떤 구체적인 칸이 미확정의 원인인지 축(axisResult)까지 그대로
    // 들고 올라가기 위함이다 (park를 실제로 안 로딩된 청크에 걸기 위해 필요, 아래 axisResult 참고).
    private static final long LATERAL_OPEN = packWorldXZ(0, Integer.MIN_VALUE + 3);
    private static final long LATERAL_BLOCKED = packWorldXZ(0, Integer.MIN_VALUE + 4);

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
    static boolean isChunkLoadedAt(int x, int z) {
        if (MCRiderMinimap.client.world == null) return false;
        long key = ChunkPos.toLong(x >> 4, z >> 4);
        for (int i = 0; i < CHUNK_CACHE_SLOTS; i++) {
            if (cacheKeys[i] == key && cacheChunks[i] != null) return true;
        }
        return MCRiderMinimap.client.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }
    static boolean isAirAt(int x, int y, int z) {
        // 가짜 블록이 하나도 없으면(대부분의 틱/깨끗한 트랙) asLong·해시 조회를 건너뛴다.
        if (!fakeBlocks.isEmpty() && fakeBlocks.contains(BlockPos.asLong(x, y, z))) return false;
        return isBlockAt(isAir, x, y, z);
    }
    static boolean isStandableAt(int x, int y, int z, boolean headAlreadyChecked) {
        if (!headAlreadyChecked && !isAirAt(x, y + 1, z)) return false;
        if (isVoidFloorUnder(x, y, z)) return false;
        if (isAirAt(x, y, z) && isAirAt(x, y - 1, z)) return false;
        return true;
    }
    // twoWay(양방향 연결) 판정: 목적지에서 출발지로 되짚어가도 같은 fy로 돌아오는지 확인
    static boolean canMoveBetween(int tx, int ty, int tz, int fx, int fy, int fz, int bottomY) {
        boolean baseIsAir = isAirAt(fx, ty, fz);
        boolean baseIsWall = isWallIfNotAir(baseIsAir, fx, ty, fz);
        if (baseIsWall) return false;
        boolean tHasBlockAt2 = !isAirAt(tx, ty + 2, tz);
        int back = resolveTargetY(fx, ty, fz, baseIsAir, baseIsWall, tHasBlockAt2, bottomY);
        return back == fy;
    }
    // air 먼저 검사 (tag 중복 시 일관성 유지)
    static boolean isWallIfNotAir(boolean baseIsAir, int x, int y, int z) {
        return !baseIsAir && isBlockAt(isWall, x, y, z);
    }
    // (nx, cy, nz)로 이동 시 실제 착지 y를 계산 (도달 불가면 Integer.MIN_VALUE)
    static int resolveTargetY(int nx, int cy, int nz, boolean baseIsAir, boolean baseIsWall, boolean fromHasBlockAt2Meter, int bottomY) {
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

    static void addFakeBlock(int x, int y, int z) {
        fakeBlocks.add(BlockPos.asLong(x, y, z));
    }
    static void clearFakeBlocks() {
        fakeBlocks.clear();
    }

    // 고립 구덩이 판정: 수평 4면 중 3면 이상 막힘, 또는 진행 방향(dx,dz) 앞뒤가 모두 막히면 true
    static boolean isIsolatedPit(int nx, int ty, int nz, int dx, int dz) {
        int blockedSides = 0;
        boolean frontBlocked = false, backBlocked = false;
        for (int[] pd : DIRECTIONS) {
            if (!isAirAt(nx + pd[0], ty, nz + pd[1])) {
                blockedSides++;
                if (pd[0] == dx && pd[1] == dz) frontBlocked = true;
                else if (pd[0] == -dx && pd[1] == -dz) backBlocked = true;
            }
        }
        return blockedSides >= 3 || (frontBlocked && backBlocked);
    }
    // isIsolatedPit이 살펴볼 4면의 청크가 전부 로딩됐는지 확인 (미로딩 시 그 좌표 반환)
    static long firstUnloadedLateralChunk(int nx, int nz) {
        for (int[] pd : DIRECTIONS) {
            int ux = nx + pd[0], uz = nz + pd[1];
            if (!isChunkLoadedAt(ux, uz)) return packWorldXZ(ux, uz);
        }
        return ALL_LATERAL_LOADED;
    }
    // 같은 높이에서의 narrow 판정 (규칙1)
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

        int centerChunkX = nx >> 4, centerChunkZ = nz >> 4;
        boolean centerLoaded = isChunkLoadedAt(nx, nz);
        return axisResult(
                isChunkLoadedNear(x1, z1, centerChunkX, centerChunkZ, centerLoaded), x1, z1,
                isChunkLoadedNear(x1r, z1r, centerChunkX, centerChunkZ, centerLoaded), x1r, z1r,
                isChunkLoadedNear(x2, z2, centerChunkX, centerChunkZ, centerLoaded), x2, z2,
                isChunkLoadedNear(x2r, z2r, centerChunkX, centerChunkZ, centerLoaded), x2r, z2r,
                true, ny, ny);
    }
    
    private static boolean isChunkLoadedNear(int x, int z, int centerChunkX, int centerChunkZ, boolean centerLoaded) {
        return (x >> 4) == centerChunkX && (z >> 4) == centerChunkZ ? centerLoaded : isChunkLoadedAt(x, z);
    }
    // 낙하 경로(cy→ty) 전체에 규칙 적용. 플레이어가 1칸은 못 들어간다고 가정
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

        // 청크 로드 여부 캐시
        int centerChunkX = nx >> 4, centerChunkZ = nz >> 4;
        boolean centerLoaded = isChunkLoadedAt(nx, nz);
        boolean zMinusLoaded = isChunkLoadedNear(x1, z1, centerChunkX, centerChunkZ, centerLoaded);
        boolean zPlusLoaded = isChunkLoadedNear(x2, z2, centerChunkX, centerChunkZ, centerLoaded);
        boolean xMinusLoaded = isChunkLoadedNear(x3, z3, centerChunkX, centerChunkZ, centerLoaded);
        boolean xPlusLoaded = isChunkLoadedNear(x4, z4, centerChunkX, centerChunkZ, centerLoaded);
        boolean zMinusRearLoaded = isChunkLoadedNear(x1r, z1r, centerChunkX, centerChunkZ, centerLoaded);
        boolean zPlusRearLoaded = isChunkLoadedNear(x2r, z2r, centerChunkX, centerChunkZ, centerLoaded);
        boolean xMinusRearLoaded = isChunkLoadedNear(x3r, z3r, centerChunkX, centerChunkZ, centerLoaded);
        boolean xPlusRearLoaded = isChunkLoadedNear(x4r, z4r, centerChunkX, centerChunkZ, centerLoaded);

        boolean sawUnknown = false;
        long unknownResult = 0;
        for (int y = cy; y >= ty; y--) {
            boolean useRear = (y == cy);
            int belowY = Math.max(y - 1, ty);

            long r1 = axisResult(zMinusLoaded, x1, z1, zMinusRearLoaded, x1r, z1r,
                    zPlusLoaded, x2, z2, zPlusRearLoaded, x2r, z2r, useRear, y, belowY);
            long r2 = axisResult(xMinusLoaded, x3, z3, xMinusRearLoaded, x3r, z3r,
                    xPlusLoaded, x4, z4, xPlusRearLoaded, x4r, z4r, useRear, y, belowY);

            // 낙하 중 NARROW 발견 시 즉시 반환. 그 전까지 UNKNOWN 기억했다가 반환
            if (r1 == PASSAGE_NARROW) return PASSAGE_NARROW;
            if (r1 != PASSAGE_OPEN && !sawUnknown) { sawUnknown = true; unknownResult = r1; }

            if (r2 == PASSAGE_NARROW) return PASSAGE_NARROW;
            if (r2 != PASSAGE_OPEN && !sawUnknown) { sawUnknown = true; unknownResult = r2; }
        }
        return sawUnknown ? unknownResult : PASSAGE_OPEN;
    }

    // narrow 판정 체인: axisResult(규칙1), checkTwoSideOpen(규칙3), checkOneSideOpen(규칙2), threeStateIfLoaded 체인 전체가 long을 주고받는다
    // OPEN/BLOCKED가 아니면 그 값 자체가 "미확정 원인인 실제 미로딩 청크 좌표"이기 때문
    // side1이 OPEN이 아니면 그대로 반환하기만 하면 되므로 axisResult에서 좌표를 다시 조립할 필요가 없다

    // 규칙1
    private static long axisResult(boolean loaded1, int x1, int z1, boolean loaded1r, int x1r, int z1r,
                                    boolean loaded2, int x2, int z2, boolean loaded2r, int x2r, int z2r,
                                    boolean useRear, int y, int belowY) {
        long s1 = checkTwoSideOpen(loaded1, x1, z1, loaded1r, x1r, z1r, useRear, y, belowY);
        if (s1 == LATERAL_OPEN) return PASSAGE_OPEN;
        long s2 = checkTwoSideOpen(loaded2, x2, z2, loaded2r, x2r, z2r, useRear, y, belowY);
        if (s2 == LATERAL_OPEN) return PASSAGE_OPEN;
        if (s1 != LATERAL_BLOCKED) return s1;
        if (s2 != LATERAL_BLOCKED) return s2;
        return PASSAGE_NARROW;
    }
    private static long checkTwoSideOpen(boolean directLoaded, int dx, int dz,
                                        boolean rearLoaded, int rx, int rz,
                                        boolean useRear, int y, int belowY) {
        long here = useRear
                ? checkOneSideOpen(directLoaded, dx, dz, rearLoaded, rx, rz, y)
                : threeStateIfLoaded(directLoaded, dx, y, dz);
        if (here == LATERAL_BLOCKED) return LATERAL_BLOCKED;
        if (belowY == y) return here;
        long below = threeStateIfLoaded(directLoaded, dx, belowY, dz);
        if (below == LATERAL_BLOCKED) return LATERAL_BLOCKED;
        if (here != LATERAL_OPEN) return here;
        if (below != LATERAL_OPEN) return below;
        return LATERAL_OPEN;
    }
    private static long checkOneSideOpen(boolean directLoaded, int dx, int dz,
                                        boolean rearLoaded, int rx, int rz, int y) {
        long direct = threeStateIfLoaded(directLoaded, dx, y, dz);
        if (direct == LATERAL_BLOCKED) return LATERAL_BLOCKED;
        long rear = threeStateIfLoaded(rearLoaded, rx, y, rz);
        if (rear == LATERAL_BLOCKED) return LATERAL_BLOCKED;
        if (direct != LATERAL_OPEN) return direct;
        if (rear != LATERAL_OPEN) return rear;
        return LATERAL_OPEN;
    }
    private static long threeStateIfLoaded(boolean loaded, int x, int y, int z) {
        if (!loaded) return packWorldXZ(x, z);
        return isAirAt(x, y, z) ? LATERAL_OPEN : LATERAL_BLOCKED;
    }

    private static boolean isVoidFloorUnder(int x, int y, int z) {
        return isVoidAt(x, y - 1, z);
    }
    private static boolean isVoidAt(int x, int y, int z) {
        return isBlockAt(isVoid, x, y, z);
    }
    private static boolean isBlockAt(Predicate<BlockState> predicate, int x, int y, int z) {
        if (MCRiderMinimap.client.world == null) return false;
        return predicate.test(blockStateAt(x, y, z));
    }
    private static BlockState blockStateAt(int x, int y, int z) {
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
}
