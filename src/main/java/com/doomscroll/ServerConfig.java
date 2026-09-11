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
		if (url != null && url.startsWith("doomscroll://")) {
			return true; // mod ici sayfalar (ana menu)
		}
		ServerConfig c = get();
		if (c.blockedDomains.isEmpty() && c.allowedDomains.isEmpty()) {
			return true;
		}
		String host = hostOf(url);
		if (host.isEmpty()) {
			return c.allowedDomains.isEmpty();
		}
		for (String d : c.blockedDomains) {
			if (matches(host, d)) {
				return false;
			}
		}
		if (!c.allowedDomains.isEmpty()) {
			for (String d : c.allowedDomains) {
				if (matches(host, d)) {
					return true;
				}
			}
			return false;
		}
		return true;
	}

	private static boolean matches(String host, String rule) {
		if (rule == null) return false;
		String r = rule.trim().toLowerCase(Locale.ROOT);
		if (r.startsWith("*.")) r = r.substring(2);
		if (r.startsWith("www.")) r = r.substring(4);
		if (r.isEmpty()) return false;
		return host.equals(r) || host.endsWith("." + r);
	}

	static String hostOf(String url) {
		try {
			String h = URI.create(url).getHost();
			if (h == null) return "";
			h = h.toLowerCase(Locale.ROOT);
			return h.startsWith("www.") ? h.substring(4) : h;
		} catch (Exception e) {
			return "";
		}
	}
}
