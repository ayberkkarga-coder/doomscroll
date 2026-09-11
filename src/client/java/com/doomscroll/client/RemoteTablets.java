package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.cef.api.CefBrowserView;
import com.doomscroll.cef.api.CefService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Diger oyuncularin tabletleri. Sunucudan gelen (adres, dik/yatay) durumu saklanir; tablet gorus alaninda
 * cizildikce o adres icin ayri bir tarayici acilir, sesi oyuncunun konumundan gelir. Bir sure cizilmeyince kapanir.
 */
public final class RemoteTablets {
	private static final long CLOSE_AFTER_NANOS = 10_000_000_000L;

	private static final class Remote {
		String url = "";
		boolean portrait = false;
		float volume = 1f; // sahibinin cihaz sesi (0 = kimse duymaz)
		@Nullable CefBrowserView browser;
		@Nullable CefTexture texture;
		@Nullable Identifier textureId;
		@Nullable BrowserSoundInstance sound;
		long lastRenderNanos = 0L;
		String loadedUrl = "";
		// sahibinin bildirdigi konum (senkron)
		double remoteTime = -1;
		double remoteDuration = 0;
		boolean remotePaused = true;
		long remoteStampMs = 0L;
	}

	private static final Map<UUID, Remote> REMOTES = new HashMap<>();
	/** Debug: gercek oyuncusu olmayan sahte tabletler (zirh askisi testi). */
	public static final java.util.Set<UUID> DEBUG_FAKE = new java.util.HashSet<>();
	public static final UUID FAKE_UUID = Doomscroll.DEBUG_FAKE_OWNER;

	private RemoteTablets() {}

	/** Sunucudan gelen durum. */
	public static void applyState(UUID player, String url, boolean portrait, float volume) {
		Remote r = REMOTES.computeIfAbsent(player, k -> new Remote());
		r.url = url == null ? "" : url;
		r.portrait = portrait;
		r.volume = Math.max(0f, Math.min(1f, volume));
		if (r.browser != null) {
			if (!r.url.isEmpty() && !r.url.equals(r.loadedUrl)) {
				r.loadedUrl = r.url;
				r.browser.getCefBrowser().loadURL(r.url);
			}
			r.browser.resize(portrait ? 720 : 1280, portrait ? 1280 : 644);
		}
	}

	public static boolean isPortrait(UUID player) {
		Remote r = REMOTES.get(player);
		return r != null && r.portrait;
	}

	/**
	 * Renderer: bu oyuncunun tabletinin dokusu. Tarayici yoksa acar (Chromium hazir degilse null).
	 * Cagrildikca "goruluyor" sayilir.
	 */
	@Nullable
	public static Identifier textureFor(UUID player) {
		Remote r = REMOTES.get(player);
		if (r == null || r.url.isEmpty()) {
			return null;
		}
		r.lastRenderNanos = System.nanoTime();
		if (r.browser == null) {
			var init = CefService.initialize();
			if (!init.isDone()) {
				return null;
			}
			try {
				r.browser = init.getFuture().join().createBrowser(r.url, false);
				r.browser.resize(r.portrait ? 720 : 1280, r.portrait ? 1280 : 644);
				r.browser.setFrameRate(20); // elde kucuk gorunur; tam hiz gereksiz
				r.loadedUrl = r.url;
				final Remote ref = r;
				r.texture = new CefTexture(() -> ref.browser);
				r.textureId = Doomscroll.id("remote_tablet/" + player.toString().replace("-", ""));
				Minecraft.getInstance().getTextureManager().register(r.textureId, r.texture);
				// Baskasinin tabletinde ses sadece arka planda/kisik olsun diye ust ust binmesin: sayfa sesi acik, seviye MC'de
				r.browser.getCefBrowser().executeJavaScript(
						"setInterval(function(){document.querySelectorAll('video,audio').forEach(function(m){m.muted=false;m.volume=1;});},2000);",
						r.url, 0);
				Doomscroll.LOGGER.info("uzak tablet tarayicisi acildi: {} -> {}", player, r.url);
			} catch (Exception e) {
				Doomscroll.LOGGER.error("uzak tablet tarayicisi acilamadi", e);
				return null;
			}
		}
		if (r.browser.getTextureView() == null) {
			return null;
		}
		r.texture.update();
		return r.textureId;
	}

	@Nullable
	public static CefBrowserView browserOf(UUID player) {
		Remote r = REMOTES.get(player);
		return r == null ? null : r.browser;
	}

	/** Sahibinin tabletindeki video konumu; izleyen istemci buna hizalanir. */
	public static void applyTime(UUID player, float time, float duration, boolean paused) {
		Remote r = REMOTES.computeIfAbsent(player, k -> new Remote());
		r.remoteTime = time;
		r.remoteDuration = duration;
		r.remotePaused = paused;
		r.remoteStampMs = System.currentTimeMillis();
	}

