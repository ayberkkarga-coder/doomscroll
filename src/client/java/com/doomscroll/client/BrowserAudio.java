package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import org.jetbrains.annotations.Nullable;

/**
 * Manager that plays/stops browser sounds through the Minecraft sound engine.
 * Audio arrives from CEF as PCM (mcef-codec AudioHandler) and is played here as a SoundInstance,
 * so distance, Sound Physics (walls, underwater, reverb) and the sound settings (Blocks slider) apply automatically.
 */
public final class BrowserAudio {
	@Nullable
	private static BrowserSoundInstance tabletSound;
	private static boolean tabletHeld = false;
	private static int tabletRate = 0;
	private static int physicsTick = 0;

	private BrowserAudio() {}

	public static void setTabletHeld(boolean held) {
		tabletHeld = held;
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			stopAll();
			return;
		}
		SoundManager sm = mc.getSoundManager();

		// ---- tablet ----
		CefBrowserView tablet = Browsers.getTabletIfPresent();
		boolean tabletWants = tablet != null && tablet.hasAudioStream() && tabletHeld;
		if (tabletWants) {
			if (tabletSound != null && tabletRate != tablet.audioSampleRate()) {
				sm.stop(tabletSound); // sample rate was corrected: reopen at the right rate
				tabletSound = null;
			}
			if (tabletSound == null || !sm.isActive(tabletSound)) {
				tabletSound = new BrowserSoundInstance(Doomscroll.TABLET_SOUND, null,
						BrowserAudio::volume, () -> Browsers.getTabletIfPresent() != null && tabletHeld);
				tabletRate = tablet.audioSampleRate();
				sm.play(tabletSound);
			}
		} else if (tabletSound != null) {
			sm.stop(tabletSound);
			tabletSound = null;
		}

		// ---- Sound Physics: periodically refresh the environment computation (walls/underwater/reverb) for the long-running sound ----
		if (SoundPhysicsBridge.available() && ++physicsTick % 20 == 0) {
			if (tabletSound != null && sm.isActive(tabletSound)) {
				SoundPhysicsBridge.refresh(tabletSound, mc.player.getEyePosition(), Doomscroll.TABLET_SOUND.location());
			}
		}
	}

	/** Volume of the tablet in hand: the tablet's own slider (independent of the screen volume on the remote). */
	private static float volume() {
		return Browsers.isTabletMuted() ? 0f : Browsers.getTabletVolume();
	}

	public static void stopAll() {
		Minecraft mc = Minecraft.getInstance();
		if (tabletSound != null) {
			mc.getSoundManager().stop(tabletSound);
			tabletSound = null;
		}
	}
}
