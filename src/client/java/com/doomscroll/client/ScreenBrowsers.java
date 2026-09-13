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
 * One browser per screen (the anchor of a multi-block panel). Opens when the screen is rendered; stays live
 * while the player is nearby (even out of view) and closes once they move away. At most MAX_LIVE screens are
 * live at once (the nearest ones); the rest stay black like a switched-off TV. Sound comes from each screen's own position.
 *
 * Control model: every screen has a "controller" on the server (the player driving it). URL changes and
 * auto-advance propagate only from the controller's browser; everyone else follows. Every deliberate action
 * (remote, click, wheel, URL) takes control (unless the screen is locked). On long videos the controller
 * broadcasts its position; viewers realign when they drift.
 */
public final class ScreenBrowsers {
	public static final int MAX_LIVE = 3;
	public static final double LIVE_DISTANCE = 48.0;
	/** A viewer realigns to the controller once it drifts this far (s). */
	private static final double SYNC_DRIFT = 2.5;
	/** No position sync for short (looping) videos (s). */
	private static final double SYNC_MIN_DURATION = 30.0;
	/** A position report older than this is not trusted (ms). */
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
		/** The real URL awaiting viewer consent (while the consent card is shown). */
		@Nullable String pendingUrl = null;
		/** Do I own this screen? (someone else's screen plays at a separate volume level) */
		boolean mine = true;
		@Nullable String serverUrlSeen = null;
		long lastRenderNanos = 0L;
		/** Center of the panel (for the visibility estimate). */
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
		 * Where the sound comes from: the point on the panel surface closest to the listener, just in front of it.
		 * That way a huge screen's sound comes from wherever you stand in front of it, not from a single block.
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

		// control (from the server, arrives with the BE)
		@Nullable UUID controller;
		String controllerName = "";
		boolean locked = false;
		/** The screen's shared volume (stored on the block, same for everyone): applied as a multiplier on top of the personal slider. */
		float screenVolume = 1f;
		long lastTakeMs = 0L;

		// local video state (page report, once a second)
		double localTime = -1;
		double localDuration = 0;
		boolean localPaused = true;
		long localStampMs = 0L;
		String localUrl = "";
		long localFrame = 0; // frame that sent the report (on movie sites the player lives inside an iframe)
		final Browsers.Cinema cinema = new Browsers.Cinema();
		String localTitle = "";
		String qualityAppliedId = ""; // video the YouTube quality cap has been applied to
		// broadcast
		@Nullable UUID broadcaster;
		String broadcasterName = "";
		boolean viewerMode = false;
		boolean viewerHasInit = false;
		boolean viewerReady = false;
		double viewerTime = -1;
		boolean hostActive = false;
		String bcUrl = "";
		int qualityTries = 0;

		// position reported by the controller
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

		/** Is this client driving the screen (I have control, or nobody does)? */
		public boolean drives() {
			return isFree() || isController();
		}

		public boolean isLocked() {
			return locked;
		}

		/** "you" / player name / "none". */
		public String controllerLabel() {
			if (controller == null) return Lang.tr("gui.doomscroll.none");
			if (isController()) return Lang.tr("gui.doomscroll.you");
			return controllerName.isEmpty() ? "?" : controllerName;
		}

		/** Local video position, corrected by the time elapsed since the report; -1 if none. */
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

	/** Listener (eye) position; zero when there is no player. */
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

