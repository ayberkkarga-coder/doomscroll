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
 * Sunucunun adres politikasinin istemcideki kopyasi (girise ve her ayar degisikligine gelir).
 *
 * <p>Neden istemcide de var: sunucu yalnizca <b>paylasilan</b> adresi denetleyebilir, ama
 * yonlendirmeler once tarayicida olur. Kisaltilmis bir adres engelli bir siteye giderse
 * sayfa bir an yuklenir. Ayni kurali her tarayicida, her adres degisikliginde uygulayinca
 * sayfa hic acilmaz. Degistirilmis bir istemci bunu yok sayabilir; sunucudaki denetim yine
 * gecerlidir, bu katman durustlugu degil sizintiyi engeller.
 */
public final class ServerPolicy {
	private static volatile List<String> blocked = List.of();
	private static volatile List<String> allowed = List.of();
	private static volatile int flags = ServerPolicyBroadcast.SHOW_DOMAIN;
	private static volatile int urlCooldownMs = 1500;

	/** Izleyicinin bu oturumda "goster" dedigi alan adlari. */
	private static final Set<String> TRUSTED = ConcurrentHashMap.newKeySet();

	private ServerPolicy() {}

	public static void apply(ServerPolicyBroadcast p) {
		blocked = List.copyOf(p.blocked());
		allowed = List.copyOf(p.allowed());
		flags = p.flags();
		urlCooldownMs = p.urlCooldownMs();
		Doomscroll.LOGGER.info("[politika] engelli {}, izinli {}, bayraklar {}", blocked.size(), allowed.size(), flags);
	}

	/** Dunyadan cikinca: bir sonraki sunucunun politikasi sizmasin. */
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

	public static boolean consentRequired() {
		return (flags & ServerPolicyBroadcast.REQUIRE_CONSENT) != 0;
	}

	public static int urlCooldownMs() {
		return urlCooldownMs;
	}

	/** Adres sunucu kurallarina uyuyor mu? (Sunucudaki ServerConfig.check ile ayni mantik.) */
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
	 * Sayfa cizilmeden once izleyicinin onayi gerekiyor mu?
	 * Sunucu onayi acmissa ve adres beyaz listede degilse, oyuncu "goster" diyene kadar yuklenmez.
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

	/** Oyuncu "goster" dedi: bu oturum boyunca bu alan adi sorulmaz. */
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
