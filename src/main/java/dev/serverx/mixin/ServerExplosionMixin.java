package dev.serverx.mixin;

import dev.serverx.ServerX;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Removes blocks inside protected regions from every explosion (creepers, TNT, beds, crystals...). */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "calculateExplodedPositions", at = @At("RETURN"), cancellable = true)
	private void serverx$protectRegions(CallbackInfoReturnable<List<BlockPos>> cir) {
		List<BlockPos> filtered = new ArrayList<>(cir.getReturnValue());
		filtered.removeIf(pos -> ServerX.data().isProtected(this.level, pos));
		cir.setReturnValue(filtered);
	}
}