	/**
	 * Sahibinin bulundugu ana hizala. Sayfa bizde ayri acildigi icin konumu bilmiyoruz; karari sayfaya
	 * birakiriz: video 2.5 sn'den fazla sapmissa oraya atlar, duraklatma durumunu da esler.
	 */
	private static void maybeSync(Remote r) {
		if (r.browser == null || !DoomscrollConfig.get().syncPlayback) {
			return;
		}
		long now = System.currentTimeMillis();
		if (r.remoteStampMs == 0L || now - r.remoteStampMs > 5000L) {
			return;
		}
		if (r.remoteDuration < 30.0 || r.remoteTime < 0) {
			return; // kisa/loop videolarda hizalama yok (Shorts, Reels)
		}
		double target = r.remotePaused ? r.remoteTime : r.remoteTime + (now - r.remoteStampMs) / 1000.0;
		String js = "(function(){var v=document.querySelector('video');if(!v)return;"
				+ "var t=" + String.format(java.util.Locale.ROOT, "%.2f", Math.max(0, target)) + ";"
				+ "if(Math.abs(v.currentTime-t)>2.5){try{v.currentTime=t;}catch(e){}}"
				+ (r.remotePaused ? "if(!v.paused){v.pause();}" : "if(v.paused){v.play().catch(function(){});}")
				+ "})();";
		try {
			r.browser.getCefBrowser().executeJavaScript(js, r.loadedUrl, 0);
		} catch (Exception ignored) {
		}
	}

	private static int syncTick = 0;

	/** Her tick: gorulmeyen tabletleri kapat, sesleri yonet, konumu hizala. */
	public static void tick(Minecraft mc) {
		syncTick++;
		long now = System.nanoTime();
		SoundManager sm = mc.getSoundManager();
		Iterator<Map.Entry<UUID, Remote>> it = REMOTES.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Remote> e = it.next();
			UUID id = e.getKey();
			Remote r = e.getValue();
			if (r.browser == null) {
				continue;
			}
			boolean stale = now - r.lastRenderNanos > CLOSE_AFTER_NANOS;
			Player p = mc.level == null ? null : mc.level.getPlayerByUUID(id);
			boolean fake = DEBUG_FAKE.contains(id);
			if (stale || (p == null && !fake)) {
				closeBrowser(r, sm);
				continue;
			}
			// Baskasinin tableti: kendi ses ayari (kumanda -> Ayarlar). Kapaliysa ses kanali hic acilmaz.
			// Duyulan seviye: sahibinin cihaz sesi x senin "baskalarinin tableti" carpanin
			float level = r.volume * Browsers.getRemoteTabletVolume();
			boolean wantsSound = p != null && r.browser.hasAudioStream() && level > 0.01f;
			if (wantsSound) {
				if (r.sound == null || !sm.isActive(r.sound)) {
					final Player pp = p;
					r.sound = new BrowserSoundInstance(Doomscroll.TABLET_SOUND,
							() -> pp.getEyePosition(),
							() -> r.volume * Browsers.getRemoteTabletVolume(),
							() -> r.browser != null && r.volume * Browsers.getRemoteTabletVolume() > 0.01f,
							Doomscroll.id("remote/" + id.toString().replace("-", "")));
					sm.play(r.sound);
				}
			} else if (r.sound != null) {
				sm.stop(r.sound);
				r.sound = null;
			}

			// Konum senkronu: 2 sn'de bir sahibinin anina hizala
			if (syncTick % 40 == 0) {
				maybeSync(r);
			}
		}
	}

	private static void closeBrowser(Remote r, SoundManager sm) {
		if (r.sound != null) {
			sm.stop(r.sound);
			r.sound = null;
		}
		if (r.browser != null) {
			try {
				r.browser.getCefBrowser().executeJavaScript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();m.src='';});", r.loadedUrl, 0);
				r.browser.close();
			} catch (Exception ignored) {
			}
			r.browser = null;
		}
		r.loadedUrl = "";
	}

	/** Uzak tablet ses akisi: SoundBufferLibraryMixin buradan bulur. */
	@Nullable
	public static CefBrowserView browserForSoundPath(Identifier id) {
		String path = id.getPath(); // sounds/remote/<uuid-hex>.ogg
		int a = path.lastIndexOf('/');
		int b = path.lastIndexOf('.');
		if (a < 0 || b < a) {
			return null;
		}
		String hex = path.substring(a + 1, b);
		for (Map.Entry<UUID, Remote> e : REMOTES.entrySet()) {
			if (e.getKey().toString().replace("-", "").equals(hex)) {
				return e.getValue().browser;
			}
		}
		return null;
	}

	public static void clear() {
		SoundManager sm = Minecraft.getInstance().getSoundManager();
		for (Remote r : REMOTES.values()) {
			closeBrowser(r, sm);
		}
		REMOTES.clear();
	}

	/** Baskasinin tabletine bakarken ses konumu vb. icin yardimci. */
	@Nullable
	public static Vec3 positionOf(Minecraft mc, UUID id) {
		Player p = mc.level == null ? null : mc.level.getPlayerByUUID(id);
		return p == null ? null : p.getEyePosition();
	}
}
