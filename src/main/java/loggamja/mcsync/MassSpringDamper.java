package loggamja.mcsync; // TODO: 본인 모드 패키지로 변경

/**
 * 질량-스프링 감쇠 진동 시스템 (1 자유도).
 *
 * <p>이상 스프링으로 원점에 연결된 질량체의 변위/속도를 한 틱 동안 적분한다.
 * 카트 바디의 흔들림(예: 롤/피치/상하 바운스)을 축마다 독립 인스턴스로 굴리면 된다.
 *
 * <h2>운동 방정식</h2>
 * <pre>
 *   m·x'' + c·x' + k·x = F
 * </pre>
 * 입력이 감쇠 계수 c 가 아니라 공진 팩터 Q 이므로 다음 관계로 변환한다.
 * <pre>
 *   ω0 = sqrt(k/m)            // 고유 각진동수
 *   c/m = ω0 / Q              // 감쇠율 (Q=0.5 가 임계 감쇠, Q>0.5 가 진동 감쇠)
 *   => x'' = F/m - ω0²·x - (ω0/Q)·x'
 * </pre>
 *
 * <h2>수치 적분 — "오일러에 가깝되 발산하지 않는" 설계</h2>
 * <ol>
 *   <li><b>스프링 항: 세미-임플리싯(심플렉틱) 오일러</b> — 위치 갱신에 '새' 속도를 사용한다.
 *       순서만 바꾼 오일러이지만 에너지가 유계로 묶여 발산을 억제한다.</li>
 *   <li><b>감쇠 항: 임플리싯 처리</b> — 속도에 선형이므로 분모로 흡수한다.
 *       분모가 항상 ≥ 1 이라 어떤 dt·Q 에서도 감쇠가 속도를 키우거나 부호를 뒤집지 못한다.</li>
 *   <li><b>적응형 서브스텝</b> — ω0·subDt 를 안정 한계(≈2.0)보다 한참 작은 값으로 유지한다.
 *       빳빳한 스프링(k↑)·작은 질량(m↓)·높은 Q 에서도 안정성을 보장한다.</li>
 * </ol>
 * 마지막으로 NaN/오버플로 대비 하드 클램프를 안전망으로 둔다.
 */
public final class MassSpringDamper {

    /** 서브스텝 1회당 허용하는 최대 ω0·dt. 작을수록 정확·안정, 클수록 빠름(안정 한계는 약 2.0). */
    private static final double MAX_OMEGA_DT = 0.4;
    /** 폭주 방지용 서브스텝 상한. */
    private static final int    MAX_SUBSTEPS = 64;
    /** 만일의 발산/오버플로에 대비한 x, v 하드 클램프 한계값. */
    private static final double MAX_STATE    = 1.0e4;
    /** Q 하한(0 또는 음수 방지). 이보다 작으면 과도 감쇠로 클램프. */
    private static final double MIN_Q        = 1.0e-3;

    /** 위치와 속도를 함께 담는 상태 홀더. 매 틱 step()에 넣어 재사용하면 된다. */
    public static final class State {
        public double x; // 변위 (m)
        public double v; // 속도 (m/s)

        public State() { this(0.0, 0.0); }
        public State(double x, double v) { this.x = x; this.v = v; }
    }

    private MassSpringDamper() {}

    /**
     * 한 틱(dt) 동안 시스템을 적분하여 갱신된 변위/속도를 반환한다.
     *
     * @param dt 시간 간격 (s), 마인크래프트 기본 0.05
     * @param k  스프링 상수 (N/m), &gt; 0
     * @param m  질량 (kg), &gt; 0
     * @param Q  공진 팩터, &gt; 0 (클수록 덜 감쇠 = 더 오래 흔들림)
     * @param F  실시간 외력 (N)
     * @param v  현재 속도 (m/s)
     * @param x  현재 변위 (m)
     * @return   갱신된 {@link State}(x, v)
     */
    public static State step(double dt, double k, double m, double Q,
                             double F, double v, double x) {

        // --- 입력 위생 처리: NaN/Infinity 차단 ---
        if (!Double.isFinite(x)) x = 0.0;
        if (!Double.isFinite(v)) v = 0.0;
        if (!Double.isFinite(F)) F = 0.0;

        // --- 비정상 파라미터 가드 ---
        if (m <= 0.0 || k <= 0.0 || dt <= 0.0) {
            return new State(x, v); // 물리적으로 무의미한 입력이면 상태를 그대로 둔다.
        }
        if (Q < MIN_Q) Q = MIN_Q;

        final double omega0   = Math.sqrt(k / m); // 고유 각진동수
        final double omega0Sq = omega0 * omega0;
        final double dampRate = omega0 / Q;       // 감쇠율 (c/m)
        final double accExt   = F / m;            // 외력에 의한 가속도

        // --- 서브스텝 수 결정: ω0·h <= MAX_OMEGA_DT 가 되도록 분할 ---
        int substeps = (int) Math.ceil((omega0 * dt) / MAX_OMEGA_DT);
        if (substeps < 1) substeps = 1;
        if (substeps > MAX_SUBSTEPS) substeps = MAX_SUBSTEPS;
        final double h = dt / substeps;

        final double dampDenom = 1.0 + h * dampRate; // 임플리싯 감쇠 분모 (항상 >= 1)

        for (int i = 0; i < substeps; i++) {
            // 1) 스프링 + 외력에 의한 가속도 (현재 위치 기준)
            final double aSpring = accExt - omega0Sq * x;

            // 2) 속도 갱신: 감쇠는 임플리싯, 나머지는 명시적
            //    v_new = (v + h·aSpring) / (1 + h·dampRate)
            v = (v + h * aSpring) / dampDenom;

            // 3) 위치 갱신: '새' 속도 사용(심플렉틱 오일러) → 에너지 발산 억제
            x = x + h * v;
        }

        // --- 최종 안전망: 혹시 모를 폭주/오버플로 클램프 ---
        if (!Double.isFinite(x)) x = 0.0;
        if (!Double.isFinite(v)) v = 0.0;
        if (x >  MAX_STATE) x =  MAX_STATE;
        if (x < -MAX_STATE) x = -MAX_STATE;
        if (v >  MAX_STATE) v =  MAX_STATE;
        if (v < -MAX_STATE) v = -MAX_STATE;

        return new State(x, v);
    }

    /**
     * 기존 State 객체를 제자리(in-place)로 갱신하는 편의 메서드.
     * 카트마다 State 하나를 보관하고 매 틱 호출하면 가비지 없이 굴릴 수 있다.
     */
    public static void step(State s, double dt, double k, double m, double Q, double F) {
        final State r = step(dt, k, m, Q, F, s.v, s.x);
        s.x = r.x;
        s.v = r.v;
    }
}
