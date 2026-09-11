package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import org.jetbrains.annotations.Nullable;

/**
 * Tarayici seslerini Minecraft ses motorunda calan/durduran yonetici.
 * Ses CEF'ten PCM olarak gelir (mcef-codec AudioHandler), burada SoundInstance olarak calinir;
 * boylece mesafe, Sound Physics (duvar, su alti, yanki) ve ses ayarlari (Bloklar kaydiricisi) otomatik uygulanir.
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
				sm.stop(tabletSound); // ornekleme hizi duzeltildi: dogru hizla yeniden ac
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

		// ---- Sound Physics: uzun suren ses icin ortam hesabini (duvar/su alti/yanki) periyodik yenile ----
		if (SoundPhysicsBridge.available() && ++physicsTick % 20 == 0) {
			if (tabletSound != null && sm.isActive(tabletSound)) {
				SoundPhysicsBridge.refresh(tabletSound, mc.player.getEyePosition(), Doomscroll.TABLET_SOUND.location());
			}
		}
	}

	/** Elindeki tabletin sesi: tabletin kendi kaydiricisi (kumandadaki ekran sesinden bagimsiz). */
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
