package loggamja.mcrider.minimap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 구버전(minimap-brute)/신버전(ColorGraph) 미니맵 성능 비교용 디버그 계측기.
 * 두 소스에 동일한 형태로 이식해서, 같은 포맷으로 로그를 남기고 나란히 비교할 수 있게 한다.
 *
 * 사용법:
 *   long t = MCRiderBenchmark.begin();
 *   ... 측정할 구간 ...
 *   MCRiderBenchmark.end("라벨", t, 이번_호출에서_처리한_셀_수);
 *
 * 매 실제 틱마다 onTickEnd(...)를 한 번 호출해주면, DUMP_EVERY_TICKS 틱마다
 * 누적된 라벨별 통계(호출 횟수/평균/95퍼센타일/최댓값/평균 처리 셀 수)를 로그로 요약해서 찍는다.
 *
 * ENABLED=false로 두면 매 호출이 즉시 리턴하므로 프로덕션 빌드에서 끄기만 하면 오버헤드가 사실상 0.
 */
public final class MCRiderBenchmark {
    /** 벤치마크 On/Off. 실제 배포 빌드에서는 false로. */
    public static boolean ENABLED = true;

    private static final int WINDOW = 256;            // 라벨당 표본 링버퍼 크기(p95 계산용)
    private static final int DUMP_EVERY_TICKS = 100;   // 20tps 기준 약 5초마다 요약 로그 1회

    private static final Logger LOGGER = LoggerFactory.getLogger("mcrider-bench");
    private static final Map<String, Metric> METRICS = new HashMap<>();
    private static int tickCounter = 0;

    private MCRiderBenchmark() {}

    /** 측정 구간 시작. ENABLED=false면 0을 리턴(공짜). */
    public static long begin() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** 측정 구간 종료 및 기록. cells는 이번 호출에서 처리한 작업량(셀 수 등, 의미 없으면 0). */
    public static void end(String label, long startNanos, long cells) {
        if (!ENABLED) return;
        long durationNanos = System.nanoTime() - startNanos;
        METRICS.computeIfAbsent(label, Metric::new).record(durationNanos, cells);
    }

    /**
     * 매 "실제" 틱(early return 여부와 무관하게)마다 1회 호출.
     * 내부 틱 카운터가 DUMP_EVERY_TICKS에 도달하면 지금까지 쌓인 통계를 로그로 덤프하고 리셋한다.
     * extraGaugeSupplier는 방문 셀 수/색 개수처럼 "그 순간의 크기"를 나타내는 부가 지표(선택, null 가능).
     */
    public static void onTickEnd(Supplier<Long> extraGaugeSupplier) {
        if (!ENABLED) return;
        tickCounter++;
        if (tickCounter < DUMP_EVERY_TICKS) return;
        tickCounter = 0;

        for (Metric m : METRICS.values()) {
            m.dumpAndReset();
        }
        if (extraGaugeSupplier != null) {
            LOGGER.info("[MCRider-bench] tracked-size={}", extraGaugeSupplier.get());
        }
    }

    private static final class Metric {
        final String name;
        final long[] samplesNanos = new long[WINDOW];
        long callsInWindow = 0;
        long sumNanos = 0;
        long maxNanos = 0;
        long cellsSum = 0;

        Metric(String name) {
            this.name = name;
        }

        void record(long nanos, long cells) {
            samplesNanos[(int) (callsInWindow % WINDOW)] = nanos;
            callsInWindow++;
            sumNanos += nanos;
            cellsSum += cells;
            if (nanos > maxNanos) maxNanos = nanos;
        }

        void dumpAndReset() {
            if (callsInWindow == 0) return;
            int n = (int) Math.min(callsInWindow, WINDOW);
            long[] snapshot = Arrays.copyOf(samplesNanos, n);
            Arrays.sort(snapshot);
            int p95Index = Math.min(n - 1, Math.round(n * 0.95f));

            double avgUs = (sumNanos / (double) callsInWindow) / 1000.0;
            double p95Us = snapshot[p95Index] / 1000.0;
            double maxUs = maxNanos / 1000.0;
            double avgCells = cellsSum / (double) callsInWindow;

            LOGGER.info(String.format(
                    "[MCRider-bench] %-24s calls=%-6d avg=%8.1fus p95=%8.1fus max=%8.1fus avgCells=%.1f",
                    name, callsInWindow, avgUs, p95Us, maxUs, avgCells));

            callsInWindow = 0;
            sumNanos = 0;
            maxNanos = 0;
            cellsSum = 0;
        }
    }
}
