package com.doomscroll.client.mixin;

import com.doomscroll.client.DirectControl;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void doomscroll$keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (window == this.minecraft.getWindow().handle() && DirectControl.onKey(this.minecraft, action, event)) {
			ci.cancel();
		}
	}

	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void doomscroll$charTyped(long window, CharacterEvent event, CallbackInfo ci) {
		if (window == this.minecraft.getWindow().handle() && DirectControl.onChar(this.minecraft, event)) {
			ci.cancel();
		}
	}
}