	/** Called by the renderer (anchor BE) every frame: update the record, open the browser if needed. */
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
				tryCreate(s); // distant screens are not opened (tick would close them right away)
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
				return false; // we are farther away: not our turn yet
			}
			closeBrowser(farthest, mc.getSoundManager());
		}
		try {
			String start = !s.startUrl.isEmpty() ? s.startUrl : Browsers.homeUrlFor(s.pos);
			start = gate(s, start);
			// The player asked for it or the server enforces it: screens use a cookie context separate from the persistent profile.
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
			Doomscroll.LOGGER.info("screen browser opened {} -> {}", s.pos, start);
			return true;
		} catch (Exception e) {
			Doomscroll.LOGGER.error("failed to open screen browser", e);
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

	// ---------- URL sync ----------

	/** URL from the server (edge-triggered; ignored if it is our own URL echoed back). */
	public static void applyServerUrl(Screen s, @Nullable String u) {
		if (u == null || u.isEmpty()) return;
		if (u.equals(s.serverUrlSeen)) return;
		s.serverUrlSeen = u;
		if (u.equals(s.lastSent)) return;
		s.lastSent = u;
		if (s.viewerMode) return; // while watching a broadcast the page is the receiver page; the URL is applied once the broadcast ends
		if (s.browser == null) {
			s.startUrl = u;
			return;
		}
		String target = gate(s, u);
		if (!target.equals(s.currentUrl())) {
			Doomscroll.LOGGER.info("[sync] screen {} applying URL: {}", s.pos, target);
			s.browser.getCefBrowser().loadURL(target);
		}
	}

	/** Passive URL report: only the client driving the screen (I have control, or nobody does) publishes it. */
	private static void syncTick() {
		for (Screen s : SCREENS.values()) {
			if (s.browser == null || !s.drives()) continue;
			String cur = s.currentUrl();
			if (s.viewerMode || cur.isEmpty() || cur.startsWith("about:") || cur.startsWith("data:") || cur.equals(s.lastSent)) continue;
			s.lastSent = cur;
			Doomscroll.LOGGER.info("[sync] screen {} URL sent: {}", s.pos, cur);
			ClientPlayNetworking.send(new SetScreenUrlPayload(s.pos, cur, false));
		}
	}

	/**
	 * Deliberate navigation: the URL goes to the server (taking control), and everyone's screen - ours included -
	 * loads the URL that comes back from the server. Works even without a local record of the screen (remote control).
	 */
	/**
	 * Runs the URL through the server policy: redirects to the home menu if it is blocked, or to the
	 * consent card if viewer consent is required. The real URL waits in {@code pendingUrl}.
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

	/** Consent given: open the pending URL. */
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

	/** Consent declined: go back to the home menu. */
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
	 * Navigation guard: if the page navigated to a blocked URL on its own (link shortener,
	 * ad redirect), go straight back to the home menu. The server only sees the shared URL;
	 * this check runs on every client, separately for each screen.
	 */
	private static void guard(Screen s) {
		if (s.browser == null) {
			return;
		}
		String cur = s.currentUrl();
		if (cur.isEmpty() || ServerPolicy.allows(cur)) {
			return;
		}
		Doomscroll.LOGGER.info("[policy] screen {} navigated to a blocked URL, reverting: {}", s.pos, cur);
		notifyBlocked(cur);
		s.pendingUrl = null;
		s.lastSent = "";
		s.browser.getCefBrowser().loadURL(Browsers.homeUrlFor(s.pos));
	}

	public static void requestNavigate(@Nullable BlockPos anchor, @Nullable String url) {
		if (anchor == null || url == null || url.isEmpty()) return;
		ClientPlayNetworking.send(new SetScreenUrlPayload(anchor.immutable(), url, true));
	}

	/** Server refused (locked): resync the screen to the server's URL. */
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

	// ---------- control ----------

	/** Deliberate action (click, wheel, key, remote): request control if I don't have it (at most once per second). */
	public static void noteExplicit(@Nullable Screen s) {
		if (s == null || s.isController()) return;
		long now = System.currentTimeMillis();
		if (now - s.lastTakeMs < 1000L) return;
		s.lastTakeMs = now;
		ClientPlayNetworking.send(new ScreenControlPayload(s.pos, ScreenControlPayload.TAKE));
	}

	/** Server refused the control request (locked): don't ask again for 10 s (so key presses don't keep re-requesting). */
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

	// ---------- video position ----------

	/** Report from the page: {"t":seconds,"d":seconds,"p":0/1,"u":url}. Runs on the game thread. */
	private static void onPageMessage(Screen s, String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			if (Browsers.cinemaMessage(s.browser, s.cinema, o)) {
				return;
			}
			if (o.has("popup")) {
				s.lastPopup = o.get("popup").getAsString();
				Browsers.popupNotice(s.lastPopup);
				return;
			}
			if (o.has("focus")) {
				DirectControl.onPageFocus(s.pos, o.get("focus").getAsInt() == 1); // text field: bind/release the keyboard
				return;
			}
			if (o.has("home")) {
				if (HomePages.isHome(s.currentUrl())) {
					HomePages.onScreenCommand(s.pos, o); // home menu command (play from queue / remove)
				}
				return;
			}
			if (o.has("ended")) {
				// only the main video (the reporting frame, >= 30 s): ad/preview videos must not trigger the next one
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
				Doomscroll.LOGGER.warn("[broadcast] receiver page could not append a chunk ({})", s.pos.toShortString());
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
				return; // shorter video in another frame (preview/ad): don't overwrite the main player's report
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

	/** Caps the maximum quality to the screen resolution via the YouTube player API (once per video; retries until the page is ready). */
	/**
	 * <b>Caps</b> the YouTube quality at what the screen can display; it does not pin it.
	 *
	 * <p>Previously the lower and upper bounds were the same (setPlaybackQualityRange(q, q)), so the player
	 * was locked to a single format. On YouTube the audio track is chosen together with the video format set;
	 * squeezing the range to a single value could drop the player to a low-bitrate audio track and the
	 * sound came out muffled. Leaving the lower bound free gives the same CPU saving without hurting the audio.
	 */
	static String qualityJs() {
		String q = DoomscrollConfig.get().youtubeQualityCap();
		return "(function(){var p=document.getElementById('movie_player');if(!p||!p.setPlaybackQualityRange)return;"
				+ "try{p.setPlaybackQualityRange('small','" + q + "');}catch(e){}})();";
	}

	/** 3 attempts per video (2 s apart): the player API may not be ready while the page is loading. */
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

	/** Once, when a video plays on a page that differs per person (not a major video site): remind about broadcast mode. */
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

	/** The page's URL changed (including SPA navigation). */
	private static void onLocalUrlChanged(Screen s) {
		s.qualityTries = 0;
	}

	/** Page title (suffixes like " - YouTube" stripped); "" if none. */
	public static String pageTitle(@Nullable BlockPos anchor) {
		Screen s = get(anchor);
		if (s == null || s.localTitle == null) return "";
		String t = s.localTitle.trim();
		int dash = t.lastIndexOf(" - ");
		if (dash > 8) t = t.substring(0, dash);
		return t;
	}

	/** After a resolution change, apply the new quality cap to open YouTube pages right away. */
	public static void refreshQuality() {
		for (Screen s : liveScreens()) {
			String id = YouTube.videoId(s.localUrl);
			if (id != null) {
				js(s, qualityJs());
				s.qualityAppliedId = id;
			}
		}
	}

	/** The controller's position arrived (from the server). */
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

	/** Viewer: realign the video if it has drifted too far from the controller; match the paused state. */
	private static void maybeSync(Screen s) {
		if (s.browser == null || !s.on || !DoomscrollConfig.get().syncPlayback) return;
		long now = System.currentTimeMillis();
		if (now - s.remoteStampMs > 4000L) return;
		if (s.remoteDuration < SYNC_MIN_DURATION) return;
		if (now - s.localStampMs > FRESH_MS || s.localDuration <= 0) return;
		if (Math.abs(s.localDuration - s.remoteDuration) > 2.5) return; // not the same video (yet)
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
			Doomscroll.LOGGER.info("[sync] screen {} position realigned: drift {} s", s.pos.toShortString(), String.format(Locale.ROOT, "%.1f", drift));
			js(s, "window.__dsSeek&&window.__dsSeek(" + String.format(Locale.ROOT, "%.2f", target) + ");");
		}
	}

	/** Controller: send my position (or just the "I'm here" signal). */
	private static void sendTime(Screen s) {
		double t = s.localNow();
		boolean has = t >= 0 && s.localDuration > 0;
		ClientPlayNetworking.send(new ScreenTimePayload(s.pos, has ? (float) t : -1f, has ? (float) s.localDuration : 0f, has && s.localPaused));
	}

	/** "12:34/45:00" (only for videos longer than 30 s); "" otherwise. */
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
			// sound
			boolean wantsSound = s.on && s.browser.hasAudioStream();
			if (wantsSound) {
				// If the sample rate changed (measurement correction), reopen the stream at the right rate
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
					// The environment calculation is expensive (ray tracing, on the audio thread): once a second and only if the position changed
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
					// Full volume in front of the screen (4 blocks), then linear falloff
					s.refApplied = SoundTuning.applyReferenceDistance(s.sound, 4f);
				}
			} else if (s.sound != null) {
				sm.stop(s.sound);
				s.sound = null;
			}
			// frame rate: the looked-at/nearby screen runs at full rate, out-of-view ones low (video keeps playing, painted less often)
			if (tick % 10 == 0) {
				int want = desiredFps(s, mc);
				if (want != s.appliedFps) {
					s.appliedFps = want;
					s.browser.setFrameRate(want);
				}
			}
			if (!s.on) {
				Broadcast.onScreenOff(s); // don't let the encoder run for nothing on a switched-off screen
				continue;
			}
			// page reporter (idempotent; reinstalled when the page changes)
			if (tick % 40 == 0) {
				Browsers.jsAllFrames(s.browser, Browsers.reporterJs());
			}
			// auto-advance watcher: only the client driving the screen
			if (tick % 40 == 20 && DoomscrollConfig.get().autoScroll && s.drives() && Browsers.isShortFormPage(s.currentUrl())) {
				js(s, Browsers.autoNextJs());
			}
			// YouTube quality cap (a few attempts per video)
			if (tick % 40 == 5) {
				applyQualityCap(s);
			}
			// broadcast: the broadcaster keeps capturing, the viewer switches to / returns from the receiver page
			if (tick % 10 == 3) {
				Broadcast.tick(s);
			}
			// controller: position + "I'm here" (every 2 s)
			if (tick % 40 == 10 && s.isController()) {
				sendTime(s);
			}
			// ad cleaner: main frame + player iframes (every 2 s, idempotent)
			if (tick % 40 == 30 && DoomscrollConfig.get().adBlock) {
				Browsers.jsAllFrames(s.browser, Browsers.adCleanJs());
			}
			// filter-list cosmetic rules (##): injects styles when it sees a new frame/URL, cheap
			if (tick % 10 == 5 && DoomscrollConfig.get().adBlock) {
				s.browser.applyCosmetics();
			}
		}
		if (tick % 20 == 0) {
			syncTick();
		}
	}

	/**
	 * Desired paint frame rate for the screen: 5 if off; full when in the view direction (approximate view cone) and within 24 blocks;
	 * half when in the view cone but far; 10 when out of view (sound/playback continues, the texture refreshes rarely).
	 */
	/**
	 * Frame rate: a screen that is looked at and close enough runs at full rate, out-of-view ones low.
	 * The thresholds grow with the panel size: a big screen fills the view even from afar, a 1x1 screen does not.
	 * Measurements are taken from the panel center; so that the center doesn't count as far away while standing
	 * at the edge of a wide screen, the near threshold also grows with the panel.
	 */
	private static int desiredFps(Screen s, Minecraft mc) {
		int base = DoomscrollConfig.get().browserFps;
		if (!s.on) return 5;
		if (mc.player == null) return base;
		Vec3 eye = mc.player.getEyePosition();
		Vec3 to = s.panelCenter.subtract(eye);
		double dist = to.length();
		double span = Math.max(s.panelW, s.panelH);
		if (dist < 4.0 + span * 0.5) return base; // always full when right up against it
		Vec3 look = mc.player.getViewVector(1.0f);
		double cos = to.normalize().dot(look);
		boolean inView = cos > 0.15; // ~80 degree cone (allowance for wide FOV + big panels)
		if (!inView) return Math.min(base, 10);
		// Full-rate radius: 24 blocks for a 1x1 screen, 3 more blocks per extra block; capped at the browser close distance
		double full = Math.min(LIVE_DISTANCE, 24.0 + 3.0 * (span - 1));
		if (dist <= full) return base;
		return Math.min(base, Math.max(20, base / 2));
	}

	/** URL of the last blocked popup on the active screen ("" if none). */
	public static String lastPopup() {
		Screen s = active();
		return s == null ? "" : s.lastPopup;
	}

	/** For the HUD: the fresh (4 s) subtitle of the nearest switched-on screen within 24 blocks; null if none. */
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

	/** Stops the audio streams; the next tick reopens them with the new settings (chunk length etc.). */
	public static void restartSounds() {
		SoundManager sm = Minecraft.getInstance().getSoundManager();
		for (Screen s : SCREENS.values()) {
			if (s.sound != null) {
				sm.stop(s.sound);
				s.sound = null;
			}
		}
	}

	// ---------- active screen (remote, commands, look-and-click) ----------

	public static void setActive(@Nullable BlockPos anchor) {
		activePos = anchor == null ? null : anchor.immutable();
	}

	/** True while the remote panel is open: only the screen the remote is bound to is managed (looked-at/nearest don't kick in). */
	private static boolean remoteLock = false;

	/** Cinema button / command: the active screen (the bound one while the remote is open). */
	public static void toggleCinemaActive() {
		Screen s = active();
		if (s != null) {
			Browsers.cinemaToggle(s.browser, s.cinema);
		}
	}

	public static void setRemoteLock(boolean lock) {
		remoteLock = lock;
	}

	/** The looked-at screen; else the one chosen by the remote/command; else the nearest live screen. */
	@Nullable
	public static Screen active() {
		if (remoteLock) {
			return activePos == null ? null : SCREENS.get(activePos); // nothing if the bound screen is missing/far away
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

	/** Anchor of the screen to navigate: the active screen; under remote lock, the bound screen (even without a local record). */
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

	/** Tablet cast target: the looked-at screen (48 blocks), else the nearest screen within 24 blocks. */
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
		StringBuilder sb = new StringBuilder("screens=" + SCREENS.size() + " live=" + liveScreens().size());
		for (Screen s : SCREENS.values()) {
			sb.append("\n  ").append(s.pos.toShortString())
					.append(s.browser != null ? " [live] " : " [closed] ")
					.append(s.on ? "on " : "off ")
					.append("control=").append(s.controllerLabel())
					.append(s.locked ? " locked " : " ")
					.append(timeLabel(s.pos)).append(' ')
					.append(s.currentUrl());
		}
		return sb.toString();
	}

	// ---------- audio stream ----------

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

	// ---------- closing ----------

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
			Doomscroll.LOGGER.info("screen browser closed {}", s.pos);
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
		s.startUrl = s.lastSent; // if reopened, resume from the URL it was on
	}

	public static void closeAll() {
		SoundManager sm = Minecraft.getInstance().getSoundManager();
		for (Screen s : SCREENS.values()) closeBrowser(s, sm);
		SCREENS.clear();
		activePos = null;
	}

	/** Screen block broken: close its record. */
	public static void removed(BlockPos pos) {
		Screen s = SCREENS.remove(pos.immutable());
		if (s != null) closeBrowser(s, Minecraft.getInstance().getSoundManager());
	}
}
