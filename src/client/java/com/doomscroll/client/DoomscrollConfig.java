package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Kullanici ayarlari: config/doomscroll.json (kumanda, /ds komutlari ve dosyadan degistirilebilir). */
public final class DoomscrollConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("doomscroll.json");
	private static DoomscrollConfig instance;

	/** Ekran tarayicisi cozunurlugu (YouTube akis kalitesini de belirler). */
	public int screenWidth = 1280;
	public int screenHeight = 720;
	/** Tarayici ses ornekleme hizi; 0 = otomatik olc. Ses ince/kalin gelirse 44100 ya da 48000 yaz. */
	public int audioSampleRate = 0;
	/** Shorts/Reels/TikTok: video bitince (ya da basa sarinca) otomatik sonraki videoya gec. */
	public boolean autoScroll = true;
	/** Tarayici kimligi (User-Agent). Google girisi icin guncel Chrome kimligi; bos = Chromium varsayilani. */
	public String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
	/**
	 * Google/YouTube giris modu: Google gomulu tarayicidan girisi engeller ("browser may not be secure"); Firefox
	 * kimligi bu kontrolu gecer. Acikken tarayici Firefox olarak tanitilir ve ekran Google giris sayfasini acar.
	 * Giris bitince kapatilir; oturum cerezleri kaldigi icin normal kimlikte de giris yapilmis kalir. Yeniden baslatma ister.
	 */
	public boolean tvLogin = false;
	/**
	 * Ses gecikmesi profili: "dusuk" (20 ms parca, 40 ms tampon; ~150 ms toplam), "normal" (25/60; ~180 ms),
	 * "yuksek" (50/100; ~320 ms, en guvenli). Takilma/citirti olursa bir kademe yukari cik.
	 */
	public String audioLatency = "normal";
	/** Senin koymadigin ekranlarin sesi (0 = duyma). Sunucu "sessiz basla" derse oturum 0'dan baslar. */
	public float othersScreenVolume = 1.0f;
	/**
	 * Ekranlar kalici Chromium profilinden ayri, gecici bir cerez baglaminda calissin.
	 * Varsayilan kapali: acikken ekranda yapilan giris oyun kapaninca kaybolur.
	 * Sunucu kendi ayarindan zorlayabilir; o zaman bu ayar kapali olsa da gecerli olur.
	 */
	public boolean separateScreenCookies = false;

	/** Ses akisinin OpenAL parca suresi (ms). Profilden turetilir. */
	public int audioChunkMs() {
		return switch (audioLatency == null ? "normal" : audioLatency) {
			case "dusuk" -> 20;
			case "yuksek" -> 50;
			default -> 25;
		};
	}

	/** Halka tampon hedefi (ms). Profilden turetilir. */
	public int audioTargetBacklogMs() {
		return switch (audioLatency == null ? "normal" : audioLatency) {
			case "dusuk" -> 40;
			case "yuksek" -> 100;
			default -> 60;
		};
	}

	/** Tarayici sesine dijital kazanc (0.5..6). Web videolari oyun seslerine gore kisik; 1 = ham. Tepeler yumusak sinirlanir. */
	public float audioBoost = 2.5f;

	/** Yakindaki ekranin altyazisini HUD'da goster (ekrana bakmasan da). */
	public boolean subtitles = true;

	/** Reklam engelleme: alan adi listesi (istekler hic cikmaz) + YouTube reklam atlayici. */
	public boolean adBlock = true;
	/** Ekran isigi (ambilight): ekrandaki renkler yakindaki yuzeylere yansir. 0 kapali, 0.5 az, 1 normal, 1.8 cok. */
	public float screenGlow = 1.0f;
	/** Ekran isiginin ulastigi mesafe (blok). */
	public int screenGlowRange = 10;
	/** Yumusak isik: komsu yuzeyler arasinda kesintisiz gecis (kapali = blok blok mozaik). */
	public boolean screenGlowSmooth = true;
	/**
	 * Kisisel ses seviyeleri (0..1) ve sessiz durumlari. Ekranlar (kumandadaki kaydirici) ile elindeki tablet
	 * (tabletin kendi hoparlor tusu) ayri ayarlanir; ikisi de oyunlar arasi hatirlanir.
	 */
	public float screenVolume = 0.8f;
	public boolean screenMuted = false;
	public float tabletVolume = 0.8f;
	public boolean tabletMuted = false;
	/** Baskalarinin elindeki tabletin sesi (0 = duyma). Onlarin sayfasi senin tarayicinda acilir, seviye senindir. */
	public float remoteTabletVolume = 0.8f;
	/** Tablet eldeyken yurume/kosma sallanmasinin kalan orani: 0 = sabit, 1 = vanilla. */
	public float tabletSway = 0.35f;
	/** SponsorBlock: YouTube'da sponsor/intro/outro bolumlerini topluluk verisiyle atla. */
	public boolean sponsorBlock = true;
	/** Paylasimli isaretci: bakilan ekrandaki crosshair'i digerlerine yayinla ve digerlerininkini goster. */
	public boolean pointer = true;
	/** Yayin modu kodlama ayarlari: genislik (16:9), kare hizi, video bit hizi (kbps). */
	public int broadcastWidth = 960;
	public int broadcastFps = 24;
	public int broadcastKbps = 1200;
	/** Ilk giris mesaji gosterildi mi. */
	public boolean welcomeShown = false;

	/** Tarayici boyama kare hizi (10..60). Bakilan ekran bu hizda; bakilmayanlar otomatik dusurulur. */
	public int browserFps = 60;

	/** Kontrolcunun video konumuna hizalan (uzun video/film izlerken herkes ayni saniyede olsun). */
	public boolean syncPlayback = true;
	/** Kanal listesi (kumanda: Kanal ◀ ▶; /ds kanal ekle|sil|liste). Bos birakilirsa varsayilanlar. */
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
			Doomscroll.LOGGER.warn("doomscroll.json okunamadi, varsayilanlar kullaniliyor", e);
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
			Doomscroll.LOGGER.warn("doomscroll.json yazilamadi", e);
		}
	}

	/** Ekran cozunurlugu etiketi: 720p / 1080p / 1440p ya da GxY. */
	/**
	 * Ekran cozunurlugune gore YouTube en yuksek kalite (oynatici seviye adi): ekranin gosterebildiginden
	 * yukarisi gorunmez, sadece islemciyi yorar. 720p ekran -> hd720, 1080p -> hd1080, 1440p -> hd1440.
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

	/** "720p"/"1080p"/"1440p" ya da "1920x1080" ayristirir; gecersizse false. */
	public static String glowLabelOf(float g) {
		return Lang.tr(g <= 0.01f ? "gui.doomscroll.glow.off"
				: g <= 0.6f ? "gui.doomscroll.glow.low"
				: g <= 1.2f ? "gui.doomscroll.glow.normal" : "gui.doomscroll.glow.high");
	}

	public String glowLabel() {
		return glowLabelOf(screenGlow);
	}

	/** kapali -> az -> normal -> cok -> kapali */
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
