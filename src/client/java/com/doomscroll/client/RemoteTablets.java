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
 * Other players' tablets. The state received from the server (URL, portrait/landscape) is stored; while the tablet is drawn
 * in view a separate browser is opened for that URL, and its audio comes from the player's position. It closes after not being drawn for a while.
 */
public final class RemoteTablets {
	private static final long CLOSE_AFTER_NANOS = 10_000_000_000L;

	private static final class Remote {
		String url = "";
		boolean portrait = false;
		float volume = 1f; // the owner's device volume (0 = nobody hears it)
		@Nullable CefBrowserView browser;
		@Nullable CefTexture texture;
		@Nullable Identifier textureId;
		@Nullable BrowserSoundInstance sound;
		long lastRenderNanos = 0L;
		String loadedUrl = "";
		// position reported by the owner (sync)
		double remoteTime = -1;
		double remoteDuration = 0;
		boolean remotePaused = true;
		long remoteStampMs = 0L;
	}

	private static final Map<UUID, Remote> REMOTES = new java.util.concurrent.ConcurrentHashMap<>();
	/** Debug: fake tablets without a real player (armor stand test). */
	public static final java.util.Set<UUID> DEBUG_FAKE = java.util.concurrent.ConcurrentHashMap.newKeySet();
	public static final UUID FAKE_UUID = Doomscroll.DEBUG_FAKE_OWNER;

	private RemoteTablets() {}

	/** State received from the server. */
	public static void applyState(UUID player, String url, boolean portrait, float volume) {
		Remote r = REMOTES.computeIfAbsent(player, k -> new Remote());
		r.url = safeUrl(url);
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

	/**
	 * URL allowed to open on someone else's tablet. The string from the server cannot be handed
	 * to the browser directly: without a scheme check a local file could open via file://, and if the server
	 * rules were bypassed a blocked domain or a local network address could open.
	 */
	private static String safeUrl(@Nullable String url) {
		if (url == null || url.isEmpty()) {
			return "";
		}
		if (url.length() > 2048) {
			return "";
		}
		if (!(url.startsWith("http://") || url.startsWith("https://") || url.startsWith(HomePages.SCHEME))) {
			return "";
		}
		return ServerPolicy.allows(url) ? url : "";
	}

	public static boolean isPortrait(UUID player) {
		Remote r = REMOTES.get(player);
		return r != null && r.portrait;
	}

	/**
	 * Renderer: this player's tablet texture. Opens the browser if there is none (null if Chromium is not ready).
	 * Each call counts as "being seen".
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
				r.browser.setFrameRate(20); // it looks small in hand; full speed is unnecessary
				r.loadedUrl = r.url;
				final Remote ref = r;
				r.texture = new CefTexture(() -> ref.browser);
				r.textureId = Doomscroll.id("remote_tablet/" + player.toString().replace("-", ""));
				Minecraft.getInstance().getTextureManager().register(r.textureId, r.texture);
				// Audio on someone else's tablet should only be background/quiet and must not stack up: page audio unmuted, the level is handled in MC
				r.browser.getCefBrowser().executeJavaScript(
						"setInterval(function(){document.querySelectorAll('video,audio').forEach(function(m){m.muted=false;m.volume=1;});},2000);",
						r.url, 0);
				Doomscroll.LOGGER.info("remote tablet browser opened: {} -> {}", player, r.url);
			} catch (Exception e) {
				Doomscroll.LOGGER.error("remote tablet browser could not be opened", e);
				closeBrowser(r, Minecraft.getInstance().getSoundManager());
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

	/** Video position on the owner's tablet; the viewing client aligns to it. */
	public static void applyTime(UUID player, float time, float duration, boolean paused) {
		Remote r = REMOTES.computeIfAbsent(player, k -> new Remote());
		r.remoteTime = time;
		r.remoteDuration = duration;
		r.remotePaused = paused;
		r.remoteStampMs = System.currentTimeMillis();
	}

	/**
	 * Align to the moment the owner is at. Since the page is opened separately on our side we do not know the position; the decision
	 * is left to the page: if the video has drifted by more than 2.5 s it jumps there, and it matches the paused state too.
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
			return; // no alignment for short/looping videos (Shorts, Reels)
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

	/** Every tick: close tablets that are no longer seen, manage the sounds, align the position. */
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
				it.remove();
				continue;
			}
			// Someone else's tablet: its own volume setting (remote -> Settings). If off, the sound channel is never opened.
			// Heard level: the owner's device volume x your "others' tablet" multiplier
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

			// Position sync: align to the owner's moment every 2 s
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
		if (r.textureId != null) {
			try {
				Minecraft.getInstance().getTextureManager().release(r.textureId);
			} catch (Exception ignored) {
			}
			r.textureId = null;
		}
		r.texture = null;
		r.loadedUrl = "";
	}

	/** Remote tablet audio stream: SoundBufferLibraryMixin looks it up here. */
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

	/** Helper for the audio position etc. while looking at someone else's tablet. */
	@Nullable
	public static Vec3 positionOf(Minecraft mc, UUID id) {
		Player p = mc.level == null ? null : mc.level.getPlayerByUUID(id);
		return p == null ? null : p.getEyePosition();
	}
}
