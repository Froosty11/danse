package de.tomalbrc.danse.clienttest.mixin;

import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The test client takes the server's resource pack without the prompt. Test source set only. */
@Mixin(ServerData.class)
public abstract class ServerPackAutoAcceptMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void danse$acceptPacks(CallbackInfo ci) {
        ((ServerData) (Object) this).setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
    }
}
