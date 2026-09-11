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
	private static final String README =
			"Her ayarin ne ise yaradigi yanindaki doomscroll-server.txt dosyasinda ve oyun icinde /doomscroll yardim komutunda."
			+ "  |  What each setting does: see doomscroll-server.txt next to this file, or run /doomscroll help in game.";

	/** Adres reddedilme sebepleri; istemciye de ayni sirayla gider. */
	public static final int OK = 0;
	public static final int DENY_BLOCKED = 1;
	public static final int DENY_NOT_ALLOWED = 2;
	public static final int DENY_PRIVATE = 3;
	public static final int DENY_LOCKDOWN = 4;

	/**
	 * JSON'a yorum yazilamiyor; yonetici dosyayi acinca nereye bakacagini bilsin diye
	 * en uste konan yonlendirme. Degeri her yuklemede yenilenir.
	 */
	public String _readme = README;

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
	/**
	 * Ekranlar kalici Chromium profilinden ayri, gecici bir cerez baglaminda calissin.
	 * Acikken oyuncunun kendi ayari kapali olsa da gecerli: baskasinin actigi sayfa,
	 * oyuncunun giris yaptigi oturumla ayni baglamda calismaz. Yalnizca yeni acilan
	 * tarayicilari etkiler; zaten acik ekranlar bir sonraki yuklemede gecer.
	 */
	public boolean separateScreenCookies = false;
	/** Tek bir panelin en fazla kac blok olabilecegi (0 = sinirsiz). */
	public int maxPanelBlocks = 0;
	/** Bir oyuncunun ayni anda kac ekran blogu koyabilecegi (0 = sinirsiz). */
	public int maxScreensPerPlayer = 0;
	/**
	 * Ayni oyuncunun iki adres degisikligi arasinda beklemesi gereken sure (ms).
	 * Varsayilan 0: kanal gezerken yolu kesmesin. Halka acik sunucuda 1000-2000 arasi iyi.
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
		c._readme = README;
		instance = c;
		// Her acilista yeniden yaz: mod guncellenip yeni ayar geldiginde dosyada da gorunsun.
		save();
		ServerGuide.write(FILE.getParent());
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

	/**
	 * Yerel/ozel IPv4 mu? Tarayicilar "127.1", "0x7f.0.0.1", "010.1" gibi kisa ve
	 * sekizlik/onaltilik yazimlari da ayni adrese cozer; duz dortlu ayristirma
	 * bunlari kacirdigi icin burada inet_aton kurallari uygulanir.
	 */
	private static boolean isPrivateIpv4(String h) {
		long addr = parseIpv4(h);
		if (addr < 0) {
			return false;
		}
		int o0 = (int) ((addr >> 24) & 0xff);
		int o1 = (int) ((addr >> 16) & 0xff);
		int o2 = (int) ((addr >> 8) & 0xff);
		if (o0 == 0 || o0 == 127) return true;                           // bu ag / loopback
		if (o0 == 10) return true;                                       // ozel
		if (o0 == 172 && o1 >= 16 && o1 <= 31) return true;              // ozel
		if (o0 == 192 && o1 == 168) return true;                         // ozel
		if (o0 == 169 && o1 == 254) return true;                         // link-local + bulut metadata
		if (o0 == 100 && o1 >= 64 && o1 <= 127) return true;             // operator NAT
		if (o0 == 192 && o1 == 0 && (o2 == 0 || o2 == 2)) return true;
		if (o0 == 198 && (o1 == 18 || o1 == 19)) return true;            // olcum
		return o0 >= 224;                                                // cok noktaya yayin + ayrilmis
	}

	/** Adres degilse -1. Bir ile dort parca; son parca kalan baytlari kapsar. */
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

	/** Onluk, "0x" ile onaltilik, bas sifirla sekizlik. Sayi degilse -1. */
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
