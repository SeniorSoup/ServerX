package dev.serverx;

import dev.serverx.ServerXData.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;

/** Region wand, region rules (interaction, break, placement decay) and the delayed-task scheduler. */
public final class ProtectionEvents {
	private static final String WAND_TAG = "serverx_wand";

	/** Pending selection of a player who ran /protect make|edit. */
	private static final class Selection {
		final String regionName;
		String dim;
		BlockPos p1, p2;

		Selection(String regionName) {
			this.regionName = regionName;
		}
	}

	private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
	/** Positions of blocks that are currently decaying (breakable by anyone). */
	private static final Set<String> DECAYING = new HashSet<>();

	private record Task(long at, Runnable run) {}

	private static final List<Task> TASKS = new ArrayList<>();

	private ProtectionEvents() {}

	// ---------- public API used by commands ----------

	public static void startSelection(ServerPlayer player, String regionName) {
		SELECTIONS.put(player.getUUID(), new Selection(regionName));
		ItemStack wand = new ItemStack(Items.GOLDEN_PICKAXE);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(WAND_TAG, true);
		wand.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		wand.set(DataComponents.CUSTOM_NAME,
				Component.literal("ServerX Region Wand").withStyle(ChatFormatting.GOLD));
		if (!player.getInventory().add(wand)) player.drop(wand, false);
		player.sendSystemMessage(Component.literal("Left-click a block for corner 1, right-click a block for corner 2 (region '"
				+ regionName + "').").withStyle(ChatFormatting.YELLOW));
	}

	private static boolean isWand(ItemStack stack) {
		CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
		return cd != null && cd.copyTag().contains(WAND_TAG);
	}

	private static boolean bypass(Player p) {
		return Permissions.check(p, "serverx.bypass", false);
	}

