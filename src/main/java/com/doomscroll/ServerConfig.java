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
 * Sunucu yonetici ayarlari: config/doomscroll-server.json (tek oyunculuda da ayni dosya).
 * Sunucu tarafinda okunur; istemcilerin konfigurasyonundan bagimsizdir.
 */
public final class ServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("doomscroll-server.json");
	private static ServerConfig instance;

	/** Adres reddedilme sebepleri; istemciye de ayni sirayla gider. */
	public static final int OK = 0;
	public static final int DENY_BLOCKED = 1;
	public static final int DENY_NOT_ALLOWED = 2;
	public static final int DENY_PRIVATE = 3;
	public static final int DENY_LOCKDOWN = 4;

	/** Engelli alan adlari (alt alanlar dahil): ekranlarda acilamaz, acilirsa herkes eski adrese geri doner. */
	public List<String> blockedDomains = new ArrayList<>();
	/** Bos degilse yalnizca bu alan adlari (ve alt alanlari) acilabilir. */
	public List<String> allowedDomains = new ArrayList<>();
	/** Acik ekranin blok isigi (0-15). Degisiklik yeniden baslatma ister. */
	public int screenLightLevel = 12;
	/** "X ekranda site acti" bildirimi. */
	public boolean announce = true;
	/** Bildirimin ulastigi mesafe (blok). */
	public int announceRange = 32;
	/** Ekranlarin redstone ile acilip kapanmasina izin ver (ekran basina /ds redstone ile etkinlestirilir). */
	public boolean redstoneControl = true;
	/** Paylasimli isaretci (baskalarinin ekrandaki imleci) yayini. */
	public boolean pointer = true;
	/** Yayin modu (bir oyuncunun goruntusu herkese aktarilir): sunucu bant genisligi kullanir. */
	public boolean broadcast = true;
	/** Kontrolcunun sessiz kalinca kontrolu kaybettigi sure (sn). */
	public int controlTimeoutSeconds = 45;

	// ---------- denetim ve guvenlik ----------

	/** Acilan adresleri config/doomscroll-audit.log dosyasina yaz. */
	public boolean auditLog = true;
	/** Acil kapatma: butun ekranlar karartilir ve acilamaz. /doomscroll acil ile degisir. */
	public boolean lockdown = false;
	/**
	 * Yerel ag ve loopback adreslerine izin ver. Varsayilan kapali: acik olsa bir oyuncu
	 * ekrana 192.168.1.1 koyup herkesin kendi modem arayuzunu actirabilir.
	 * Yalnizca kendi LAN'inde denemek icin ac.
	 */
	public boolean allowPrivateNetwork = false;
	/**
	 * Beyaz listede olmayan bir adres dogrudan cizilmez: once "su alan adi, su oyuncu koydu, goster"
	 * karti cikar. Izleyici onaylayana kadar sayfa yuklenmez (sok icerik ve IP sizintisi icin).
	 */
	public boolean requireConsent = false;
	/** Senin koymadigin ekranlar sessiz baslasin (oyuncu kendi ses kaydiricisiyla acabilir). */
	public boolean muteOthersByDefault = false;
	/** Gercek alan adi ekranin altinda yazsin (sahte giris sayfasina karsi). */
	public boolean showDomain = true;
	/** Tek bir panelin en fazla kac blok olabilecegi (0 = sinirsiz). */
	public int maxPanelBlocks = 0;
	/** Bir oyuncunun ayni anda kac ekran blogu koyabilecegi (0 = sinirsiz). */
	public int maxScreensPerPlayer = 0;
	/** Ayni oyuncunun iki adres degisikligi arasinda beklemesi gereken sure (ms, 0 = beklemesiz). */
	public int urlCooldownMs = 1500;

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
			Doomscroll.LOGGER.warn("doomscroll-server.json okunamadi, varsayilanlar kullaniliyor: {}", e.toString());
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
		instance = c;
		if (!Files.isRegularFile(FILE)) {
			save();
		}
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(instance == null ? new ServerConfig() : instance), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Doomscroll.LOGGER.warn("doomscroll-server.json yazilamadi: {}", e.toString());
		}
	}

	/** Adresin alan adi sunucu kurallarina uyuyor mu? */
	public static boolean urlAllowed(String url) {
		return check(url) == OK;
	}

	/** Adres neden reddedildi? OK ya da DENY_* sabitlerinden biri. */
	public static int check(String url) {
		if (url != null && url.startsWith("doomscroll://")) {
			return OK; // mod ici sayfalar (ana menu, onay karti)
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

	/** Adres beyaz listede mi? (Bos beyaz liste = hicbir sey "bilinen" degil; izleyici onayi buna bakar.) */
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
	 * Loopback, yerel ag, link-local (bulut metadata dahil) ve ic ag adi mi?
	 * Boyle bir adres ekrana konursa her izleyicinin istemcisi KENDI agindaki
	 * o adresi acar; yani oyunun icine ag tarayicisi koymus oluruz.
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
		// Noktasiz ad = ic ag adi (router, nas, printer...)
		if (h.indexOf('.') < 0 && h.indexOf(':') < 0) {
			return true;
		}
		if (h.indexOf(':') >= 0) {
			// IPv6: ::1, benzersiz yerel (fc00::/7), link-local (fe80::/10), IPv4 esleme
			if (h.equals("::1") || h.equals("::")) return true;
			if (h.startsWith("fc") || h.startsWith("fd")) return true;
			if (h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")) return true;
			int last = h.lastIndexOf(':');
			String tail = h.substring(last + 1);
			return tail.indexOf('.') > 0 && isPrivateIpv4(tail);
		}
		return isPrivateIpv4(h);
	}

	private static boolean isPrivateIpv4(String h) {
		String[] parts = h.split("\\.");
		if (parts.length != 4) {
			return false;
		}
		int[] o = new int[4];
		for (int i = 0; i < 4; i++) {
			try {
				o[i] = Integer.parseInt(parts[i]);
			} catch (NumberFormatException e) {
				return false; // alan adi, IP degil
			}
			if (o[i] < 0 || o[i] > 255) {
				return false;
			}
		}
		if (o[0] == 0 || o[0] == 127) return true;                       // bu ag / loopback
		if (o[0] == 10) return true;                                     // ozel
		if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return true;        // ozel
		if (o[0] == 192 && o[1] == 168) return true;                     // ozel
		if (o[0] == 169 && o[1] == 254) return true;                     // link-local + bulut metadata
		if (o[0] == 100 && o[1] >= 64 && o[1] <= 127) return true;       // operator NAT
		if (o[0] == 192 && o[1] == 0 && (o[2] == 0 || o[2] == 2)) return true;
		if (o[0] == 198 && (o[1] == 18 || o[1] == 19)) return true;      // olcum
		return o[0] >= 224;                                              // cok noktaya yayin + ayrilmis
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
				// URI.getHost() yalnizca kurallara uyan adlari dondurur; alt cizgili ana makine adlari null gelir.
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
