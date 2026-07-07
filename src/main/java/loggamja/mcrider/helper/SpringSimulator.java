package loggamja.mcrider.helper;

// Claude Opus 4.8로 제작된 질량 스프링 물리 계산기
public final class SpringSimulator {

    private static final double MAX_OMEGA_DT = 0.4;
    private static final int    MAX_SUBSTEPS = 64;
    private static final double MAX_STATE    = 1.0e4;
    private static final double MIN_Q        = 1.0e-3;
    private static final double TWO_PI       = 2.0 * Math.PI;

    public static final class SpringState {
        public double x; // 변위 (m)
        public double v; // 속도 (m/s)

        public SpringState() { this(0.0, 0.0); }
        public SpringState(double x, double v) { this.x = x; this.v = v; }
    }

    private SpringSimulator() {}

    // 한 틱(dt) 동안 적분하여 갱신된 변위/속도를 반환한다
    // dt 시간 간격 (s, 마인크래프트 기본 0.05), f 공진주파수(Hz), Q 공진 팩터
    // a 구동 가속도(m/s^2), v 현재 속도(m/s), x 현재 변위(m). 반환값은 갱신된 SpringState(x, v)
    public static SpringState step(double dt, double f, double Q,
                                   double a, double v, double x) {

        // --- 입력 위생 처리 ---
        if (!Double.isFinite(x)) x = 0.0;
        if (!Double.isFinite(v)) v = 0.0;
        if (!Double.isFinite(a)) a = 0.0;

        // --- 파라미터 가드 ---
        if (f <= 0.0 || dt <= 0.0) {
            return new SpringState(x, v); // 복원력이 없으면(주파수 0) 진동도 없음
        }
        if (Q < MIN_Q) Q = MIN_Q;

        final double omega0   = TWO_PI * f;   // 고유 각진동수
        final double omega0Sq = omega0 * omega0;
        final double dampRate = omega0 / Q;   // 감쇠율

        // 서브스텝 분할: omega0*h <= MAX_OMEGA_DT
        int substeps = (int) Math.ceil((omega0 * dt) / MAX_OMEGA_DT);
        if (substeps < 1) substeps = 1;
        if (substeps > MAX_SUBSTEPS) substeps = MAX_SUBSTEPS;
        final double h = dt / substeps;

        final double dampDenom = 1.0 + h * dampRate; // 항상 >= 1

        for (int i = 0; i < substeps; i++) {
            final double aSpring = a - omega0Sq * x;
            v = (v + h * aSpring) / dampDenom;       // 감쇠 임플리싯
            x = x + h * v;                           // 위치는 새 속도로(심플렉틱)
        }

        // --- 안전망 ---
        if (!Double.isFinite(x)) x = 0.0;
        if (!Double.isFinite(v)) v = 0.0;
        if (x >  MAX_STATE) x =  MAX_STATE;
        if (x < -MAX_STATE) x = -MAX_STATE;
        if (v >  MAX_STATE) v =  MAX_STATE;
        if (v < -MAX_STATE) v = -MAX_STATE;

        return new SpringState(x, v);
    }

    // State 를 제자리로 갱신하는 헬퍼 메서드
    public static void step(SpringState s, double dt, double f, double Q, double a) {
        final SpringState r = step(dt, f, Q, a, s.v, s.x);
        s.x = r.x;
        s.v = r.v;
    }
}