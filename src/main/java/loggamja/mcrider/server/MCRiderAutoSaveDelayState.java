package loggamja.mcrider.server;

import java.util.function.BooleanSupplier;

public final class MCRiderAutoSaveDelayState {

	private static final float COUNTDOWN_SECONDS = 1;
	private static final float SAVE_BLOCK_SECONDS_LIMIT = 300;

	private enum Phase {
		IDLE,
		BLOCKED,
		COUNTING
	}

	// 서버 스레드에서만 사용
	private static Phase phase = Phase.IDLE;
	private static int countdownStartTick = -1;
	private static int blockStartTick = -1;

	private MCRiderAutoSaveDelayState() {}

	public static void reset() {
		phase = Phase.IDLE;
		countdownStartTick = -1;
		blockStartTick = -1;
	}

	public static boolean tryDelayAutosave(BooleanSupplier raceActive, int currentTick, float tickRate) {
		int tickElapsedAfterBlock = currentTick - blockStartTick;
		if (phase != Phase.IDLE && tickElapsedAfterBlock >= toTicks(SAVE_BLOCK_SECONDS_LIMIT, tickRate)) {
			reset();
			return false;
		}

		switch (phase) {
			case BLOCKED:
				if (raceActive.getAsBoolean()) {
					return true;
				}
				phase = Phase.COUNTING;
				countdownStartTick = currentTick;
				return true;
			case COUNTING:
				int tickElapsedAfterRaceEnd = currentTick - countdownStartTick;
				if (tickElapsedAfterRaceEnd >= toTicks(COUNTDOWN_SECONDS, tickRate)) {
					reset();
					return false;
				}
				return true;
			default:
				if (raceActive.getAsBoolean()) {
					phase = Phase.BLOCKED;
					blockStartTick = currentTick;
					return true;
				}
				return false;
		}
	}
	private static int toTicks(float seconds, float tickRate) {
		return Math.max(1, (int) (tickRate * seconds));
	}
}
