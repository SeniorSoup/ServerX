package dev.serverx;

import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerX implements ModInitializer {
	public static final String ID = "serverx";
	public static final Logger LOG = LoggerFactory.getLogger("ServerX");

	private static ServerXData data = new ServerXData();
	private static Path file;

	public static ServerXData data() {
		return data;
	}

	public static void save() {
		if (file != null) data.save(file);
	}

	/** Dimension id as a string, e.g. "minecraft:overworld". */
	public static String dim(Level level) {
		return level.dimension().identifier().toString();
	}

	@Override
	public void onInitialize() {
		file = FabricLoader.getInstance().getConfigDir().resolve("serverx.json");
		data = ServerXData.load(file);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				ServerXCommands.register(dispatcher));
		ProtectionEvents.register();
		PrisonManager.register();
		IpBanManager.register();

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
		LOG.info("ServerX loaded: {} region(s), prison {}", data.regions.size(),
				data.prison == null ? "not set" : "set");
	}
}
