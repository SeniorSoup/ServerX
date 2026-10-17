package dev.serverx;

import dev.serverx.ServerXData.JailRecord;
import dev.serverx.ServerXData.Loc;
import java.util.ArrayList;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/** Prison logic: jailing, releasing, confinement and action restrictions. */
public final class PrisonManager {
	/** Prisoners further than this from the prison point are pulled back. */
	private static final double RADIUS = 12.0;

	private PrisonManager() {}

	public static boolean isJailed(Player p) {
		return p instanceof ServerPlayer && ServerX.data().isJailed(p.getUUID());
	}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(server, handler.player));
		ServerTickEvents.END_SERVER_TICK.register(PrisonManager::tick);

		// Prisoners cannot use items, blocks, entities or break anything.
		UseBlockCallback.EVENT.register((player, level, hand, hit) ->
				!level.isClientSide() && isJailed(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) ->
				!level.isClientSide() && isJailed(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				!level.isClientSide() && isJailed(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) ->
				!level.isClientSide() && isJailed(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				!level.isClientSide() && isJailed(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> !isJailed(player));
	}

	// ---------- actions ----------

	/** durationMs <= 0 means permanent. */
	public static void jail(MinecraftServer server, ServerPlayer p, long durationMs) {
		ServerXData d = ServerX.data();
		String key = p.getUUID().toString();
		JailRecord old = d.jailed.get(key);

		JailRecord r = new JailRecord();
		r.name = p.getName().getString();
		r.returnLoc = (old != null && !old.pendingRelease && old.returnLoc != null) ? old.returnLoc : Loc.of(p);
		r.until = durationMs <= 0 ? 0 : System.currentTimeMillis() + durationMs;
		d.jailed.put(key, r);
		ServerX.save();

		teleport(server, p, d.prison);
		p.sendSystemMessage(Component.literal(durationMs <= 0
				? "You have been imprisoned permanently."
				: "You have been imprisoned for " + format(durationMs) + ".").withStyle(ChatFormatting.RED));
	}

	/** Releases a player by name. Returns false if nobody with that name is imprisoned. */
	public static boolean pardon(MinecraftServer server, String name) {
		ServerXData d = ServerX.data();
		for (var e : d.jailed.entrySet()) {
			JailRecord r = e.getValue();
			if (r.name != null && r.name.equalsIgnoreCase(name)) {
				ServerPlayer online = null;
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					if (p.getUUID().toString().equals(e.getKey())) online = p;
				}
				if (online != null) {
					release(server, online, r);
				} else {
					r.pendingRelease = true; // sent back when they next join
					ServerX.save();
				}
				return true;
			}
		}
		return false;
	}

	private static void release(MinecraftServer server, ServerPlayer p, JailRecord r) {
		ServerX.data().jailed.remove(p.getUUID().toString());
		ServerX.save();
		if (r.returnLoc != null) teleport(server, p, r.returnLoc);
		p.sendSystemMessage(Component.literal("You have been released from prison.").withStyle(ChatFormatting.GREEN));
	}

	private static void onJoin(MinecraftServer server, ServerPlayer p) {
		JailRecord r = ServerX.data().jailed.get(p.getUUID().toString());
		if (r == null) return;
		if (!ServerX.data().isJailed(p.getUUID())) {
			release(server, p, r);
		} else {
			teleport(server, p, ServerX.data().prison);
			p.sendSystemMessage(Component.literal("You are still in prison.").withStyle(ChatFormatting.RED));
		}
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) return;
		ServerXData d = ServerX.data();
		if (d.jailed.isEmpty()) return;
		for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
			JailRecord r = d.jailed.get(p.getUUID().toString());
			if (r == null) continue;
			if (!d.isJailed(p.getUUID())) { // sentence over or pardoned
				release(server, p, r);
				continue;
			}
			confine(server, p, d.prison);
		}
	}

	private static void confine(MinecraftServer server, ServerPlayer p, Loc prison) {
		if (prison == null) return;
		boolean sameDim = ServerX.dim(p.level()).equals(prison.dim);
		double dx = p.getX() - prison.x, dy = p.getY() - prison.y, dz = p.getZ() - prison.z;
		if (!sameDim || dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) {
			teleport(server, p, prison);
		}
	}

	private static void teleport(MinecraftServer server, ServerPlayer p, Loc l) {
		if (l == null) return;
		ServerLevel target = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(l.dim)));
		if (target == null) target = server.overworld();
		p.teleportTo(target, l.x, l.y, l.z, Set.of(), l.yaw, l.pitch, true);
	}

	public static String format(long ms) {
		long s = Math.max(1, ms / 1000);
		long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
		StringBuilder sb = new StringBuilder();
		if (d > 0) sb.append(d).append("d ");
		if (h > 0) sb.append(h).append("h ");
		if (m > 0) sb.append(m).append("m ");
		if (sec > 0 && d == 0) sb.append(sec).append("s");
		return sb.toString().trim();
	}
}
