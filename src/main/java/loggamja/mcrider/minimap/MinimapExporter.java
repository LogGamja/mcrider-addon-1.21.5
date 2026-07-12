package loggamja.mcrider.minimap;

import it.unimi.dsi.fastutil.longs.LongIterator;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.texture.NativeImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 시험 기능: 카트 세션(탑승~하차) 동안 방문한 구간 전체를 하나로 이어 붙여 PNG 한 장으로 저장한다.
// 화면에 보이는 512x512 윈도우/활성 경로 필터와 달리 FrontierSearch.visitedColumns(세션 전체 방문 기록)를
// 직접 읽어 그리므로, 낙하 등으로 활성 경로에서 빠져 화면엔 안 보이게 된 구간도 결과 이미지엔 남는다.
final class MinimapExporter {
    private MinimapExporter() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("mcrider");
    private static final DateTimeFormatter FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    // 세션 하나가 다룰 수 있는 최대 변 길이(블록). 비정상적으로 넓은 방문 범위로 인한 메모리 폭증을 막는다.
    private static final int MAX_DIMENSION = 4096;

    // PNG 인코딩/디스크 쓰기가 클라이언트 틱을 막지 않도록 별도 스레드에서 처리한다.
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mcrider-minimap-export");
        thread.setDaemon(true);
        return thread;
    });

    private static String sessionFileStem = null;

    static void startSession() {
        sessionFileStem = LocalDateTime.now().format(FILE_NAME_FORMAT);
    }

    // MCRiderMinimap.clearAllMap()이 방문 기록을 지우기 전에 호출해야 한다.
    static void endSession() {
        String fileStem = sessionFileStem;
        sessionFileStem = null;
        if (fileStem == null) return;

        SessionImage result = buildSessionImage();
        if (result == null) return;

        // 파일명에 이미지 좌상단(px 0,0)이 가리키는 실제 월드 좌표를 남긴다.
        String filename = String.format("%s_x%d_z%d.png", fileStem, result.originX, result.originZ);

        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("mcrider-minimaps");
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            WRITER.submit(() -> {
                try (result.image) {
                    result.image.writeTo(target);
                } catch (IOException e) {
                    LOGGER.error("[MCRider] 미니맵 PNG 저장에 실패했습니다: {}", target, e);
                }
            });
        } catch (IOException e) {
            LOGGER.error("[MCRider] 미니맵 저장 폴더 생성에 실패했습니다.", e);
            result.image.close();
        }
    }

    private record SessionImage(NativeImage image, int originX, int originZ) {}

    // 세션 중 방문한 모든 컬럼의 바운딩 박스를 구해 그 크기의 이미지를 만들고, 컬럼마다
    // (activeSet 필터 없이) 색을 계산해 채운다. clearAllMap() 전에 동기적으로 끝내야 하므로
    // 여기서는 픽셀 버퍼만 만들고, 실제 PNG 인코딩/쓰기는 endSession()에서 비동기로 넘긴다.
    private static SessionImage buildSessionImage() {
        if (FrontierSearch.visitedColumns.isEmpty()) return null;

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        LongIterator keyIt = FrontierSearch.visitedColumns.keySet().iterator();
        while (keyIt.hasNext()) {
            long key = keyIt.nextLong();
            int x = FrontierSearch.unpackColumnX(key);
            int z = FrontierSearch.unpackColumnZ(key);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }

        int width = maxX - minX + 1;
        int height = maxZ - minZ + 1;
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            LOGGER.warn("[MCRider] 세션 방문 범위가 너무 넓어({}x{}) 미니맵 export를 건너뜁니다.", width, height);
            return null;
        }

        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        image.fillRect(0, 0, width, height, 0);

        keyIt = FrontierSearch.visitedColumns.keySet().iterator();
        while (keyIt.hasNext()) {
            long key = keyIt.nextLong();
            int x = FrontierSearch.unpackColumnX(key);
            int z = FrontierSearch.unpackColumnZ(key);
            int color = MinimapRenderer.computeExportColumnColor(x, z);
            if (color == 0) continue;
            image.setColorArgb(x - minX, z - minZ, color);
        }

        return new SessionImage(image, minX, minZ);
    }
}