	// ---------- registration ----------

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ProtectionEvents::runTasks);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SELECTIONS.remove(handler.player.getUUID()));

		// Wand: left click = corner 1
		AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			if (hand == InteractionHand.MAIN_HAND && isWand(sp.getItemInHand(hand))) {
				select(sp, pos, true);
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});

		// Wand: right click = corner 2; region rules for everything else
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			ItemStack stack = player.getItemInHand(hand);

			if (isWand(stack)) {
				if (hand == InteractionHand.MAIN_HAND) {
					select(sp, hit.getBlockPos(), false);
					return InteractionResult.SUCCESS;
				}
				return InteractionResult.PASS;
			}

			ServerXData data = ServerX.data();
			if (data.regions.isEmpty() || bypass(sp)) return InteractionResult.PASS;

			ServerLevel sl = (ServerLevel) level;
			String dim = ServerX.dim(level);
			BlockPos clicked = hit.getBlockPos();
			BlockState clickedState = level.getBlockState(clicked);
			Direction face = hit.getDirection();
			BlockPos target = clickedState.canBeReplaced() ? clicked : clicked.relative(face);

			Region clickedRegion = data.findRegion(dim, clicked);
			Region targetRegion = data.findRegion(dim, target);
			if (clickedRegion == null && targetRegion == null) return InteractionResult.PASS;

			// Signs cannot be used; chests, barrels, doors, trapdoors, buttons... can.
			if (clickedRegion != null && clickedState.is(BlockTags.ALL_SIGNS)) {
				deny(sp, "Signs are protected here.");
				return InteractionResult.FAIL;
			}
			if (isGriefItem(stack)) {
				deny(sp, "You cannot use that here.");
				return InteractionResult.FAIL;
			}
			// Block placement inside a region decays.
			if (targetRegion != null && targetRegion.decaySeconds > 0 && stack.getItem() instanceof BlockItem) {
				trackPlacement(sl, target, targetRegion.decaySeconds * 20);
			}
			return InteractionResult.PASS;
		});

		// Buckets aimed at fluids don't go through UseBlockCallback.
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			if (ServerX.data().regions.isEmpty() || bypass(sp)) return InteractionResult.PASS;
			ItemStack stack = player.getItemInHand(hand);
			if (isGriefItem(stack) && ServerX.data().isProtected(level, player.blockPosition())) {
				deny(sp, "You cannot use that here.");
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});

		// Breaking: only decaying blocks may be broken inside a region.
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return true;
			if (!ServerX.data().isProtected(level, pos) || bypass(sp)) return true;
			if (DECAYING.contains(key(level, pos))) return true;
			deny(sp, "This area is protected.");
			return false;
		});
	}

	// ---------- selection ----------

	private static void select(ServerPlayer sp, BlockPos pos, boolean first) {
		Selection sel = SELECTIONS.get(sp.getUUID());
		if (sel == null) {
			sp.sendSystemMessage(Component.literal("Run /protect make <name> first.").withStyle(ChatFormatting.RED));
			return;
		}
		String dim = ServerX.dim(sp.level());
		if (sel.dim != null && !sel.dim.equals(dim)) {
			sel.p1 = null;
			sel.p2 = null;
			sp.sendSystemMessage(Component.literal("Different dimension - selection reset.").withStyle(ChatFormatting.RED));
		}
		sel.dim = dim;
		if (first) sel.p1 = pos.immutable(); else sel.p2 = pos.immutable();
		sp.sendSystemMessage(Component.literal((first ? "Corner 1" : "Corner 2") + " set to "
				+ pos.getX() + ", " + pos.getY() + ", " + pos.getZ()).withStyle(ChatFormatting.AQUA));

		if (sel.p1 != null && sel.p2 != null) {
			ServerXData data = ServerX.data();
			Region r = data.regions.get(sel.regionName);
			boolean created = r == null;
			if (created) {
				r = new Region();
				r.name = sel.regionName;
				data.regions.put(r.name, r);
			}
			r.dim = sel.dim;
			r.setBounds(sel.p1, sel.p2);
			ServerX.save();
			SELECTIONS.remove(sp.getUUID());
			sp.sendSystemMessage(Component.literal("Region '" + r.name + "' " + (created ? "created" : "updated")
					+ " (" + (r.maxX - r.minX + 1) + "x" + (r.maxY - r.minY + 1) + "x" + (r.maxZ - r.minZ + 1) + ").")
					.withStyle(ChatFormatting.GREEN));
		}
	}

	// ---------- decay ----------

	private static void trackPlacement(ServerLevel level, BlockPos target, int decayTicks) {
		BlockState before = level.getBlockState(target);
		BlockPos pos = target.immutable();
		MinecraftServer server = level.getServer();
		// Check next tick whether a block was really placed.
		schedule(server, 1, () -> {
			BlockState placed = level.getBlockState(pos);
			if (placed == before || placed.isAir()) return;
			String k = key(level, pos);
			DECAYING.add(k);
			schedule(server, decayTicks, () -> {
				DECAYING.remove(k);
				if (level.getBlockState(pos).is(placed.getBlock())) {
					// restore water/lava if the block was placed into a fluid, otherwise air
					level.setBlock(pos, before.getFluidState().createLegacyBlock(), 3);
				}
			});
		});
	}

	private static String key(net.minecraft.world.level.Level level, BlockPos pos) {
		return ServerX.dim(level) + "|" + pos.asLong();
	}

	private static boolean isGriefItem(ItemStack s) {
		return s.is(Items.LAVA_BUCKET) || s.is(Items.WATER_BUCKET) || s.is(Items.FLINT_AND_STEEL)
				|| s.is(Items.FIRE_CHARGE) || s.is(Items.END_CRYSTAL);
	}

	private static void deny(ServerPlayer p, String msg) {
		p.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.RED), true);
	}

	// ---------- scheduler ----------

	private static void schedule(MinecraftServer server, int delayTicks, Runnable r) {
		TASKS.add(new Task(server.getTickCount() + delayTicks, r));
	}

	private static void runTasks(MinecraftServer server) {
		if (TASKS.isEmpty()) return;
		long now = server.getTickCount();
		List<Task> due = new ArrayList<>();
		TASKS.removeIf(t -> {
			if (t.at() <= now) {
				due.add(t);
				return true;
			}
			return false;
		});
		for (Task t : due) t.run().run();
	}
}
