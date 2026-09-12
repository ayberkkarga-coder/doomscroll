package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.ScreenBlockEntity;
import com.doomscroll.cef.api.CefBrowserView;
import com.doomscroll.cef.api.CefService;
import com.doomscroll.net.ScreenControlPayload;
import com.doomscroll.net.ScreenTimePayload;
import com.doomscroll.net.SetScreenUrlPayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Her ekran (cok-bloklu panelin anchor'u) icin ayri bir tarayici. Ekran cizildiginde acilir; oyuncuya
 * yakin oldugu surece (gorus disinda da) canli kalir, uzaklasinca kapanir. Ayni anda en fazla MAX_LIVE
 * ekran canli olur (en yakinlar); digerleri kapali TV gibi siyah durur. Ses her ekranin kendi konumundan.
 *
 * Kontrol modeli: her ekranin sunucuda bir "kontrolcusu" vardir (ekrani suren oyuncu). Adres degisiklikleri
 * ve otomatik gecis yalnizca kontrolcunun tarayicisindan yayilir; digerleri izler. Bilerek yapilan her
 * eylem (kumanda, tiklama, tekerlek, adres) kontrolu alir (ekran kilitli degilse). Kontrolcu uzun videolarda
 * konumunu yayinlar; izleyiciler sapinca hizalanir.
 */
public final class ScreenBrowsers {
	public static final int MAX_LIVE = 3;
	public static final double LIVE_DISTANCE = 48.0;
	/** Izleyici bu kadar sapinca kontrolcuye hizalanir (sn). */
	private static final double SYNC_DRIFT = 2.5;
	/** Kisa (loop) videolarda konum senkronu yok (sn). */
	private static final double SYNC_MIN_DURATION = 30.0;
	/** Konum raporu bu kadar eskiyse guvenilmez (ms). */
	private static final long FRESH_MS = 3000L;

	public static final class Screen {
		public final BlockPos pos;
		public final Vec3 center;
		public final String key;
		@Nullable public CefBrowserView browser;
		@Nullable CefTexture texture;
		@Nullable Identifier textureId;
		@Nullable BrowserSoundInstance sound;
		boolean on = true;
		boolean pausedForOff = false;
		String startUrl = "";
		String lastSent = "";
		/** Izleyici onayi bekleyen gercek adres (onay karti gosterilirken). */
		@Nullable String pendingUrl = null;
		/** Ekranin sahibi ben miyim? (baskasinin ekrani ayri ses seviyesinden duyulur) */
		boolean mine = true;
		@Nullable String serverUrlSeen = null;
		long lastRenderNanos = 0L;
		/** Panelin ortasi (gorunurluk kestirimi icin). */
		Vec3 panelCenter;
		@Nullable net.minecraft.core.Direction facing;
		@Nullable net.minecraft.core.Direction extDir;
		@Nullable net.minecraft.core.Direction topDir;
		int panelW = 1;
		int panelH = 1;
		boolean refApplied = false;
		int soundRate = 0;
		String subtitle = "";
		String lastPopup = "";
		long subtitleStampMs = 0L;
		@Nullable Vec3 lastSprPos;
		@Nullable Vec3 lastSprListener;

		/**
		 * Sesin geldigi nokta: panel yuzeyinde dinleyiciye en yakin nokta, yuzeyin hemen onunde.
		 * Boylece kocaman ekranin sesi tek bir bloktan degil, onunde durdugun yerden gelir.
		 */
		Vec3 soundPos(Vec3 listener) {
			if (facing == null || extDir == null || topDir == null) {
				return center;
			}
			Vec3 ext = extDir.getUnitVec3();
			Vec3 up = topDir.getUnitVec3();
			Vec3 rel = listener.subtract(center);
			double u = Math.max(-0.5, Math.min(panelW - 0.5, rel.dot(ext)));
			double v = Math.max(-0.5, Math.min(panelH - 0.5, rel.dot(up)));
			return center.add(ext.scale(u)).add(up.scale(v)).add(facing.getUnitVec3().scale(0.6));
		}
		int appliedFps = -1;

		// kontrol (sunucudan, BE ile gelir)
		@Nullable UUID controller;
		String controllerName = "";
		boolean locked = false;
		/** Ekranin ortak sesi (blokta durur, herkes ayni): kisisel kaydiricinin ustune carpan olarak biner. */
		float screenVolume = 1f;
		long lastTakeMs = 0L;

		// yerel video durumu (sayfa raporu, saniyede bir)
		double localTime = -1;
		double localDuration = 0;
		boolean localPaused = true;
		long localStampMs = 0L;
		String localUrl = "";
		long localFrame = 0; // raporu gonderen cerceve (film sitelerinde oynatici iframe icinde)
		String localTitle = "";
		String qualityAppliedId = ""; // YouTube kalite siniri uygulanan video
		// yayin
		@Nullable UUID broadcaster;
		String broadcasterName = "";
		boolean viewerMode = false;
		boolean viewerHasInit = false;
		boolean viewerReady = false;
		double viewerTime = -1;
		boolean hostActive = false;
		String bcUrl = "";
		int qualityTries = 0;

		// kontrolcunun bildirdigi konum
		double remoteTime = -1;
		double remoteDuration = 0;
		boolean remotePaused = true;
		long remoteStampMs = 0L;
		long lastSeekMs = 0L;
		long lastPlayPauseMs = 0L;

		Screen(BlockPos pos) {
			this.pos = pos.immutable();
			this.center = Vec3.atCenterOf(pos);
			this.panelCenter = this.center;
			this.key = keyOf(pos);
		}

		public String currentUrl() {
			if (browser == null) return "";
			String u = browser.getCefBrowser().getURL();
			return u == null ? "" : u;
		}

		public boolean isController() {
			UUID me = localPlayerId();
			return me != null && me.equals(controller);
		}

		public boolean isFree() {
			return controller == null;
		}

		/** Bu istemci ekrani suruyor mu (kontrol bende ya da bos)? */
		public boolean drives() {
			return isFree() || isController();
		}

		public boolean isLocked() {
			return locked;
		}

		/** "sen" / oyuncu adi / "boş". */
		public String controllerLabel() {
			if (controller == null) return Lang.tr("gui.doomscroll.none");
			if (isController()) return Lang.tr("gui.doomscroll.you");
			return controllerName.isEmpty() ? "?" : controllerName;
		}

		/** Yerel video konumu, rapor zamanindan bu yana gecen sureyle duzeltilmis; yoksa -1. */
		public double localNow() {
			if (localDuration <= 0 || System.currentTimeMillis() - localStampMs > FRESH_MS) return -1;
			return localPaused ? localTime : localTime + (System.currentTimeMillis() - localStampMs) / 1000.0;
		}

		public double localDuration() {
			return localDuration;
		}
	}

	private static final Map<BlockPos, Screen> SCREENS = new java.util.concurrent.ConcurrentHashMap<>();
	@Nullable private static BlockPos activePos;
	private static int tick = 0;

	private ScreenBrowsers() {}

	/** Dinleyici (goz) konumu; oyuncu yoksa sifir. */
	static Vec3 listenerPos() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? Vec3.ZERO : mc.player.getEyePosition();
	}

	@Nullable
	static UUID localPlayerId() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : mc.player.getUUID();
	}

	static String keyOf(BlockPos p) {
		return c(p.getX()) + "_" + c(p.getY()) + "_" + c(p.getZ());
	}

	private static String c(int v) {
		return v < 0 ? "m" + (-v) : Integer.toString(v);
	}

	@Nullable
	public static Screen get(@Nullable BlockPos anchor) {
		return anchor == null ? null : SCREENS.get(anchor.immutable());
	}

	// ---------- renderer ----------

	/** Renderer (anchor BE) her karede: kaydi guncelle, gerekiyorsa tarayiciyi ac. */
	public static Screen noteRendered(ScreenBlockEntity be, boolean on) {
		Screen s = SCREENS.computeIfAbsent(be.getBlockPos().immutable(), Screen::new);
		s.lastRenderNanos = System.nanoTime();
		var st = be.getBlockState();
		if (st.hasProperty(com.doomscroll.ScreenBlock.FACING)) {
			s.facing = st.getValue(com.doomscroll.ScreenBlock.FACING);
			s.topDir = com.doomscroll.ScreenBlock.top(st);
			s.extDir = com.doomscroll.ScreenBlock.extend(st);
			s.panelW = be.getWidth();
			s.panelH = be.getHeight();
			s.panelCenter = s.center.add(s.extDir.getUnitVec3().scale((s.panelW - 1) / 2.0)).add(s.topDir.getUnitVec3().scale((s.panelH - 1) / 2.0));
		}
		s.controller = be.getController();
		s.controllerName = be.getControllerName();
		s.broadcaster = be.getBroadcaster();
		s.broadcasterName = be.getBroadcasterName();
		s.locked = be.isLocked();
		s.screenVolume = be.getVolume();
		if (s.on != on) {
			s.on = on;
			applyOnState(s);
		}
		applyServerUrl(s, be.getUrl());
		if (on && s.browser == null) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && s.center.distanceTo(mc.player.position()) <= LIVE_DISTANCE) {
				tryCreate(s); // uzaktaki ekranlar acilmaz (tick hemen kapatirdi)
			}
		}
		return s;
	}

	@Nullable
	public static Identifier textureFor(Screen s) {
		if (s.browser == null || s.texture == null || s.browser.getTextureView() == null) {
			return null;
		}
		s.texture.update();
		return s.textureId;
	}

	private static boolean tryCreate(Screen s) {
		var init = CefService.initialize();
		if (!init.isDone()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		Vec3 me = mc.player != null ? mc.player.position() : s.center;
		List<Screen> live = liveScreens();
		if (live.size() >= MAX_LIVE) {
			Screen farthest = null;
			double fd = -1;
			for (Screen o : live) {
				double d = o.center.distanceToSqr(me);
				if (d > fd) { fd = d; farthest = o; }
			}
			if (farthest == null || fd <= s.center.distanceToSqr(me)) {
				return false; // biz daha uzagiz: sira bize gelmedi
			}
			closeBrowser(farthest, mc.getSoundManager());
		}
		try {
			String start = !s.startUrl.isEmpty() ? s.startUrl : Browsers.homeUrlFor(s.pos);
			start = gate(s, start);
			// Oyuncu istedi ya da sunucu zorluyor: ekranlar kalici profilden ayri cerez baglaminda.
			boolean ephemeral = DoomscrollConfig.get().separateScreenCookies || ServerPolicy.separateCookies();
			s.browser = init.getFuture().join().createBrowser(start, false, ephemeral);
			s.browser.resize(Browsers.screenWidth(), Browsers.screenHeight());
			s.lastSent = start;
			final Screen ref = s;
			s.texture = new CefTexture(() -> ref.browser);
			s.textureId = Doomscroll.id("screen/" + s.key);
			mc.getTextureManager().register(s.textureId, s.texture);
			s.browser.setMessageListener(msg -> Minecraft.getInstance().execute(() -> onPageMessage(ref, msg)));
			s.browser.getCefBrowser().executeJavaScript(
					"setInterval(function(){document.querySelectorAll('video,audio').forEach(function(m){m.muted=false;m.volume=1;});},2000);"
					+ Browsers.reporterJs(),
					start, 0);
			s.pausedForOff = false;
			s.localStampMs = 0L;
			s.remoteStampMs = 0L;
			Doomscroll.LOGGER.info("ekran tarayicisi acildi {} -> {}", s.pos, start);
			return true;
		} catch (Exception e) {
			Doomscroll.LOGGER.error("ekran tarayicisi acilamadi", e);
			return false;
		}
	}

	private static void applyOnState(Screen s) {
		if (s.browser == null) return;
		if (!s.on && !s.pausedForOff) {
			s.pausedForOff = true;
			js(s, "document.querySelectorAll('video,audio').forEach(function(m){m.pause();});");
		} else if (s.on && s.pausedForOff) {
			s.pausedForOff = false;
			js(s, "document.querySelectorAll('video,audio').forEach(function(m){m.play().catch(function(){});});");
		}
	}

	// ---------- adres senkronu ----------

	/** Sunucudan gelen adres (kenar tetiklemeli; bizim gonderdigimiz geri gelirse yok sayilir). */
	public static void applyServerUrl(Screen s, @Nullable String u) {
		if (u == null || u.isEmpty()) return;
		if (u.equals(s.serverUrlSeen)) return;
		s.serverUrlSeen = u;
		if (u.equals(s.lastSent)) return;
		s.lastSent = u;
		if (s.viewerMode) return; // yayin izlenirken sayfa alici sayfasi; adres yayin bitince uygulanir
		if (s.browser == null) {
			s.startUrl = u;
			return;
		}
		String target = gate(s, u);
		if (!target.equals(s.currentUrl())) {
			Doomscroll.LOGGER.info("[sync] ekran {} adres uygulaniyor: {}", s.pos, target);
			s.browser.getCefBrowser().loadURL(target);
		}
	}

	/** Pasif adres bildirimi: yalnizca ekrani suren (kontrol bende ya da bos) yayar. */
	private static void syncTick() {
		for (Screen s : SCREENS.values()) {
			if (s.browser == null || !s.drives()) continue;
			String cur = s.currentUrl();
			if (s.viewerMode || cur.isEmpty() || cur.startsWith("about:") || cur.startsWith("data:") || cur.equals(s.lastSent)) continue;
			s.lastSent = cur;
			Doomscroll.LOGGER.info("[sync] ekran {} adres gonderildi: {}", s.pos, cur);
			ClientPlayNetworking.send(new SetScreenUrlPayload(s.pos, cur, false));
		}
	}

	/**
	 * Bilerek yonlendirme: adres sunucuya gider (kontrol alinir), herkesin ekrani - bizimki dahil - sunucudan
	 * gelen adresi yukler. Ekranin yerel kaydi olmasa da (uzak kumanda) calisir.
	 */
	/**
	 * Adresi sunucu politikasindan gecirir: engelliyse ana menuye, izleyici onayi gerekiyorsa
	 * onay kartina cevirir. Gercek adres {@code pendingUrl}'de bekler.
	 */
	private static String gate(Screen s, String url) {
		if (!ServerPolicy.allows(url)) {
			s.pendingUrl = null;
			notifyBlocked(url);
			return Browsers.homeUrlFor(s.pos);
		}
		if (ServerPolicy.needsConsent(url)) {
			s.pendingUrl = url;
			return HomePages.consentUrl(s.pos, ServerPolicy.host(url), ownerName(s));
		}
		s.pendingUrl = null;
		return url;
	}

	/** Onay verildi: bekleyen adresi ac. */
	public static void consentGiven(@Nullable BlockPos anchor) {
		Screen s = get(anchor);
		if (s == null || s.browser == null) {
			return;
		}
		String u = s.pendingUrl;
		s.pendingUrl = null;
		if (u != null && ServerPolicy.allows(u)) {
			s.browser.getCefBrowser().loadURL(u);
		}
	}

	/** Onay verilmedi: ana menuye don. */
	public static void consentDeclined(@Nullable BlockPos anchor) {
		Screen s = get(anchor);
		if (s == null || s.browser == null) {
			return;
		}
		s.pendingUrl = null;
		s.browser.getCefBrowser().loadURL(Browsers.homeUrlFor(s.pos));
	}

	private static String ownerName(Screen s) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.level.getBlockEntity(s.pos) instanceof ScreenBlockEntity be) {
			return be.getOwnerName();
		}
		return "";
	}

	private static long lastBlockedNoticeMs = 0L;

	private static void notifyBlocked(String url) {
		long now = System.currentTimeMillis();
		if (now - lastBlockedNoticeMs < 3000L) {
			return;
		}
		lastBlockedNoticeMs = now;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable(
					"message.doomscroll.policy_blocked", ServerPolicy.host(url)));
		}
	}

	/**
	 * Yonlendirme denetimi: sayfa kendiliginden engelli bir adrese gittiyse (kisaltici,
	 * reklam yonlendirmesi) hemen ana menuye don. Sunucu yalnizca paylasilan adresi gorur;
	 * bu kontrol her istemcide, her ekran icin ayri calisir.
	 */
	private static void guard(Screen s) {
		if (s.browser == null) {
			return;
		}
		String cur = s.currentUrl();
		if (cur.isEmpty() || ServerPolicy.allows(cur)) {
			return;
		}
		Doomscroll.LOGGER.info("[politika] ekran {} engelli adrese gitti, geri aliniyor: {}", s.pos, cur);
		notifyBlocked(cur);
		s.pendingUrl = null;
		s.lastSent = "";
		s.browser.getCefBrowser().loadURL(Browsers.homeUrlFor(s.pos));
	}

	public static void requestNavigate(@Nullable BlockPos anchor, @Nullable String url) {
		if (anchor == null || url == null || url.isEmpty()) return;
		ClientPlayNetworking.send(new SetScreenUrlPayload(anchor.immutable(), url, true));
	}

	/** Sunucu reddetti (kilit): ekrani sunucudaki adrese geri esle. */
	public static void forceResync(BlockPos anchor) {
		Screen s = get(anchor);
		if (s == null) return;
		s.lastSent = "";
		s.serverUrlSeen = null;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.level.getBlockEntity(s.pos) instanceof ScreenBlockEntity be) {
			applyServerUrl(s, be.getUrl());
		}
	}

	// ---------- kontrol ----------

	/** Bilerek yapilan eylem (tiklama, tekerlek, tus, kumanda): kontrol bende degilse iste (sn'de en fazla bir). */
	public static void noteExplicit(@Nullable Screen s) {
		if (s == null || s.isController()) return;
		long now = System.currentTimeMillis();
		if (now - s.lastTakeMs < 1000L) return;
		s.lastTakeMs = now;
		ClientPlayNetworking.send(new ScreenControlPayload(s.pos, ScreenControlPayload.TAKE));
	}

	/** Sunucu kontrol istegini reddetti (kilit): 10 sn boyunca yeniden isteme (tus basinca surekli yenilenmesin). */
	public static void noteDenied(@Nullable BlockPos anchor) {
		Screen s = get(anchor);
		if (s != null) {
			s.lastTakeMs = System.currentTimeMillis() + 10_000L;
		}
	}

	public static void noteExplicitActive() {
		noteExplicit(active());
	}

	public static void noteExplicitAt(@Nullable BlockPos anchor) {
		noteExplicit(get(anchor));
	}

	public static void sendControl(@Nullable BlockPos anchor, int action) {
		if (anchor == null) return;
		ClientPlayNetworking.send(new ScreenControlPayload(anchor.immutable(), action));
	}

	// ---------- video konumu ----------

	/** Sayfadan gelen rapor: {"t":sn,"d":sn,"p":0/1,"u":adres}. Oyun is parcaciginda. */
	private static void onPageMessage(Screen s, String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			if (o.has("fsArmed")) {
				Browsers.armedClick(s.browser); // sinema: sayfa tam ekran icin tik bekliyor
				return;
			}
			if (o.has("popup")) {
				s.lastPopup = o.get("popup").getAsString();
				Browsers.popupNotice(s.lastPopup);
				return;
			}
			if (o.has("focus")) {
				DirectControl.onPageFocus(s.pos, o.get("focus").getAsInt() == 1); // yazi alani: klavyeyi bagla/birak
				return;
			}
			if (o.has("home")) {
				if (HomePages.isHome(s.currentUrl())) {
					HomePages.onScreenCommand(s.pos, o); // ana menu komutu (siradan oynat / cikar)
				}
				return;
			}
			if (o.has("ended")) {
				// yalnizca asil video (raporu veren cerceve, >= 30 sn): reklam/onizleme videolari siradakini tetiklemesin
				long fr = o.has("fr") ? o.get("fr").getAsLong() : 0L;
				double d = o.has("d") ? o.get("d").getAsDouble() : 0;
				boolean ad = o.has("ad") && o.get("ad").getAsInt() == 1;
				if (fr == s.localFrame && d >= 8 && !ad) {
					ScreenQueue.onEnded(s);
				}
				return;
			}
			if (o.has("sb")) {
				return;
			}
			if (o.has("bc")) {
				Broadcast.onHostMessage(s, o.get("bc").getAsString());
				return;
			}
			if (o.has("bcerr")) {
				Doomscroll.LOGGER.warn("[yayin] alici sayfa parca ekleyemedi ({})", s.pos.toShortString());
				return;
			}
			if (o.has("bcinfo")) {
				Broadcast.onViewerReady(s);
				return;
			}
			if (o.has("bct")) {
				s.viewerTime = o.get("bct").getAsDouble();
				return;
			}
			if (o.has("s") && !o.has("t")) {
				s.subtitle = o.get("s").getAsString();
				s.subtitleStampMs = System.currentTimeMillis();
				return;
			}
			long fr = o.has("fr") ? o.get("fr").getAsLong() : 0L;
			double dur = o.has("d") ? o.get("d").getAsDouble() : 0;
			long nowMs = System.currentTimeMillis();
			if (fr != s.localFrame && s.localStampMs > 0 && nowMs - s.localStampMs < 2500 && dur < s.localDuration) {
				return; // baska cercevedeki daha kisa video (onizleme/reklam): asil oynaticinin raporunu ezme
			}
			s.localFrame = fr;
			s.localTime = o.has("t") ? o.get("t").getAsDouble() : -1;
			s.localDuration = dur;
			if (dur >= 120 && !s.viewerMode) {
				maybeBroadcastHint(s);
			}
			s.localPaused = o.has("p") && o.get("p").getAsInt() == 1;
			String u = o.has("u") ? o.get("u").getAsString() : "";
			if (o.has("ti")) {
				s.localTitle = o.get("ti").getAsString();
			}
			if (!u.equals(s.localUrl)) {
				s.localUrl = u;
				onLocalUrlChanged(s);
			}
			s.localStampMs = System.currentTimeMillis();
		} catch (Exception e) {
			return;
		}
		if (!s.isController() && s.remoteStampMs > 0 && !s.viewerMode) {
			maybeSync(s);
		}
	}

	/** YouTube oynatici API'siyle en yuksek kaliteyi ekran cozunurlugune sinirlar (video basina bir kez; sayfa hazir olana kadar dener). */
	/**
	 * YouTube kalitesini ekranin gosterebildigiyle <b>sinirlar</b>, sabitlemez.
	 *
	 * <p>Eskiden alt ve ust sinir ayni veriliyordu (setPlaybackQualityRange(q, q)), yani oynatici
	 * tek bir bicime cakiliyordu. YouTube'da ses izi video bicim kumesiyle birlikte seciliyor;
	 * araligi tek degere kisinca oynatici dusuk bitrate'li ses izine dusebiliyor ve ses boguk
	 * geliyordu. Alt siniri serbest birakmak ayni islemci kazancini veriyor ama sesi bozmuyor.
	 */
	static String qualityJs() {
		String q = DoomscrollConfig.get().youtubeQualityCap();
		return "(function(){var p=document.getElementById('movie_player');if(!p||!p.setPlaybackQualityRange)return;"
				+ "try{p.setPlaybackQualityRange('small','" + q + "');}catch(e){}})();";
	}

	/** Video basina 3 deneme (2 sn arayla): oynatici API'si sayfa acilirken hazir olmayabilir. */
	static void applyQualityCap(Screen s) {
		String id = YouTube.videoId(s.localUrl);
		if (id == null || id.equals(s.qualityAppliedId)) return;
		js(s, qualityJs());
		if (++s.qualityTries >= 3) {
			s.qualityAppliedId = id;
			s.qualityTries = 0;
		}
	}

	private static final java.util.Set<String> BROADCAST_HINTED = new java.util.HashSet<>();

	/** Kisiye gore degisen (buyuk video sitesi olmayan) bir sayfada video oynarken bir kez: yayin modunu hatirlat. */
	static void maybeBroadcastHint(Screen s) {
		if (!s.drives() || s.broadcaster != null || s.localDuration < 120) return;
		String host;
		try {
			host = java.net.URI.create(s.localUrl).getHost();
		} catch (Exception e) {
			return;
		}
		if (host == null) return;
		host = host.toLowerCase(java.util.Locale.ROOT);
		if (host.contains("youtube") || host.contains("youtu.be") || host.contains("twitch") || host.contains("kick.com")
				|| host.contains("instagram") || host.contains("tiktok") || host.contains("vimeo") || host.contains("dailymotion")) return;
		if (!BROADCAST_HINTED.add(host)) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.translatable("message.doomscroll.broadcast.hint"));
		}
	}

	/** Sayfanin adresi degisti (SPA dahil). */
	private static void onLocalUrlChanged(Screen s) {
		s.qualityTries = 0;
	}

	/** Sayfa basligi (" - YouTube" gibi ekler atilmis); yoksa "". */
	public static String pageTitle(@Nullable BlockPos anchor) {
		Screen s = get(anchor);
		if (s == null || s.localTitle == null) return "";
		String t = s.localTitle.trim();
		int dash = t.lastIndexOf(" - ");
		if (dash > 8) t = t.substring(0, dash);
		return t;
	}

	/** Cozunurluk degisince acik YouTube sayfalarina yeni kalite sinirini hemen uygula. */
	public static void refreshQuality() {
		for (Screen s : liveScreens()) {
			String id = YouTube.videoId(s.localUrl);
			if (id != null) {
				js(s, qualityJs());
				s.qualityAppliedId = id;
			}
		}
	}

	/** Kontrolcunun konumu geldi (sunucudan). */
	public static void applyRemoteTime(BlockPos anchor, float time, float duration, boolean paused) {
		Screen s = get(anchor);
		if (s == null) return;
		s.remoteTime = time;
		s.remoteDuration = duration;
		s.remotePaused = paused;
		s.remoteStampMs = System.currentTimeMillis();
		if (!s.isController() && !s.viewerMode) {
			maybeSync(s);
		}
	}

	/** Izleyici: kontrolcuden cok sapmissa videoyu hizala; duraklatma durumunu esle. */
	private static void maybeSync(Screen s) {
		if (s.browser == null || !s.on || !DoomscrollConfig.get().syncPlayback) return;
		long now = System.currentTimeMillis();
		if (now - s.remoteStampMs > 4000L) return;
		if (s.remoteDuration < SYNC_MIN_DURATION) return;
		if (now - s.localStampMs > FRESH_MS || s.localDuration <= 0) return;
		if (Math.abs(s.localDuration - s.remoteDuration) > 2.5) return; // ayni video degil (henuz)
		double remoteNow = s.remotePaused ? s.remoteTime : s.remoteTime + (now - s.remoteStampMs) / 1000.0;
		double localNow = s.localPaused ? s.localTime : s.localTime + (now - s.localStampMs) / 1000.0;
		if (s.remotePaused != s.localPaused && now - s.lastPlayPauseMs > 2000L) {
			s.lastPlayPauseMs = now;
			js(s, "window.__dsSetPaused&&window.__dsSetPaused(" + (s.remotePaused ? 1 : 0) + ");");
		}
		double drift = remoteNow - localNow;
		if (Math.abs(drift) > SYNC_DRIFT && now - s.lastSeekMs > 4000L) {
			s.lastSeekMs = now;
			double target = Math.max(0, remoteNow + (s.remotePaused ? 0 : 0.3));
			Doomscroll.LOGGER.info("[sync] ekran {} konum hizalandi: sapma {} sn", s.pos.toShortString(), String.format(Locale.ROOT, "%.1f", drift));
			js(s, "window.__dsSeek&&window.__dsSeek(" + String.format(Locale.ROOT, "%.2f", target) + ");");
		}
	}

	/** Kontrolcu: konumumu (ya da yalnizca "buradayim" sinyalini) yolla. */
	private static void sendTime(Screen s) {
		double t = s.localNow();
		boolean has = t >= 0 && s.localDuration > 0;
		ClientPlayNetworking.send(new ScreenTimePayload(s.pos, has ? (float) t : -1f, has ? (float) s.localDuration : 0f, has && s.localPaused));
	}

	/** "12:34/45:00" (yalnizca 30 sn'den uzun videolarda); yoksa "". */
	public static String timeLabel(@Nullable BlockPos anchor) {
		Screen s = get(anchor);
		if (s == null) return "";
		double t = s.localNow();
		if (t < 0 || s.localDuration < SYNC_MIN_DURATION) return "";
		return clock(t) + "/" + clock(s.localDuration);
	}

	private static String clock(double sec) {
		int t = (int) Math.max(0, sec);
		int h = t / 3600, m = (t % 3600) / 60, sc = t % 60;
		return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sc) : String.format(Locale.ROOT, "%d:%02d", m, sc);
	}

	// ---------- tick ----------

	public static void tick(Minecraft mc) {
		tick++;
		if (mc.level == null || mc.player == null) {
			closeAll();
			return;
		}
		SoundManager sm = mc.getSoundManager();
		Vec3 me = mc.player.position();
		Iterator<Map.Entry<BlockPos, Screen>> it = SCREENS.entrySet().iterator();
		while (it.hasNext()) {
			Screen s = it.next().getValue();
			BlockEntity be = mc.level.getBlockEntity(s.pos);
			boolean valid = be instanceof ScreenBlockEntity sbe && sbe.isAnchor();
			double dist = s.center.distanceTo(me);
			if (!valid || dist > LIVE_DISTANCE) {
				closeBrowser(s, sm);
				it.remove();
				continue;
			}
			if (s.browser == null) continue;
			if (tick % 10 == 0) {
				guard(s);
				if (be instanceof ScreenBlockEntity sbe2) {
					java.util.UUID owner = sbe2.getOwner();
					s.mine = owner == null || owner.equals(mc.player.getUUID());
				}
			}
			// ses
			boolean wantsSound = s.on && s.browser.hasAudioStream();
			if (wantsSound) {
				// Ornekleme hizi degistiyse (olcum duzeltmesi) akisi dogru hizla yeniden ac
				if (s.sound != null && s.soundRate != s.browser.audioSampleRate()) {
					sm.stop(s.sound);
					s.sound = null;
				}
				if (s.sound == null || !sm.isActive(s.sound)) {
					final Screen ref = s;
					s.sound = new BrowserSoundInstance(Doomscroll.SCREEN_SOUND,
							() -> ref.soundPos(listenerPos()),
							() -> Browsers.isMuted() ? 0f
									: Browsers.getUserVolume() * ref.screenVolume
											* (ref.mine ? 1f : Browsers.getOthersScreenVolume()),
							() -> ref.browser != null && ref.on,
							Doomscroll.id("screen/" + s.key));
					s.soundRate = s.browser.audioSampleRate();
					s.refApplied = false;
					s.lastSprPos = null;
					sm.play(s.sound);
				}
				if (SoundPhysicsBridge.available()) {
					// Ortam hesabi pahali (isin izleme, ses is parcaciginda): saniyede bir ve yalnizca konum degistiyse
					if (tick % 20 == 0) {
						Vec3 lp = listenerPos();
						Vec3 sp = s.soundPos(lp);
						if (s.lastSprPos == null || sp.distanceToSqr(s.lastSprPos) > 0.25 || lp.distanceToSqr(s.lastSprListener) > 0.25) {
							s.lastSprPos = sp;
							s.lastSprListener = lp;
							SoundPhysicsBridge.refresh(s.sound, sp, Doomscroll.SCREEN_SOUND.location());
						}
					}
				} else if (!s.refApplied && tick % 5 == 0) {
					// Ekranin onunde (4 blok) tam ses, sonra dogrusal azalma
					s.refApplied = SoundTuning.applyReferenceDistance(s.sound, 4f);
				}
			} else if (s.sound != null) {
				sm.stop(s.sound);
				s.sound = null;
			}
			// kare hizi: bakilan/yakin ekran tam, gorus disindakiler dusuk (video akmaya devam eder, seyrek boyanir)
			if (tick % 10 == 0) {
				int want = desiredFps(s, mc);
				if (want != s.appliedFps) {
					s.appliedFps = want;
					s.browser.setFrameRate(want);
				}
			}
			if (!s.on) {
				Broadcast.onScreenOff(s); // kapali ekranda kodlayici bos yere calismasin
				continue;
			}
			// sayfa raporcusu (idempotent; sayfa degisince yeniden kurulur)
			if (tick % 40 == 0) {
				Browsers.jsAllFrames(s.browser, Browsers.reporterJs());
			}
			// otomatik gecis izleyicisi: yalnizca ekrani suren
			if (tick % 40 == 20 && DoomscrollConfig.get().autoScroll && s.drives() && Browsers.isShortFormPage(s.currentUrl())) {
				js(s, Browsers.autoNextJs());
			}
			// YouTube kalite siniri (video basina birkac deneme)
			if (tick % 40 == 5) {
				applyQualityCap(s);
			}
			// yayin: yayinci yakalamayi surdurur, izleyici alici sayfasina gecer/doner
			if (tick % 10 == 3) {
				Broadcast.tick(s);
			}
			// kontrolcu: konum + "buradayim" (2 sn'de bir)
			if (tick % 40 == 10 && s.isController()) {
				sendTime(s);
			}
			// reklam temizleyici: ana cerceve + oynatici iframe'leri (2 sn'de bir, idempotent)
			if (tick % 40 == 30 && DoomscrollConfig.get().adBlock) {
				Browsers.jsAllFrames(s.browser, Browsers.adCleanJs());
			}
			// liste kozmetik kurallari (##): yeni cerceve/adres gorunce stil enjekte eder, ucuz
			if (tick % 10 == 5 && DoomscrollConfig.get().adBlock) {
				s.browser.applyCosmetics();
			}
		}
		if (tick % 20 == 0) {
			syncTick();
		}
	}

	/**
	 * Ekran icin istenen boyama kare hizi: kapaliysa 5; bakis yonunde (yaklasik gorus konisi) ve 24 blok icindeyse tam;
	 * gorus konisinde ama uzaksa yarim; gorus disindaysa 10 (ses/oynatma surer, doku seyrek yenilenir).
	 */
	/**
	 * Kare hizi: bakilan ve yeterince yakin ekran tam hizda, gorus disindakiler dusuk.
	 * Sinirlar panel boyuyla buyur: buyuk bir perde uzaktan da gorusu doldurur, 1x1 ekran doldurmaz.
	 * Olculer panelin ortasindan alinir; genis perdede kenarda dururken orta uzak kalmasin diye yakin
	 * esigi de panelle birlikte buyur.
	 */
	private static int desiredFps(Screen s, Minecraft mc) {
		int base = DoomscrollConfig.get().browserFps;
		if (!s.on) return 5;
		if (mc.player == null) return base;
		Vec3 eye = mc.player.getEyePosition();
		Vec3 to = s.panelCenter.subtract(eye);
		double dist = to.length();
		double span = Math.max(s.panelW, s.panelH);
		if (dist < 4.0 + span * 0.5) return base; // dibindeyken her zaman tam
		Vec3 look = mc.player.getViewVector(1.0f);
		double cos = to.normalize().dot(look);
		boolean inView = cos > 0.15; // ~80 derece koni (genis FOV + buyuk panel payi)
		if (!inView) return Math.min(base, 10);
		// Tam hiz yaricapi: 1x1 ekranda 24 blok, her ek blok 3 blok daha; tarayicinin kapanma mesafesinde durur
		double full = Math.min(LIVE_DISTANCE, 24.0 + 3.0 * (span - 1));
		if (dist <= full) return base;
		return Math.min(base, Math.max(20, base / 2));
	}

	/** Aktif ekranda son engellenen popup adresi ("" yoksa). */
	public static String lastPopup() {
		Screen s = active();
		return s == null ? "" : s.lastPopup;
	}

	/** HUD icin: 24 blok icindeki en yakin acik ekranin taze (4 sn) altyazisi; yoksa null. */
	@Nullable
	public static String currentSubtitle() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		long now = System.currentTimeMillis();
		Vec3 me = mc.player.getEyePosition();
		Screen best = null;
		double bd = 24.0 * 24.0;
		for (Screen s : SCREENS.values()) {
			if (s.browser == null || !s.on || s.subtitle.isEmpty() || now - s.subtitleStampMs > 4000L) continue;
			double d = s.soundPos(me).distanceToSqr(me);
			if (d < bd) {
				bd = d;
				best = s;
			}
		}
		return best == null ? null : best.subtitle;
	}

	/** Ses akislarini durdurur; bir sonraki tick yeni ayarlarla (parca suresi vb.) yeniden acilir. */
	public static void restartSounds() {
		SoundManager sm = Minecraft.getInstance().getSoundManager();
		for (Screen s : SCREENS.values()) {
			if (s.sound != null) {
				sm.stop(s.sound);
				s.sound = null;
			}
		}
	}

	// ---------- aktif ekran (kumanda, komutlar, bak-tikla) ----------

	public static void setActive(@Nullable BlockPos anchor) {
		activePos = anchor == null ? null : anchor.immutable();
	}

	/** Kumanda paneli acikken true: yalnizca kumandanin bagli oldugu ekran yonetilir (bakilan/en yakin devreye girmez). */
	private static boolean remoteLock = false;

	public static void setRemoteLock(boolean lock) {
		remoteLock = lock;
	}

	/** Bakilan ekran; yoksa kumandanin/komutun sectigi; yoksa en yakin canli ekran. */
	@Nullable
	public static Screen active() {
		if (remoteLock) {
			return activePos == null ? null : SCREENS.get(activePos); // bagli ekran yoksa/uzaktaysa hicbir sey
		}
		ScreenTracker.Hit hit = DirectControl.currentHit();
		if (hit != null) {
			Screen s = SCREENS.get(hit.pos());
			if (s != null) return s;
		}
		if (activePos != null) {
			Screen s = SCREENS.get(activePos);
			if (s != null) return s;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		Vec3 me = mc.player.position();
		Screen best = null;
		double bd = Double.MAX_VALUE;
		for (Screen s : SCREENS.values()) {
			if (s.browser == null) continue;
			double d = s.center.distanceToSqr(me);
			if (d < bd) { bd = d; best = s; }
		}
		return best;
	}

	/** Yonlendirilecek ekranin anchor'u: aktif ekran; kumanda kilidindeyse bagli ekran (yerel kaydi olmasa da). */
	@Nullable
	public static BlockPos activeAnchor() {
		Screen s = active();
		if (s != null) return s.pos;
		return remoteLock ? activePos : null;
	}

	@Nullable
	public static CefBrowserView activeBrowser() {
		Screen s = active();
		return s == null ? null : s.browser;
	}

	@Nullable
	public static Vec3 activeCenter() {
		Screen s = active();
		return s == null ? null : s.center;
	}

	public static boolean activeOn() {
		Screen s = active();
		return s != null && s.on;
	}

	@Nullable
	public static CefBrowserView browserAt(@Nullable BlockPos anchor) {
		if (anchor == null) return null;
		Screen s = SCREENS.get(anchor.immutable());
		return s == null ? null : s.browser;
	}

	/** Tablet yansitma hedefi: bakilan ekran (48 blok), yoksa 24 blok icindeki en yakin ekran. */
	@Nullable
	public static BlockPos castTarget() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		ScreenTracker.Hit hit = ScreenTracker.raycast(mc.player.getEyePosition(), mc.player.getViewVector(1.0f), 48.0, true);
		if (hit != null) return hit.pos();
		BlockPos n = ScreenTracker.nearestAnchor(mc.player.position(), true);
		if (n != null && Vec3.atCenterOf(n).distanceTo(mc.player.position()) <= 24.0) return n;
		return null;
	}

	public static List<Screen> liveScreens() {
		List<Screen> out = new ArrayList<>();
		for (Screen s : SCREENS.values()) if (s.browser != null) out.add(s);
		return out;
	}

	public static void resizeAll() {
		for (Screen s : liveScreens()) {
			s.browser.resize(Browsers.screenWidth(), Browsers.screenHeight());
		}
	}

	public static String debugInfo() {
		StringBuilder sb = new StringBuilder("ekranlar=" + SCREENS.size() + " canli=" + liveScreens().size());
		for (Screen s : SCREENS.values()) {
			sb.append("\n  ").append(s.pos.toShortString())
					.append(s.browser != null ? " [canli] " : " [kapali] ")
					.append(s.on ? "acik " : "kapali ")
					.append("kontrol=").append(s.controllerLabel())
					.append(s.locked ? " kilitli " : " ")
					.append(timeLabel(s.pos)).append(' ')
					.append(s.currentUrl());
		}
		return sb.toString();
	}

	// ---------- ses akisi ----------

	@Nullable
	public static CefBrowserView browserForSoundPath(Identifier id) {
		String path = id.getPath(); // sounds/screen/<key>.ogg
		int a = path.lastIndexOf('/');
		int b = path.lastIndexOf('.');
		if (a < 0 || b < a) return null;
		String key = path.substring(a + 1, b);
		for (Screen s : SCREENS.values()) {
			if (s.key.equals(key)) return s.browser;
		}
		return null;
	}

	// ---------- kapatma ----------

	private static void js(Screen s, String code) {
		if (s.browser != null) {
			s.browser.getCefBrowser().executeJavaScript(code, s.currentUrl(), 0);
		}
	}

	private static void closeBrowser(Screen s, SoundManager sm) {
		Broadcast.onBrowserClosed(s);
		if (s.sound != null) {
			sm.stop(s.sound);
			s.sound = null;
		}
		if (s.browser != null) {
			try {
				s.browser.setMessageListener(null);
				js(s, "document.querySelectorAll('video,audio').forEach(function(m){m.pause();m.src='';});");
				s.browser.close();
			} catch (Exception ignored) {
			}
			Doomscroll.LOGGER.info("ekran tarayicisi kapatildi {}", s.pos);
			s.browser = null;
		}
		if (s.textureId != null) {
			try {
				Minecraft.getInstance().getTextureManager().release(s.textureId);
			} catch (Exception ignored) {
			}
			s.textureId = null;
		}
		s.texture = null;
		s.localStampMs = 0L;
		s.appliedFps = -1;
		s.startUrl = s.lastSent; // tekrar acilirsa kaldigi adresten
	}

	public static void closeAll() {
		SoundManager sm = Minecraft.getInstance().getSoundManager();
		for (Screen s : SCREENS.values()) closeBrowser(s, sm);
		SCREENS.clear();
		activePos = null;
	}

	/** Ekran blogu kirildi: kaydi kapat. */
	public static void removed(BlockPos pos) {
		Screen s = SCREENS.remove(pos.immutable());
		if (s != null) closeBrowser(s, Minecraft.getInstance().getSoundManager());
	}
}
