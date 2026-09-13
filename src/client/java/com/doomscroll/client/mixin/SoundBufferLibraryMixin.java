package com.doomscroll.client.mixin;

import com.doomscroll.Doomscroll;
import com.doomscroll.client.Browsers;
import com.doomscroll.client.CefAudioStream;
import com.doomscroll.client.RemoteTablets;
import com.doomscroll.client.ScreenBrowsers;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** When doomscroll:sounds/screen.ogg or tablet.ogg is requested, serves the live browser PCM stream instead of the .ogg. */
@Mixin(SoundBufferLibrary.class)
public class SoundBufferLibraryMixin {
	private static final Identifier SCREEN = Doomscroll.id("sounds/screen.ogg");
	private static final Identifier TABLET = Doomscroll.id("sounds/tablet.ogg");

	@Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
	private void doomscroll$liveStream(Identifier id, boolean looping, CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
		if (id.equals(SCREEN)) {
			cir.setReturnValue(CompletableFuture.completedFuture(new CefAudioStream(Browsers::getIfPresent)));
		} else if (id.equals(TABLET)) {
			cir.setReturnValue(CompletableFuture.completedFuture(new CefAudioStream(Browsers::getTabletIfPresent)));
		} else if (id.getNamespace().equals(Doomscroll.MOD_ID) && id.getPath().startsWith("sounds/screen/")) {
			final Identifier sid = id;
			cir.setReturnValue(CompletableFuture.completedFuture(new CefAudioStream(() -> ScreenBrowsers.browserForSoundPath(sid))));
		} else if (id.getNamespace().equals(Doomscroll.MOD_ID) && id.getPath().startsWith("sounds/remote/")) {
			final Identifier rid = id;
			cir.setReturnValue(CompletableFuture.completedFuture(new CefAudioStream(() -> RemoteTablets.browserForSoundPath(rid))));
		}
	}
}
