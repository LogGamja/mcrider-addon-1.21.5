package loggamja.mcsync; // TODO: 본인 모드 패키지로 변경

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
    public static EasedValue rotation = new EasedValue(0.0, 1);
    private static final Logger LOGGER = LoggerFactory.getLogger("kart-wobble-test");

    // --- 시뮬레이션 파라미터 (앞서 정한 기본값) ---
    List<Float> buffer = new ArrayList<>();

    private static float driftAngle = 0;
    private static boolean isDrifting;


    private static final double FREQ  = 1.66; // 공진주파수 (Hz), 이전 ω0=4 와 동일한 거동
    private static final double Q     = 1;    // 공진 팩터
    private static final double PUSH_ACC = 8; // G/H 가 주는 구동 가속도 (m/s²)

    float prevPlayerYaw = 0;

    private static final int DRIFT_FLAG_TICKS = 6; // 0.2초 ÷ 0.05초 = 4틱
    private boolean driftJustStarted = false;      // 드리프트 시작 후 0.2초 동안 true
    private int driftJustStartedTicks = 0;         // 남은 틱 카운터




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
        if (!MCRiderMain.isPlayingInGame()) return;

        var player = MCRiderMain.getRidingPlayer();
        var kart = player.getRootVehicle();

        var playerYaw = player.getYaw();
        buffer.add(Math.abs(prevPlayerYaw - playerYaw));
        if (buffer.size() > 5) buffer.removeFirst();
        prevPlayerYaw = playerYaw;

        var avg = buffer.stream().mapToDouble(Float::floatValue)
                .average()
                .orElse(0.0);

        boolean isDriftingTemp = detectDriftState(kart);
        if (isDriftingTemp != isDrifting) {
            isDrifting = isDriftingTemp;
            if (isDrifting) {


                System.out.println(avg);
                if (avg < 6 && driftJustStartedTicks == 0) {
                    driftJustStartedTicks = DRIFT_FLAG_TICKS;
                }
                else {

                }
            }
            else {
                driftJustStartedTicks = DRIFT_FLAG_TICKS; // 0.2초 펄스 시작
            }
        }

        if (keyLeft.wasPressed()) driftJustStartedTicks = DRIFT_FLAG_TICKS;

        if (isDrifting && avg > 10 && driftJustStartedTicks == 0) {
            driftJustStartedTicks = DRIFT_FLAG_TICKS;
        }

        // 매 틱 실행: 0.2초 펄스 카운트다운
        driftJustStarted = driftJustStartedTicks > 0;
        if (driftJustStartedTicks > 0) driftJustStartedTicks--;

        final double a = (50 * 2 / Math.PI);
        var asdf = a * Math.atan(1 / a * driftAngle);
        if (driftJustStarted) asdf = 0;

        MassSpringDamper.step(state, 0.05, FREQ, Q, -(asdf / 2f));

        var asdf2 = Math.clamp(driftAngle, -90, 90);
        double value = 1.2 * Math.tan(Math.toRadians(asdf2));
        //System.out.println(value);





        rotation.set(Math.toDegrees(state.x));



        List<Entity> passengers = kart.getPassengerList();

        float direction = 0f;
        float datacarrier = 0f;

        for (var i : passengers) {
            if (MCRiderMain.hasCertainName(i, "mcrider-modelsaddle")) {
                for (var j : i.getPassengerList()) {
                    RollManager.setRoll(j.getUuid(), (float) rotation.get(), 1);
                }
            }
            if (MCRiderMain.hasCertainName(i, "mcrider-direction")) {
                direction = i.getYaw();
            }
            if (MCRiderMain.hasCertainName(i, "mcrider-datacarrier")) {
                datacarrier = i.getYaw();
            }
        }
        driftAngle = MathHelper.subtractAngles(direction, datacarrier);


        //LOGGER.info(String.format(
        //        "F=%+7.1f N | x=%+8.4f m | v=%+8.4f m/s",
        //        force, state.x, state.v));
        //wasMoving = true;

    }
    boolean detectDriftState(Entity kart) {
        if (kart.isPlayer()) return false;

        List<Entity> passengers = kart.getPassengerList();
        for (var i : passengers) {
            if (!MCRiderMain.hasCertainName(i, "mcrider-modelsaddle")) continue;

            for (var j : i.getPassengerList()) {
                if (!MCRiderMain.hasCertainName(j, "mcrider-drift-effect") || !isDisplayEntity(j)) continue;

                return ((DisplayEntity) j).getViewRange() > 0;
            }
        }
        return false;
    }
    static boolean isDisplayEntity(Entity entity) {
        return entity.getType() == EntityType.ITEM_DISPLAY
                || entity.getType() == EntityType.BLOCK_DISPLAY
                || entity.getType() == EntityType.TEXT_DISPLAY;
    }
}
