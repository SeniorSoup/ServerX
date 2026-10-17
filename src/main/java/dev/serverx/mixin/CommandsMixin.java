package dev.serverx.mixin;

import com.mojang.brigadier.ParseResults;
import dev.serverx.ServerX;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Imprisoned players cannot run any command. */
@Mixin(Commands.class)
public abstract class CommandsMixin {

	@Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
	private void serverx$blockJailed(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
		CommandSourceStack source = parse.getContext().getSource();
		if (source.getEntity() instanceof ServerPlayer player && ServerX.data().isJailed(player.getUUID())) {
			player.sendSystemMessage(Component.literal("You are in prison and cannot use commands.").withStyle(ChatFormatting.RED));
			ci.cancel();
		}
	}
}
