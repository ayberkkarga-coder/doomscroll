package com.doomscroll.client.mixin;

import com.doomscroll.client.DirectControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void doomscroll$onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (window == this.minecraft.getWindow().handle() && DirectControl.onMouseButton(this.minecraft, info, action)) {
			ci.cancel();
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void doomscroll$onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
		if (window == this.minecraft.getWindow().handle() && DirectControl.onScroll(this.minecraft, yOffset)) {
			ci.cancel();
		}
	}
}
