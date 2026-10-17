package dev.serverx;

import dev.serverx.ServerXData.IpBan;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** IP bans: stored in config/serverx.json, enforced when a player joins. */
public final class IpBanManager {
	private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

	private IpBanManager() {}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer p = handler.player;
			String ip = ipOf(p);
			if (ip == null) return;
			IpBan ban = ServerX.data().ipBans.get(ip);
			if (ban == null) return;
			String name = p.getName().getString();
			if (!ban.names.contains(name)) { // remember alts that tried to join
				ban.names.add(name);
				ServerX.save();
			}
			p.connection.disconnect(banMessage(ban));
		});
	}

	public static String ipOf(ServerPlayer p) {
		SocketAddress a = p.connection.getRemoteAddress();
		if (a instanceof InetSocketAddress isa && isa.getAddress() != null) {
			return isa.getAddress().getHostAddress();
		}
		return null;
	}

	public static boolean looksLikeIp(String s) {
		return IPV4.matcher(s).matches() || (s.contains(":") && s.matches("[0-9a-fA-F:.]+"));
	}

	/** Bans the IP and kicks every online player using it. Returns the number kicked. */
	public static int ban(MinecraftServer server, String ip, String name, String reason, String by) {
		IpBan ban = new IpBan();
		ban.ip = ip;
		ban.reason = reason;
		ban.bannedBy = by;
		ban.created = System.currentTimeMillis();
		if (name != null) ban.names.add(name);
		ServerX.data().ipBans.put(ip, ban);

		int kicked = 0;
		for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
			if (ip.equals(ipOf(p))) {
				String n = p.getName().getString();
				if (!ban.names.contains(n)) ban.names.add(n);
				p.connection.disconnect(banMessage(ban));
				kicked++;
			}
		}
		ServerX.save();
		return kicked;
	}

	/** Unban by IP address or by any name recorded against a banned IP. */
	public static boolean unban(String target) {
		var it = ServerX.data().ipBans.values().iterator();
		while (it.hasNext()) {
			IpBan b = it.next();
			boolean match = b.ip.equals(target) || b.names.stream().anyMatch(n -> n.equalsIgnoreCase(target));
			if (match) {
				it.remove();
				ServerX.save();
				return true;
			}
		}
		return false;
	}

	private static Component banMessage(IpBan ban) {
		return Component.literal("Your IP address is banned from this server."
				+ (ban.reason == null ? "" : "\nReason: " + ban.reason));
	}
}
