package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.ServerConfig;
import com.doomscroll.net.ServerPolicyBroadcast;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side copy of the server's URL policy (arrives on login and on every settings change).
 *
 * <p>Why it also lives on the client: the server can only check the <b>shared</b> URL, but
 * redirects happen in the browser first. If a shortened URL leads to a blocked site, the page
 * loads for a moment. Applying the same rule in every browser, on every URL change, means the
 * page never opens at all. A modified client can ignore this; the server-side check still
 * stands, so this layer prevents leaks, not dishonesty.
 */
public final class ServerPolicy {
	private static volatile List<String> blocked = List.of();
	private static volatile List<String> allowed = List.of();
	private static volatile int flags = ServerPolicyBroadcast.SHOW_DOMAIN;
	private static volatile int urlCooldownMs = 1500;

	/** Domains the viewer has said "show" to in this session. */
	private static final Set<String> TRUSTED = ConcurrentHashMap.newKeySet();

	private ServerPolicy() {}

	public static void apply(ServerPolicyBroadcast p) {
		blocked = List.copyOf(p.blocked());
		allowed = List.copyOf(p.allowed());
		flags = p.flags();
		urlCooldownMs = p.urlCooldownMs();
		Doomscroll.LOGGER.info("[policy] blocked {}, allowed {}, flags {}", blocked.size(), allowed.size(), flags);
	}

	/** On leaving the world: no stale policy may leak into the next server. */
	public static void reset() {
		blocked = List.of();
		allowed = List.of();
		flags = ServerPolicyBroadcast.SHOW_DOMAIN;
		urlCooldownMs = 1500;
		TRUSTED.clear();
	}

	public static boolean lockdown() {
		return (flags & ServerPolicyBroadcast.LOCKDOWN) != 0;
	}

	public static boolean showDomain() {
		return (flags & ServerPolicyBroadcast.SHOW_DOMAIN) != 0;
	}

	public static boolean muteOthers() {
		return (flags & ServerPolicyBroadcast.MUTE_OTHERS) != 0;
	}

	/** Does the server force screen cookies to be kept separate? */
	public static boolean separateCookies() {
		return (flags & ServerPolicyBroadcast.SEPARATE_COOKIES) != 0;
	}

	public static boolean consentRequired() {
		return (flags & ServerPolicyBroadcast.REQUIRE_CONSENT) != 0;
	}

	public static int urlCooldownMs() {
		return urlCooldownMs;
	}

	/** Does the URL pass the server rules? (Same logic as ServerConfig.check on the server.) */
	public static boolean allows(@Nullable String url) {
		if (url == null || url.isEmpty()) {
			return true;
		}
		if (url.startsWith("doomscroll://") || url.startsWith("about:") || url.startsWith("data:")) {
			return true;
		}
		if (lockdown()) {
			return false;
		}
		String host = host(url);
		if ((flags & ServerPolicyBroadcast.ALLOW_PRIVATE) == 0 && ServerConfig.isPrivateHost(host)) {
			return false;
		}
		if (blocked.isEmpty() && allowed.isEmpty()) {
			return true;
		}
		if (host.isEmpty()) {
			return allowed.isEmpty();
		}
		for (String d : blocked) {
			if (matches(host, d)) {
				return false;
			}
		}
		if (!allowed.isEmpty()) {
			for (String d : allowed) {
				if (matches(host, d)) {
					return true;
				}
			}
			return false;
		}
		return true;
	}

	/**
	 * Is the viewer's consent needed before the page is drawn?
	 * If the server has consent enabled and the URL is not on the allow list, nothing loads until the player says "show".
	 */
	public static boolean needsConsent(@Nullable String url) {
		if (!consentRequired() || url == null || url.isEmpty()) {
			return false;
		}
		if (url.startsWith("doomscroll://") || url.startsWith("about:") || url.startsWith("data:")) {
			return false;
		}
		String host = host(url);
		if (host.isEmpty() || TRUSTED.contains(host)) {
			return false;
		}
		for (String d : allowed) {
			if (matches(host, d)) {
				return false;
			}
		}
		return true;
	}

	/** The player said "show": this domain is not asked about again for the rest of the session. */
	public static void trust(String host) {
		String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
		if (!h.isEmpty()) {
			TRUSTED.add(h.startsWith("www.") ? h.substring(4) : h);
		}
	}

	public static boolean trusted(String host) {
		return host != null && TRUSTED.contains(host);
	}

	public static String host(@Nullable String url) {
		return url == null ? "" : ServerConfig.hostOf(url);
	}

	private static boolean matches(String host, String rule) {
		if (rule == null) return false;
		String r = rule.trim().toLowerCase(Locale.ROOT);
		if (r.startsWith("*.")) r = r.substring(2);
		if (r.startsWith("www.")) r = r.substring(4);
		if (r.isEmpty()) return false;
		return host.equals(r) || host.endsWith("." + r);
	}
}
