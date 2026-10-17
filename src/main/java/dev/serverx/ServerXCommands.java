package dev.serverx;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.serverx.ServerXData.JailRecord;
import dev.serverx.ServerXData.Loc;
import dev.serverx.ServerXData.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ServerXCommands {
	private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
	private static final Pattern DURATION = Pattern.compile("(\\d+)([smhdw]?)");

	private static final SuggestionProvider<CommandSourceStack> REGIONS =
			(c, b) -> SharedSuggestionProvider.suggest(ServerX.data().regions.keySet(), b);
	private static final SuggestionProvider<CommandSourceStack> JAILED = (c, b) -> {
		List<String> names = new ArrayList<>();
		for (JailRecord r : ServerX.data().jailed.values()) if (r.name != null) names.add(r.name);
		return SharedSuggestionProvider.suggest(names, b);
	};

	private static final SuggestionProvider<CommandSourceStack> IPBANNED = (c, b) -> {
		List<String> out = new ArrayList<>();
		for (ServerXData.IpBan ban : ServerX.data().ipBans.values()) {
			out.add(ban.ip);
			out.addAll(ban.names);
		}
		return SharedSuggestionProvider.suggest(out, b);
	};

	private ServerXCommands() {}

	// ---------- permissions (LuckPerms through fabric-permissions-api) ----------

	private static boolean isConsole(CommandSourceStack s) {
		return s.getEntity() == null && ("Server".equals(s.getTextName()) || "Rcon".equals(s.getTextName()));
	}

	private static boolean perm(CommandSourceStack s, String node) {
		return isConsole(s) || Permissions.check(s, node, false);
	}

	// ---------- registration ----------

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("cmd")
				.requires(s -> perm(s, "serverx.cmd"))
				.then(Commands.argument("command", StringArgumentType.greedyString())
						.executes(ServerXCommands::runCmd)));

		d.register(Commands.literal("protect")
				.requires(s -> perm(s, "serverx.protect"))
				.executes(ServerXCommands::protectHelp)
				.then(Commands.literal("make")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(ServerXCommands::protectMake)))
				.then(Commands.literal("edit")
						.then(Commands.argument("name", StringArgumentType.word()).suggests(REGIONS)
								.then(Commands.literal("bounds").executes(ServerXCommands::protectEditBounds))
								.then(Commands.literal("decay")
										.then(Commands.argument("seconds", IntegerArgumentType.integer(0, 3600))
												.executes(ServerXCommands::protectEditDecay)))))
				.then(Commands.literal("del")
						.then(Commands.argument("name", StringArgumentType.word()).suggests(REGIONS)
								.executes(ServerXCommands::protectDel)))
				.then(Commands.literal("list").executes(ServerXCommands::protectList))
				.then(Commands.literal("help").executes(ServerXCommands::protectHelp)));

		d.register(Commands.literal("setprison")
				.requires(s -> perm(s, "serverx.setprison"))
				.executes(ServerXCommands::setPrison));

		d.register(Commands.literal("punishban")
				.requires(s -> perm(s, "serverx.punishban"))
				.then(Commands.argument("player", EntityArgument.player())
						.executes(ServerXCommands::punishBan)));

		d.register(Commands.literal("pardonban")
				.requires(s -> perm(s, "serverx.pardonban"))
				.then(Commands.argument("player", StringArgumentType.word()).suggests(JAILED)
						.executes(ServerXCommands::pardonBan)));

		d.register(Commands.literal("ipban")
				.requires(s -> perm(s, "serverx.ipban"))
				.then(Commands.argument("target", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(
								c.getSource().getServer().getPlayerList().getPlayers().stream()
										.map(pl -> pl.getName().getString()).toList(), b))
						.executes(ctx -> ipBan(ctx, ""))
						.then(Commands.argument("reason", StringArgumentType.greedyString())
								.executes(ctx -> ipBan(ctx, StringArgumentType.getString(ctx, "reason"))))));

		d.register(Commands.literal("ipunban")
				.requires(s -> perm(s, "serverx.ipunban"))
				.then(Commands.argument("target", StringArgumentType.word()).suggests(IPBANNED)
						.executes(ServerXCommands::ipUnban)));

		d.register(Commands.literal("ipbanlist")
				.requires(s -> perm(s, "serverx.ipbanlist"))
				.executes(ServerXCommands::ipBanList));

		d.register(Commands.literal("prison")
				.requires(s -> perm(s, "serverx.prison"))
				.then(Commands.argument("player", EntityArgument.player())
						.then(Commands.argument("time", StringArgumentType.word())
								.executes(ServerXCommands::prison))));
	}

	// ---------- /cmd ----------

	private static int runCmd(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String cmd = StringArgumentType.getString(ctx, "command").trim();
		if (cmd.startsWith("/")) cmd = cmd.substring(1);
		MinecraftServer server = src.getServer();

		// Run with console (owner) permissions, but send the output back to whoever ran /cmd.
		CommandSource feedbackTo = src.getEntity() instanceof ServerPlayer p ? p : server;
		CommandSourceStack elevated = server.createCommandSourceStack().withSource(feedbackTo);
		if (src.getEntity() instanceof ServerPlayer p) {
			elevated = elevated.withEntity(p).withPosition(p.position()).withLevel((ServerLevel) p.level());
		}
		ServerX.LOG.info("{} ran /cmd {}", src.getTextName(), cmd);
		server.getCommands().performPrefixedCommand(elevated, cmd);
		return 1;
	}

	// ---------- /protect ----------

	private static int protectHelp(CommandContext<CommandSourceStack> ctx) {
		String[] lines = {
				"/protect make <name> - get a wand, select 2 corners to create a region",
				"/protect edit <name> bounds - get a wand to redefine the corners",
				"/protect edit <name> decay <seconds> - block decay time (0 = off)",
				"/protect del <name> - delete a region",
				"/protect list - list regions",
		};
		for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l).withStyle(ChatFormatting.YELLOW), false);
		return 1;
	}

	private static int protectMake(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name").toLowerCase();
		if (!NAME.matcher(name).matches()) return fail(ctx, "Names may use a-z, 0-9, _ and - (max 32).");
		if (ServerX.data().regions.containsKey(name)) return fail(ctx, "Region exists. Use /protect edit " + name + " bounds.");
		ProtectionEvents.startSelection(p, name);
		return 1;
	}

	private static int protectEditBounds(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name").toLowerCase();
		if (!ServerX.data().regions.containsKey(name)) return fail(ctx, "No region named '" + name + "'.");
		ProtectionEvents.startSelection(p, name);
		return 1;
	}

	private static int protectEditDecay(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "name").toLowerCase();
		Region r = ServerX.data().regions.get(name);
		if (r == null) return fail(ctx, "No region named '" + name + "'.");
		r.decaySeconds = IntegerArgumentType.getInteger(ctx, "seconds");
		ServerX.save();
		return ok(ctx, "Region '" + name + "' decay set to " + r.decaySeconds + "s" + (r.decaySeconds == 0 ? " (off)." : "."));
	}

	private static int protectDel(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "name").toLowerCase();
		if (ServerX.data().regions.remove(name) == null) return fail(ctx, "No region named '" + name + "'.");
		ServerX.save();
		return ok(ctx, "Region '" + name + "' deleted.");
	}

	private static int protectList(CommandContext<CommandSourceStack> ctx) {
		var regions = ServerX.data().regions.values();
		if (regions.isEmpty()) return ok(ctx, "No regions.");
		for (Region r : regions) {
			ok(ctx, r.name + " [" + r.dim + "] " + r.minX + "," + r.minY + "," + r.minZ + " -> "
					+ r.maxX + "," + r.maxY + "," + r.maxZ + " decay=" + r.decaySeconds + "s");
		}
		return regions.size();
	}

	// ---------- prison ----------

	private static int setPrison(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		ServerX.data().prison = Loc.of(p);
		ServerX.save();
		return ok(ctx, "Prison location set to your position.");
	}

	private static int punishBan(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		if (ServerX.data().prison == null) return fail(ctx, "No prison set. Use /setprison first.");
		if (ctx.getSource().getEntity() == target) return fail(ctx, "You cannot imprison yourself.");
		PrisonManager.jail(ctx.getSource().getServer(), target, 0);
		return ok(ctx, target.getName().getString() + " is imprisoned permanently.");
	}

	private static int prison(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		long ms = parseDuration(StringArgumentType.getString(ctx, "time"));
		if (ms <= 0) return fail(ctx, "Invalid time. Examples: 30s, 10m, 2h, 1d, 1h30m (plain number = minutes).");
		if (ServerX.data().prison == null) return fail(ctx, "No prison set. Use /setprison first.");
		if (ctx.getSource().getEntity() == target) return fail(ctx, "You cannot imprison yourself.");
		PrisonManager.jail(ctx.getSource().getServer(), target, ms);
		return ok(ctx, target.getName().getString() + " is imprisoned for " + PrisonManager.format(ms) + ".");
	}

	private static int pardonBan(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		if (!PrisonManager.pardon(ctx.getSource().getServer(), name)) return fail(ctx, name + " is not imprisoned.");
		return ok(ctx, name + " has been pardoned.");
	}

	// ---------- ip bans ----------

	private static int ipBan(CommandContext<CommandSourceStack> ctx, String reason) {
		CommandSourceStack src = ctx.getSource();
		MinecraftServer server = src.getServer();
		String target = StringArgumentType.getString(ctx, "target");

		String ip;
		String name = null;
		if (IpBanManager.looksLikeIp(target)) {
			ip = target;
		} else {
			ServerPlayer online = null;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p.getName().getString().equalsIgnoreCase(target)) online = p;
			}
			if (online == null) return fail(ctx, "'" + target + "' is not online. Give an IP address instead.");
			ip = IpBanManager.ipOf(online);
			name = online.getName().getString();
			if (ip == null) return fail(ctx, "Could not read that player's IP (local connection?).");
		}
		if (src.getEntity() instanceof ServerPlayer self && ip.equals(IpBanManager.ipOf(self))) {
			return fail(ctx, "That is your own IP address.");
		}
		if (ServerX.data().ipBans.containsKey(ip)) return fail(ctx, "That IP is already banned.");

		int kicked = IpBanManager.ban(server, ip, name, reason.isBlank() ? null : reason, src.getTextName());
		return ok(ctx, "IP banned" + (name != null ? " (" + name + ")" : "") + ". Kicked " + kicked + " player(s).");
	}

	private static int ipUnban(CommandContext<CommandSourceStack> ctx) {
		String target = StringArgumentType.getString(ctx, "target");
		if (!IpBanManager.unban(target)) return fail(ctx, "No IP ban matches '" + target + "'.");
		return ok(ctx, "IP ban removed for '" + target + "'.");
	}

	private static int ipBanList(CommandContext<CommandSourceStack> ctx) {
		var bans = ServerX.data().ipBans.values();
		if (bans.isEmpty()) return ok(ctx, "No IP bans.");
		for (ServerXData.IpBan b : bans) {
			ctx.getSource().sendSuccess(() -> Component.literal(b.ip
					+ (b.names.isEmpty() ? "" : " " + b.names)
					+ (b.reason == null ? "" : " - " + b.reason)
					+ " (by " + b.bannedBy + ")"), false);
		}
		return bans.size();
	}

	private static long parseDuration(String s) {
		Matcher m = DURATION.matcher(s.toLowerCase());
		long total = 0;
		int pos = 0;
		while (pos < s.length()) {
			m.region(pos, s.length());
			if (!m.lookingAt()) return -1;
			long n = Long.parseLong(m.group(1));
			total += n * switch (m.group(2)) {
				case "s" -> 1_000L;
				case "h" -> 3_600_000L;
				case "d" -> 86_400_000L;
				case "w" -> 604_800_000L;
				default -> 60_000L; // "m" or bare number
			};
			pos = m.end();
		}
		return total;
	}

	// ---------- helpers ----------

	private static int ok(CommandContext<CommandSourceStack> ctx, String msg) {
		ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
		ctx.getSource().sendFailure(Component.literal(msg));
		return 0;
	}
}
