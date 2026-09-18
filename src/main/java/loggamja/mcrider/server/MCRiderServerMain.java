package loggamja.mcrider.server;

import loggamja.mcrider.option.MCRiderConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class MCRiderServerMain implements ModInitializer {

	@Override
	public void onInitialize() {
		// 클라이언트 설정도 여기서 로드된다 (main 엔트리포인트는 client보다 먼저 실행됨)
		MCRiderConfig.INSTANCE.load();

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			if (server.isDedicated()) {
				MCRiderConfig.INSTANCE.load();
			}
			MCRiderAutoSaveDelayState.reset();
		});
	}
}
