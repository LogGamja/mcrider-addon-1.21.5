package loggamja.mcsync;

public class EasedValue {

    private double startValue;
    private double targetValue;
    private long startTime = -1;
    private final long durationMs;

    public EasedValue(double initial, long durationMs) {
        this.startValue = initial;
        this.targetValue = initial;
        this.durationMs = durationMs;
    }

    public EasedValue(double initial) {
        this(initial, 200); // 기본 0.2초
    }

    public synchronized void set(double target) {
        this.startValue = get();
        this.targetValue = target;
        this.startTime = System.currentTimeMillis();
    }

    public synchronized double get() {
        if (startTime < 0) return startValue;

        double tau = (double)(System.currentTimeMillis() - startTime) / durationMs;
        tau = Math.min(tau, 1.0);

        double progress;
        if (tau < 0.5) {
            progress = 2 * (tau * tau);
        } else {
            progress = -2 * (tau - 1) * (tau - 1) + 1;
        }

        return startValue + (targetValue - startValue) * progress;
    }
}