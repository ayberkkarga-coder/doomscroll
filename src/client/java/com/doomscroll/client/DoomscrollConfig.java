package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** User settings: config/doomscroll.json (editable via the remote, /ds commands, or the file itself). */
public final class DoomscrollConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("doomscroll.json");
	private static DoomscrollConfig instance;

	/** Screen browser resolution (also determines the YouTube stream quality). */
	public int screenWidth = 1280;
	public int screenHeight = 720;
	/** Browser audio sample rate; 0 = measure automatically. If the audio sounds too high/low pitched, set 44100 or 48000. */
	public int audioSampleRate = 0;
	/** Shorts/Reels/TikTok: automatically advance to the next video when one ends (or loops back to the start). */
	public boolean autoScroll = true;
	/** Browser identity (User-Agent). A current Chrome identity for Google sign-in; empty = Chromium default. */
	public String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
	/**
	 * Google/YouTube sign-in mode: Google blocks sign-in from embedded browsers ("browser may not be secure"); a Firefox
	 * identity passes that check. While on, the browser identifies as Firefox and the screen opens the Google sign-in page.
	 * Turn it off once signed in; the session cookies remain, so you stay signed in under the normal identity too. Requires a restart.
	 */
	public boolean tvLogin = false;
	/**
	 * Audio latency profile: "dusuk" (low: 20 ms chunk, 40 ms buffer; ~150 ms total), "normal" (25/60; ~180 ms),
	 * "yuksek" (high: 50/100; ~320 ms, safest). On stutter/crackle, step up one level.
	 */
	public String audioLatency = "normal";
	/** Volume of screens you did not place (0 = mute). If the server says "start muted", the session starts at 0. */
	public float othersScreenVolume = 1.0f;
	/**
	 * Run screens in a temporary cookie context, separate from the persistent Chromium profile.
	 * Off by default: when on, a sign-in made on a screen is lost when the game closes.
	 * The server can force it from its own setting; then it applies even if this setting is off.
	 */
	public boolean separateScreenCookies = false;

	/** OpenAL chunk duration of the audio stream (ms). Derived from the profile. */
	public int audioChunkMs() {
		return switch (audioLatency == null ? "normal" : audioLatency) {
			case "dusuk" -> 20;
			case "yuksek" -> 50;
			default -> 25;
		};
	}

	/** Ring buffer target (ms). Derived from the profile. */
	public int audioTargetBacklogMs() {
		return switch (audioLatency == null ? "normal" : audioLatency) {
			case "dusuk" -> 40;
			case "yuksek" -> 100;
			default -> 60;
		};
	}

	/** Digital gain on browser audio (0.5..6). Web videos are quiet compared to game sounds; 1 = raw. Peaks are soft-limited. */
	public float audioBoost = 2.5f;

	/** Show the nearby screen's subtitles on the HUD (even when not looking at the screen). */
	public boolean subtitles = true;

	/** Ad blocking: domain list (requests never go out) + YouTube ad skipper. */
	public boolean adBlock = true;
	/** Screen glow (ambilight): the screen's colors are cast onto nearby surfaces. 0 off, 0.5 low, 1 normal, 1.8 high. */
	public float screenGlow = 1.0f;
	/** Distance the screen glow reaches (blocks). */
	public int screenGlowRange = 10;
	/** Smooth glow: seamless transition between neighboring surfaces (off = block-by-block mosaic). */
	public boolean screenGlowSmooth = true;
	/**
	 * Personal volume levels (0..1) and mute states. Screens (the slider on the remote) and the tablet in your hand
	 * (the tablet's own speaker button) are set separately; both are remembered across game sessions.
	 */
	public float screenVolume = 0.8f;
	public boolean screenMuted = false;
	public float tabletVolume = 0.8f;
	public boolean tabletMuted = false;
	/** Volume of tablets held by other players (0 = mute). Their page opens in your browser; the level is yours. */
	public float remoteTabletVolume = 0.8f;
	/** Remaining fraction of walk/run bobbing while holding the tablet: 0 = steady, 1 = vanilla. */
	public float tabletSway = 0.35f;
	/** Shared pointer: broadcast your crosshair on the screen you are looking at to others, and show theirs. */
	public boolean pointer = true;
	/** Broadcast mode encoding settings: width (16:9), frame rate, video bitrate (kbps). */
	public int broadcastWidth = 960;
	public int broadcastFps = 24;
	public int broadcastKbps = 1200;
	/** Whether the first-launch message has been shown. */
	public boolean welcomeShown = false;

	/** Browser paint frame rate (10..60). The screen being looked at runs at this rate; the others are lowered automatically. */
	public int browserFps = 60;

	/** Align to the controller's video position (so everyone is at the same second when watching long videos/films). */
	public boolean syncPlayback = true;
	/** Channel list (remote: Channel ◀ ▶; /ds kanal ekle|sil|liste). Defaults are used if left empty. */
	public java.util.List<ChannelEntry> channels = null;

	public static final class ChannelEntry {
		public String name = "";
		public String url = "";

		public static ChannelEntry of(String name, String url) {
			ChannelEntry e = new ChannelEntry();
			e.name = name;
			e.url = url;
			return e;
		}
	}

	public static final String TV_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0";

	public static DoomscrollConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(FILE)) {
				instance = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), DoomscrollConfig.class);
			}
		} catch (Exception e) {
			Doomscroll.LOGGER.warn("Could not read doomscroll.json, using defaults", e);
		}
		if (instance == null) {
			instance = new DoomscrollConfig();
			save();
		}
		if (instance.channels == null || instance.channels.isEmpty()) {
			instance.channels = Channels.defaults();
			save();
		}
		instance.browserFps = Math.max(10, Math.min(60, instance.browserFps));
		instance.audioBoost = Math.max(0.5f, Math.min(6f, instance.audioBoost));
		if (!java.util.Set.of("dusuk", "normal", "yuksek").contains(instance.audioLatency)) {
			instance.audioLatency = "normal";
		}
		instance.screenWidth = Math.max(320, Math.min(3840, instance.screenWidth));
		instance.screenGlow = Math.max(0f, Math.min(3f, instance.screenGlow));
		instance.screenGlowRange = Math.max(2, Math.min(24, instance.screenGlowRange));
		instance.tabletSway = Math.max(0f, Math.min(1f, instance.tabletSway));
		instance.screenVolume = Math.max(0f, Math.min(1f, instance.screenVolume));
		instance.tabletVolume = Math.max(0f, Math.min(1f, instance.tabletVolume));
		instance.remoteTabletVolume = Math.max(0f, Math.min(1f, instance.remoteTabletVolume));
		instance.screenHeight = Math.max(180, Math.min(2160, instance.screenHeight));
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(get()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Doomscroll.LOGGER.warn("Could not write doomscroll.json", e);
		}
	}

	/** Screen resolution label: 720p / 1080p / 1440p or WxH. */
	/**
	 * Maximum YouTube quality for the screen resolution (player level name): anything above what the screen
	 * can show is not visible and only burdens the CPU. 720p screen -> hd720, 1080p -> hd1080, 1440p -> hd1440.
	 */
	public String youtubeQualityCap() {
		if (screenHeight <= 480) return "large";
		if (screenHeight <= 720) return "hd720";
		if (screenHeight <= 1080) return "hd1080";
		if (screenHeight <= 1440) return "hd1440";
		return "hd2160";
	}

	public String resolutionLabel() {
		return switch (screenHeight) {
			case 720 -> "720p";
			case 1080 -> "1080p";
			case 1440 -> "1440p";
			default -> screenWidth + "x" + screenHeight;
		};
	}

	/** 720p -> 1080p -> 1440p -> 720p. */
	public void cycleResolution() {
		if (screenHeight < 1080) { screenWidth = 1920; screenHeight = 1080; }
		else if (screenHeight < 1440) { screenWidth = 2560; screenHeight = 1440; }
		else { screenWidth = 1280; screenHeight = 720; }
	}

	/** Parses "720p"/"1080p"/"1440p" or "1920x1080"; false if invalid. */
	public static String glowLabelOf(float g) {
		return Lang.tr(g <= 0.01f ? "gui.doomscroll.glow.off"
				: g <= 0.6f ? "gui.doomscroll.glow.low"
				: g <= 1.2f ? "gui.doomscroll.glow.normal" : "gui.doomscroll.glow.high");
	}

	public String glowLabel() {
		return glowLabelOf(screenGlow);
	}

	/** off -> low -> normal -> high -> off */
	public void cycleGlow() {
		screenGlow = screenGlow <= 0.01f ? 0.5f : screenGlow <= 0.6f ? 1.0f : screenGlow <= 1.2f ? 1.8f : 0f;
	}

	public boolean setResolution(String text) {
		String t = text.trim().toLowerCase();
		switch (t) {
			case "720p" -> { screenWidth = 1280; screenHeight = 720; return true; }
			case "1080p" -> { screenWidth = 1920; screenHeight = 1080; return true; }
			case "1440p" -> { screenWidth = 2560; screenHeight = 1440; return true; }
			default -> {
				String[] p = t.split("x");
				if (p.length != 2) return false;
				try {
					int w = Integer.parseInt(p[0].trim()), h = Integer.parseInt(p[1].trim());
					if (w < 320 || h < 180 || w > 3840 || h > 2160) return false;
					screenWidth = w; screenHeight = h; return true;
				} catch (NumberFormatException e) { return false; }
			}
		}
	}
}
