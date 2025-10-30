package xyz.nightsync.hdskinssupportgravit.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.nightsync.hdskinssupportgravit.client.CapeManager;

@Mixin(AbstractClientPlayer.class)
public abstract class CapeMagicMixin {

    @Inject(method = "getCloakTextureLocation", at = @At("RETURN"), cancellable = true)
    private void nightsync$overrideCape(CallbackInfoReturnable<ResourceLocation> cir) {
        AbstractClientPlayer self = (AbstractClientPlayer)(Object)this;
        GameProfile profile = self.getGameProfile();
        ResourceLocation custom = CapeManager.getCape(profile.getId());
        if (custom != null) {
            cir.setReturnValue(custom);
        }
    }
}
