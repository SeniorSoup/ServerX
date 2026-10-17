package dev.serverx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Everything ServerX persists (config/serverx.json). */
public class ServerXData {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static class Loc {
		public String dim;
		public double x, y, z;
		public float yaw, pitch;

		public static Loc of(ServerPlayer p) {
			Loc l = new Loc();
			l.dim = ServerX.dim(p.level());
			l.x = p.getX();
			l.y = p.getY();
			l.z = p.getZ();
			l.yaw = p.getYRot();
			l.pitch = p.getXRot();
			return l;
		}
	}

	public static class Region {
		public String name;
		public String dim;
		public int minX, minY, minZ, maxX, maxY, maxZ;
		/** Seconds until a block placed inside vanishes. 0 disables decay. */
		public int decaySeconds = 2;

		public void setBounds(BlockPos a, BlockPos b) {
			minX = Math.min(a.getX(), b.getX());
			minY = Math.min(a.getY(), b.getY());
			minZ = Math.min(a.getZ(), b.getZ());
			maxX = Math.max(a.getX(), b.getX());
			maxY = Math.max(a.getY(), b.getY());
			maxZ = Math.max(a.getZ(), b.getZ());
		}

		public boolean contains(String dimension, BlockPos p) {
			return dim.equals(dimension)
					&& p.getX() >= minX && p.getX() <= maxX
					&& p.getY() >= minY && p.getY() <= maxY
					&& p.getZ() >= minZ && p.getZ() <= maxZ;
		}
	}

	public static class JailRecord {
		public String name;
		/** Epoch millis when the sentence ends, 0 = permanent. */
		public long until;
		public Loc returnLoc;
		/** Pardoned while offline: release them the next time they join. */
		public boolean pendingRelease;
	}

	public static class IpBan {
		public String ip;
		/** Names seen on this IP when it was banned (for display / unbanning by name). */
		public java.util.List<String> names = new java.util.ArrayList<>();
		public String reason;
		public String bannedBy;
		public long created;
	}

	public Map<String, IpBan> ipBans = new LinkedHashMap<>();
	public Map<String, Region> regions = new LinkedHashMap<>();
	public Loc prison;
	public Map<String, JailRecord> jailed = new LinkedHashMap<>();

	// ---------- queries ----------

	public Region findRegion(String dimension, BlockPos pos) {
		for (Region r : regions.values()) {
			if (r.contains(dimension, pos)) return r;
		}
		return null;
	}

	public boolean isProtected(Level level, BlockPos pos) {
		return !regions.isEmpty() && findRegion(ServerX.dim(level), pos) != null;
	}

	public boolean isJailed(UUID id) {
		JailRecord r = jailed.get(id.toString());
		if (r == null || r.pendingRelease) return false;
		return r.until == 0 || System.currentTimeMillis() < r.until;
	}

	// ---------- io ----------

	public static ServerXData load(Path file) {
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				ServerXData d = GSON.fromJson(reader, ServerXData.class);
				if (d != null) {
					if (d.regions == null) d.regions = new LinkedHashMap<>();
					if (d.jailed == null) d.jailed = new LinkedHashMap<>();
					if (d.ipBans == null) d.ipBans = new LinkedHashMap<>();
					return d;
				}
			} catch (Exception e) {
				ServerX.LOG.error("Could not read {}, starting empty", file, e);
			}
		}
		return new ServerXData();
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(this, w);
			}
		} catch (IOException e) {
			ServerX.LOG.error("Could not save {}", file, e);
		}
	}
}
