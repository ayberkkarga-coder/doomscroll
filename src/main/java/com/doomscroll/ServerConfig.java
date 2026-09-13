package com.doomscroll;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server admin settings: config/doomscroll-server.json (the same file in singleplayer too).
 * Read on the server side; independent of the clients' configuration.
 */
public final class ServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("doomscroll-server.json");
	private static ServerConfig instance;
	private static final String README =
			"Her ayarin ne ise yaradigi yanindaki doomscroll-server.txt dosyasinda ve oyun icinde /doomscroll yardim komutunda."
			+ "  |  What each setting does: see doomscroll-server.txt next to this file, or run /doomscroll help in game.";

	/** Reasons an address is refused; sent to the client in the same order. */
	public static final int OK = 0;
	public static final int DENY_BLOCKED = 1;
	public static final int DENY_NOT_ALLOWED = 2;
	public static final int DENY_PRIVATE = 3;
	public static final int DENY_LOCKDOWN = 4;

	/**
	 * JSON cannot carry comments; this pointer sits at the top so an admin opening the file
	 * knows where to look. Its value is refreshed on every load.
	 */
	public String _readme = README;

	/** Blocked domains (subdomains included): cannot be opened on screens; if one is opened anyway, everyone falls back to the previous address. */
	public List<String> blockedDomains = new ArrayList<>();
	/** If not empty, only these domains (and their subdomains) can be opened. */
	public List<String> allowedDomains = new ArrayList<>();
	/** Block light of a screen that is on (0-15). Changing it requires a restart. */
	public int screenLightLevel = 12;
	/** The "X opened a site on the screen" notice. */
	public boolean announce = true;
	/** How far the notice reaches (blocks). */
	public int announceRange = 32;
	/** Allow screens to be switched on and off by redstone (enabled per screen with /ds redstone). */
	public boolean redstoneControl = true;
	/** Shared pointer broadcast (other players' cursors on the screen). */
	public boolean pointer = true;
	/** Broadcast mode (one player's view is relayed to everyone): uses server bandwidth. */
	public boolean broadcast = true;
	/** How long a controller may stay silent before losing control (seconds). */
	public int controlTimeoutSeconds = 45;

	// ---------- audit and safety ----------

	/** Write opened addresses to config/doomscroll-audit.log. */
	public boolean auditLog = true;
	/** Emergency shutdown: every screen is darkened and none can be turned on. Toggled with /doomscroll emergency. */
	public boolean lockdown = false;
	/**
	 * Allow local-network and loopback addresses. Off by default: if it were on, a player
	 * could put 192.168.1.1 on a screen and make everyone open their own router interface.
	 * Turn it on only to experiment on your own LAN.
	 */
	public boolean allowPrivateNetwork = false;
	/**
	 * An address outside the allow list is not drawn right away: first a "this domain, placed by this player,
	 * show it" card appears. The page is not loaded until the viewer approves (against shock content and IP leaks).
	 */
	public boolean requireConsent = false;
	/** Screens you did not place start muted (the player can turn them up with their own volume slider). */
	public boolean muteOthersByDefault = false;
	/** Show the real domain under the screen (against fake sign-in pages). */
	public boolean showDomain = true;
	/**
	 * Run screens in a temporary cookie context, separate from the persistent Chromium profile.
	 * When on, it applies even if the player's own setting is off: a page somebody else opened
	 * does not run in the same context as the player's signed-in session. Only affects newly
	 * opened browsers; screens that are already open switch over on their next load.
	 */
	public boolean separateScreenCookies = false;
	/** Maximum size of a single panel in blocks (0 = unlimited). */
	public int maxPanelBlocks = 0;
	/** How many screen blocks one player may have placed at the same time (0 = unlimited). */
	public int maxScreensPerPlayer = 0;
	/**
	 * Minimum gap between two address changes by the same player (ms).
	 * Default 0: it must not get in the way while channel-surfing. 1000-2000 is good on a public server.
	 */
	public int urlCooldownMs = 0;

	public static synchronized ServerConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	public static synchronized void load() {
		ServerConfig c = null;
		try {
			if (Files.isRegularFile(FILE)) {
				c = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), ServerConfig.class);
			}
		} catch (Exception e) {
			Doomscroll.LOGGER.warn("doomscroll-server.json could not be read, using defaults: {}", e.toString());
		}
		if (c == null) {
			c = new ServerConfig();
		}
		if (c.blockedDomains == null) c.blockedDomains = new ArrayList<>();
		if (c.allowedDomains == null) c.allowedDomains = new ArrayList<>();
		c.screenLightLevel = Math.max(0, Math.min(15, c.screenLightLevel));
		c.announceRange = Math.max(0, Math.min(256, c.announceRange));
		c.controlTimeoutSeconds = Math.max(5, Math.min(3600, c.controlTimeoutSeconds));
		c.maxPanelBlocks = Math.max(0, Math.min(4096, c.maxPanelBlocks));
		c.maxScreensPerPlayer = Math.max(0, Math.min(4096, c.maxScreensPerPlayer));
		c.urlCooldownMs = Math.max(0, Math.min(60000, c.urlCooldownMs));
		c._readme = README;
		instance = c;
		// Rewrite on every start: when the mod is updated and a new setting arrives, it shows up in the file too.
		save();
		ServerGuide.write(FILE.getParent());
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(instance == null ? new ServerConfig() : instance), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Doomscroll.LOGGER.warn("doomscroll-server.json could not be written: {}", e.toString());
		}
	}

	/** Does the address's domain comply with the server rules? */
	public static boolean urlAllowed(String url) {
		return check(url) == OK;
	}

	/** Why was the address refused? OK or one of the DENY_* constants. */
	public static int check(String url) {
		if (url != null && url.startsWith("doomscroll://")) {
			return OK; // in-mod pages (home menu, consent card)
		}
		ServerConfig c = get();
		if (c.lockdown) {
			return DENY_LOCKDOWN;
		}
		String host = hostOf(url);
		if (!c.allowPrivateNetwork && isPrivateHost(host)) {
			return DENY_PRIVATE;
		}
		if (c.blockedDomains.isEmpty() && c.allowedDomains.isEmpty()) {
			return OK;
		}
		if (host.isEmpty()) {
			return c.allowedDomains.isEmpty() ? OK : DENY_NOT_ALLOWED;
		}
		for (String d : c.blockedDomains) {
			if (matches(host, d)) {
				return DENY_BLOCKED;
			}
		}
		if (!c.allowedDomains.isEmpty()) {
			for (String d : c.allowedDomains) {
				if (matches(host, d)) {
					return OK;
				}
			}
			return DENY_NOT_ALLOWED;
		}
		return OK;
	}

	/** Is the address on the allow list? (An empty allow list = nothing is "known"; viewer consent checks this.) */
	public static boolean onAllowList(String url) {
		if (url != null && url.startsWith("doomscroll://")) {
			return true;
		}
		ServerConfig c = get();
		String host = hostOf(url);
		if (host.isEmpty()) {
			return false;
		}
		for (String d : c.allowedDomains) {
			if (matches(host, d)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Is it loopback, a local network, link-local (cloud metadata included) or an intranet name?
	 * If such an address is put on a screen, every viewer's client opens that address on ITS OWN
	 * network; we would effectively be putting a network scanner inside the game.
	 */
	public static boolean isPrivateHost(String host) {
		if (host == null || host.isEmpty()) {
			return false;
		}
		String h = host.toLowerCase(Locale.ROOT);
		if (h.startsWith("[") && h.endsWith("]")) {
			h = h.substring(1, h.length() - 1);
		}
		if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")
				|| h.endsWith(".internal") || h.endsWith(".home.arpa")) {
			return true;
		}
		// Dotless name = intranet host name (router, nas, printer...)
		if (h.indexOf('.') < 0 && h.indexOf(':') < 0) {
			return true;
		}
		if (h.indexOf(':') >= 0) {
			// IPv6: ::1, unique local (fc00::/7), link-local (fe80::/10), IPv4-mapped
			if (h.equals("::1") || h.equals("::")) return true;
			if (h.startsWith("fc") || h.startsWith("fd")) return true;
			if (h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")) return true;
			int last = h.lastIndexOf(':');
			String tail = h.substring(last + 1);
			return tail.indexOf('.') > 0 && isPrivateIpv4(tail);
		}
		return isPrivateIpv4(h);
	}

	/**
	 * Is it a local/private IPv4? Browsers resolve short and octal/hex spellings such as
	 * "127.1", "0x7f.0.0.1", "010.1" to the same address; a plain dotted-quad parse
	 * misses them, so the inet_aton rules are applied here.
	 */
	private static boolean isPrivateIpv4(String h) {
		long addr = parseIpv4(h);
		if (addr < 0) {
			return false;
		}
		int o0 = (int) ((addr >> 24) & 0xff);
		int o1 = (int) ((addr >> 16) & 0xff);
		int o2 = (int) ((addr >> 8) & 0xff);
		if (o0 == 0 || o0 == 127) return true;                           // this network / loopback
		if (o0 == 10) return true;                                       // private
		if (o0 == 172 && o1 >= 16 && o1 <= 31) return true;              // private
		if (o0 == 192 && o1 == 168) return true;                         // private
		if (o0 == 169 && o1 == 254) return true;                         // link-local + cloud metadata
		if (o0 == 100 && o1 >= 64 && o1 <= 127) return true;             // carrier-grade NAT
		if (o0 == 192 && o1 == 0 && (o2 == 0 || o2 == 2)) return true;
		if (o0 == 198 && (o1 == 18 || o1 == 19)) return true;            // benchmarking
		return o0 >= 224;                                                // multicast + reserved
	}

	/** -1 if not an address. One to four parts; the last part covers the remaining bytes. */
	private static long parseIpv4(String h) {
		String[] parts = h.split("\\.", -1);
		if (parts.length < 1 || parts.length > 4) {
			return -1;
		}
		long[] v = new long[parts.length];
		for (int i = 0; i < parts.length; i++) {
			v[i] = parseOctet(parts[i]);
			if (v[i] < 0) {
				return -1;
			}
		}
		int n = parts.length;
		long last = v[n - 1];
		if (last >= (1L << (8 * (5 - n)))) {
			return -1;
		}
		long addr = last;
		for (int i = 0; i < n - 1; i++) {
			if (v[i] > 255) {
				return -1;
			}
			addr |= v[i] << (8 * (3 - i));
		}
		return addr;
	}

	/** Decimal, hex with "0x", octal with a leading zero. -1 if not a number. */
	private static long parseOctet(String s) {
		if (s.isEmpty() || s.length() > 11) {
			return -1;
		}
		try {
			if (s.length() > 2 && (s.charAt(0) == '0') && (s.charAt(1) == 'x' || s.charAt(1) == 'X')) {
				return Long.parseLong(s.substring(2), 16);
			}
			if (s.length() > 1 && s.charAt(0) == '0') {
				return Long.parseLong(s.substring(1), 8);
			}
			return Long.parseLong(s, 10);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static boolean matches(String host, String rule) {
		if (rule == null) return false;
		String r = rule.trim().toLowerCase(Locale.ROOT);
		if (r.startsWith("*.")) r = r.substring(2);
		if (r.startsWith("www.")) r = r.substring(4);
		if (r.isEmpty()) return false;
		return host.equals(r) || host.endsWith("." + r);
	}

	public static String hostOf(String url) {
		try {
			String h = URI.create(url).getHost();
			if (h == null) {
				// URI.getHost() only returns syntactically valid names; host names containing underscores come back null.
				String s = url == null ? "" : url;
				int i = s.indexOf("://");
				if (i < 0) return "";
				s = s.substring(i + 3);
				int cut = s.length();
				for (String sep : new String[]{"/", "?", "#"}) {
					int k = s.indexOf(sep);
					if (k >= 0) cut = Math.min(cut, k);
				}
				s = s.substring(0, cut);
				int at = s.lastIndexOf('@');
				if (at >= 0) s = s.substring(at + 1);
				if (!s.startsWith("[")) {
					int colon = s.indexOf(':');
					if (colon >= 0) s = s.substring(0, colon);
				}
				h = s;
			}
			h = h.toLowerCase(Locale.ROOT);
			return h.startsWith("www.") ? h.substring(4) : h;
		} catch (Exception e) {
			return "";
		}
	}
}
