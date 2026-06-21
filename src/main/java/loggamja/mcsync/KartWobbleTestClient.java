package loggamja.mcsync; // TODO: 본인 모드 패키지로 변경

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 질량-스프링 감쇠 시스템 테스트용 클라이언트 모드.
 *
 * <p>인게임에서:
 * <ul>
 *   <li><b>G</b> 누르고 있으면 → 왼쪽(-) 외력</li>
 *   <li><b>H</b> 누르고 있으면 → 오른쪽(+) 외력</li>
 * </ul>
 * 키를 떼면 외력이 0이 되고, 스프링이 질량체를 원점으로 끌어당기며 감쇠 진동하는 모습을
 * 매 틱 콘솔(로그)로 확인할 수 있다.
 */
public class KartWobbleTestClient implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("kart-wobble-test");

    // --- 시뮬레이션 파라미터 (앞서 정한 기본값) ---
    private static final double DT         = 0.05; // 마인크래프트 기본 틱 주기 (s)
    private static final double K          = 800;  // 스프링 상수 (N/m)
    private static final double M          = 50;   // 질량 (kg)
    private static final double Q          = 4;    // 공진 팩터
    private static final double PUSH_FORCE = 400;  // G/H 가 주는 외력 크기 (N)
    //   참고: 정상상태 변위 ≈ F/k = 400/800 = 0.5 m, ω0 = sqrt(K/M) = 4 rad/s (주기 약 31틱)

    // 출력 노이즈 억제용: 거의 정지 상태면 로그를 찍지 않는다.
    private static final double REST_EPS = 1.0e-4;

    private static KeyBinding keyLeft;   // G
    private static KeyBinding keyRight;  // H

    // 질량체의 현재 상태(위치/속도). 매 틱 갱신.
    private final MassSpringDamper.State state = new MassSpringDamper.State();

    // 정지 상태에서 막 멈춘 직후 한 번만 "정지" 로그를 찍기 위한 플래그.
    private boolean wasMoving = false;

    @Override
    public void onInitialize() {
        keyLeft = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.kart_wobble_test.push_left",   // 번역 키 (lang 파일에 넣으면 설정 화면에 표시됨)
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.kart_wobble_test"          // 키 설정 카테고리
        ));
        keyRight = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.kart_wobble_test.push_right",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.kart_wobble_test"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick());

        LOGGER.info("[KartWobbleTest] 준비 완료. 인게임에서 G(왼쪽) / H(오른쪽)로 힘을 줘보세요.");
    }

    private void onClientTick() {
        // --- 키 입력 → 외력 합산 ---
        double force = 0.0;
        if (keyLeft.isPressed())  force -= PUSH_FORCE; // G: 왼쪽
        if (keyRight.isPressed()) force += PUSH_FORCE; // H: 오른쪽

        // --- 한 틱 적분 (state 를 제자리 갱신) ---
        MassSpringDamper.step(state, DT, K, M, Q, force);

        // --- 콘솔 출력 ---
        boolean moving = force != 0.0
                || Math.abs(state.x) > REST_EPS
                || Math.abs(state.v) > REST_EPS;

        if (moving) {
            LOGGER.info(String.format(
                    "F=%+7.1f N | x=%+8.4f m | v=%+8.4f m/s",
                    force, state.x, state.v));
            wasMoving = true;
        } else if (wasMoving) {
            // 방금 정지함 → 한 번만 알림
            LOGGER.info("[KartWobbleTest] 정지 (x≈0, v≈0).");
            wasMoving = false;
        }
    }
}
