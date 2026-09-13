package com.doomscroll.client;

import com.doomscroll.cef.api.CefBrowserView;
import com.doomscroll.net.BroadcastChunkBroadcast;
import com.doomscroll.net.BroadcastChunkPayload;
import com.doomscroll.net.BroadcastControlBroadcast;
import com.doomscroll.net.BroadcastControlPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Broadcast mode: one player (the host) relays the video and audio on their screen to the others as-is; everyone sees
 * the same picture (on pages that differ per person, like a movie site, one person's click is enough).
 *
 * Host: the page's video is drawn onto a canvas (downscaled), the canvas + the video's audio track are encoded by
 * MediaRecorder as WebM (VP8 + Opus) in 500 ms chunks, the chunks reach Java over the console channel (__DSB__) and go
 * to the server in 30 KB pieces; the server distributes them to subscribers. Viewer: the screen browser opens a small
 * receiver page (MediaSource, sequence mode), chunks are appended via JS and played close to the live edge (~1-2 s latency).
 * When a new viewer arrives the server tells the host to "restart": a fresh init chunk + key frame is produced.
 */
public final class Broadcast {
	private static final Logger LOGGER = LoggerFactory.getLogger("doomscroll-yayin");

	/** Piece reassembly (viewer): screen -> chunk in progress. */
	private static final class Assembler {
		int seq = -1;
		byte[][] parts;
		int have;
		boolean init;
	}

	private static final Map<BlockPos, Assembler> ASM = new HashMap<>();
	/** Chunks waiting until the receiver page is ready (target -> [init, data]). */
	private static final Map<Object, java.util.ArrayDeque<Object[]>> PENDING = new HashMap<>();
	private static boolean tabletReady = false;
	private static final Object TABLET_KEY = "tablet";
	private static int hostChunks = 0;
	private static int viewerChunks = 0;
	/** Smoke test: push our own chunks, as reflected back by the server, into the receiver page on the tablet. */
	static boolean testTabletViewer = false;
	private static boolean testTabletLoaded = false;
	private static long testTabletCreatedMs = 0L;

	private Broadcast() {}

	/** Do not try to send packets without a connection (during shutdown): the exception used to cut the browser close short. */
	private static void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
		try {
			if (Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(payload.type())) {
				ClientPlayNetworking.send(payload);
			}
		} catch (Exception e) {
			LOGGER.debug("could not send packet: {}", e.toString());
		}
	}

	// ---------- commands ----------

	/** Preset: low (640x360, 20 fps, 700 kbps) / normal (960x540, 24, 1200) / high (1280x720, 30, 2500). */
	public static boolean applyPreset(String name) {
		DoomscrollConfig c = DoomscrollConfig.get();
		switch (name == null ? "" : name.toLowerCase(Locale.ROOT)) {
			case "dusuk", "düşük", "low" -> { c.broadcastWidth = 640; c.broadcastFps = 20; c.broadcastKbps = 700; }
			case "normal", "orta" -> { c.broadcastWidth = 960; c.broadcastFps = 24; c.broadcastKbps = 1200; }
			case "yuksek", "yüksek", "high" -> { c.broadcastWidth = 1280; c.broadcastFps = 30; c.broadcastKbps = 2500; }
			default -> { return false; }
		}
		DoomscrollConfig.save();
		// if a broadcast is running, restart it with the new settings
		for (ScreenBrowsers.Screen s : ScreenBrowsers.liveScreens()) {
			if (s.hostActive && s.browser != null) {
				Browsers.jsAllFrames(s.browser, captureJs());
			}
		}
		return true;
	}

	public static String presetLabel() {
		DoomscrollConfig c = DoomscrollConfig.get();
		if (c.broadcastWidth <= 640) return Lang.tr("gui.doomscroll.quality.low");
		if (c.broadcastWidth >= 1280) return Lang.tr("gui.doomscroll.quality.high");
		return Lang.tr("gui.doomscroll.quality.normal");
	}

	public static void requestStart(@Nullable BlockPos anchor) {
		if (anchor != null) send(new BroadcastControlPayload(anchor.immutable(), BroadcastControlPayload.START));
	}

	public static void requestStop(@Nullable BlockPos anchor) {
		if (anchor != null) send(new BroadcastControlPayload(anchor.immutable(), BroadcastControlPayload.STOP));
	}

	@Nullable
	private static UUID me() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : mc.player.getUUID();
	}

	public static boolean isHost(ScreenBrowsers.Screen s) {
		UUID me = me();
		return me != null && s.broadcaster != null && s.broadcaster.equals(me);
	}

	public static int hostChunks() {
		return hostChunks;
	}

	public static int viewerChunks() {
		return viewerChunks;
	}

	// ---------- tick (ScreenBrowsers, every 10 ticks) ----------

	/** Maximum number of pieces per chunk; the array size is never taken straight from the packet. */
	private static final int MAX_PIECES = 512;

	public static void tick(ScreenBrowsers.Screen s) {
		UUID me = me();
		if (me == null || s.browser == null) {
			return;
		}
		boolean hostNow = s.broadcaster != null && s.broadcaster.equals(me);
		boolean viewerNow = s.broadcaster != null && !hostNow;
		if (viewerNow && !s.viewerMode) {
			enterViewer(s);
		} else if (!viewerNow && s.viewerMode) {
			leaveViewer(s);
		}
		if (hostNow) {
			ensureCapture(s);
		} else if (s.hostActive) {
			stopCapture(s);
		}
	}

	// ---------- host ----------

	private static String captureJs() {
		DoomscrollConfig c = DoomscrollConfig.get();
		int w = Math.max(320, Math.min(1920, c.broadcastWidth));
		int h = w * 9 / 16;
		int fps = Math.max(10, Math.min(30, c.broadcastFps));
		int kbps = Math.max(300, Math.min(6000, c.broadcastKbps));
		return "(function(){if(window.__dsBcStop){window.__dsBcStop();}"
				+ "var v=window.__dsVid?window.__dsVid():null;if(!v)return;"
				+ "var W=" + w + ",H=" + h + ",FPS=" + fps + ";var c=document.createElement('canvas');c.width=W;c.height=H;var g=c.getContext('2d');"
				+ "var cs=c.captureStream(FPS);var st=new MediaStream(cs.getVideoTracks());"
				+ "try{var vs=v.captureStream?v.captureStream():(v.mozCaptureStream?v.mozCaptureStream():null);if(vs){var at=vs.getAudioTracks();if(at.length)st.addTrack(at[0]);}}catch(e){}"
				+ "var seq=0,rec=null,timer=null;window.__dsBcOn=true;"
				+ "function draw(){if(!window.__dsBcOn)return;try{g.drawImage(v,0,0,W,H);}catch(e){}timer=setTimeout(draw,1000/FPS);}"
				+ "function onData(e){if(this.__dead||!e.data||!e.data.size)return;var isInit=!this.__sent;this.__sent=true;e.data.arrayBuffer().then(function(b){var u=new Uint8Array(b);var s='';for(var i=0;i<u.length;i+=8192){s+=String.fromCharCode.apply(null,u.subarray(i,Math.min(i+8192,u.length)));}console.log('__DSB__'+(seq++)+':'+(isInit?1:0)+':'+btoa(s));});}"
				+ "function start(){try{rec=new MediaRecorder(st,{mimeType:'video/webm;codecs=vp8,opus',videoBitsPerSecond:" + (kbps * 1000) + ",audioBitsPerSecond:64000});}catch(e){try{rec=new MediaRecorder(st,{mimeType:'video/webm'});}catch(e2){console.log('__DS__{\"bc\":\"unsupported\"}');return;}}rec.ondataavailable=onData;rec.start(500);}"
				+ "window.__dsBcRestart=function(){if(rec){rec.__dead=true;try{rec.stop();}catch(e){}}start();};"
				+ "window.__dsBcStop=function(){window.__dsBcOn=false;if(timer)clearTimeout(timer);if(rec){rec.__dead=true;try{rec.stop();}catch(e){}}try{st.getTracks().forEach(function(t){t.stop();});}catch(e){}window.__dsBcStop=null;window.__dsBcRestart=null;};"
				+ "draw();start();console.log('__DS__{\"bc\":\"started\"}');})();";
	}

	private static void ensureCapture(ScreenBrowsers.Screen s) {
		if (s.hostActive && s.localUrl.equals(s.bcUrl)) {
			return;
		}
		// is the video ready (the reporter reported a duration within the last 3 s)
		if (s.localDuration <= 0 && !(s.localTime > 0)) {
			return;
		}
		if (System.currentTimeMillis() - s.localStampMs > 3000) {
			return;
		}
		final ScreenBrowsers.Screen ref = s;
		s.browser.setChunkListener(msg -> Minecraft.getInstance().execute(() -> onHostChunk(ref, msg)));
		Browsers.jsAllFrames(s.browser, captureJs());
		s.hostActive = true;
		s.bcUrl = s.localUrl;
		LOGGER.info("[broadcast] {} capture started ({})", s.pos.toShortString(), s.localUrl);
	}

	/** Screen turned off: the encoder should not keep running for nothing (tick() did not reach here while the screen was off). */
	public static void onScreenOff(ScreenBrowsers.Screen s) {
		if (s.hostActive) {
			stopCapture(s);
		}
	}

	private static void stopCapture(ScreenBrowsers.Screen s) {
		if (s.browser != null) {
			Browsers.jsAllFrames(s.browser, "if(window.__dsBcStop)window.__dsBcStop();");
			s.browser.setChunkListener(null);
		}
		s.hostActive = false;
		s.bcUrl = "";
		LOGGER.info("[broadcast] {} capture stopped", s.pos.toShortString());
	}

	/** From the page: "__DSB__seq:init:base64" (console channel). Split into pieces and send to the server. */
	private static void onHostChunk(ScreenBrowsers.Screen s, String msg) {
		if (!s.hostActive) return;
		int a = msg.indexOf(':');
		int b = a < 0 ? -1 : msg.indexOf(':', a + 1);
		if (a < 0 || b < 0) return;
		int seq;
		try {
			seq = Integer.parseInt(msg.substring(0, a));
		} catch (NumberFormatException e) {
			return;
		}
		boolean init = msg.charAt(a + 1) == '1';
		byte[] data;
		try {
			data = Base64.getDecoder().decode(msg.substring(b + 1));
		} catch (IllegalArgumentException e) {
			return;
		}
		if (data.length == 0) return;
		int pieces = (data.length + BroadcastChunkPayload.MAX_PIECE - 1) / BroadcastChunkPayload.MAX_PIECE;
		for (int i = 0; i < pieces; i++) {
			int from = i * BroadcastChunkPayload.MAX_PIECE;
			int to = Math.min(data.length, from + BroadcastChunkPayload.MAX_PIECE);
			byte[] part = new byte[to - from];
			System.arraycopy(data, from, part, 0, part.length);
			send(new BroadcastChunkPayload(s.pos, seq, i, pieces, init, part));
		}
		hostChunks++;
	}

	/** Page report: {"bc":"started"|"unsupported"} */
	public static void onHostMessage(ScreenBrowsers.Screen s, String what) {
		if ("unsupported".equals(what)) {
			LOGGER.warn("[broadcast] browser does not support MediaRecorder");
			notice(Lang.tr("message.doomscroll.broadcast.start_failed"));
		}
	}

	public static void onControl(BroadcastControlBroadcast b) {
		ScreenBrowsers.Screen s = ScreenBrowsers.get(b.pos());
		if (s == null || s.browser == null || !s.hostActive) return;
		if (b.action() == BroadcastControlBroadcast.RESTART) {
			Browsers.jsAllFrames(s.browser, "if(window.__dsBcRestart)window.__dsBcRestart();");
		}
	}

	// ---------- viewer ----------

	/** The receiver page is now a separate file (home/receiver.html) and uses the shared visual language. */
	static String receiverUrl() {
		return HomePages.receiverUrl();
	}

	private static void enterViewer(ScreenBrowsers.Screen s) {
		s.viewerMode = true;
		s.viewerHasInit = false;
		s.viewerReady = false;
		s.viewerTime = -1;
		ASM.remove(s.pos);
		PENDING.remove(s.pos.immutable());
		s.browser.getCefBrowser().loadURL(receiverUrl());
		send(new BroadcastControlPayload(s.pos, BroadcastControlPayload.SUBSCRIBE));
		notice(Lang.tr("message.doomscroll.broadcast.viewing", s.broadcasterName.isEmpty()
				? Lang.tr("gui.doomscroll.broadcast.label")
				: Lang.tr("gui.doomscroll.broadcast.host_of", s.broadcasterName)));
		LOGGER.info("[broadcast] {} entered viewer mode", s.pos.toShortString());
	}

	private static void leaveViewer(ScreenBrowsers.Screen s) {
		s.viewerMode = false;
		ASM.remove(s.pos);
		send(new BroadcastControlPayload(s.pos, BroadcastControlPayload.UNSUBSCRIBE));
		ScreenBrowsers.forceResync(s.pos); // go back to the address held by the server
		LOGGER.info("[broadcast] {} left viewer mode", s.pos.toShortString());
	}

	/** While the browser is closing (out of range etc.). */
	static void onBrowserClosed(ScreenBrowsers.Screen s) {
		if (s.viewerMode) {
			s.viewerMode = false;
			ASM.remove(s.pos);
			send(new BroadcastControlPayload(s.pos, BroadcastControlPayload.UNSUBSCRIBE));
		}
		s.hostActive = false;
		s.bcUrl = "";
	}

	public static void onChunk(BroadcastChunkBroadcast b) {
		ScreenBrowsers.Screen s = ScreenBrowsers.get(b.pos());
		if (s == null) return;
		CefBrowserView target = null;
		if (s.viewerMode && s.browser != null) {
			target = s.browser;
		} else if (testTabletViewer && isHost(s)) {
			target = Browsers.getOrCreateTablet();
			// if the browser was just created, loadURL may get swallowed: wait 1.5 s, then load the receiver page
			if (target != null && !testTabletLoaded) {
				if (testTabletCreatedMs == 0L) {
					testTabletCreatedMs = System.currentTimeMillis();
				} else if (System.currentTimeMillis() - testTabletCreatedMs > 1500L) {
					testTabletLoaded = true;
					tabletReady = false;
					target.getCefBrowser().loadURL(receiverUrl());
					LOGGER.info("[broadcast] test: loading the tablet receiver page");
				}
			}
		}
		if (target == null) return;
		Assembler as = ASM.computeIfAbsent(b.pos().immutable(), k -> new Assembler());
		if (b.pieces() < 1 || b.pieces() > MAX_PIECES) {
			return; // corrupt or malicious packet: the array size does not come from the packet
		}
		if (b.piece() == 0 || as.seq != b.seq()) {
			as.seq = b.seq();
			as.parts = new byte[b.pieces()][];
			as.have = 0;
			as.init = b.init();
		}
		if (b.piece() < 0 || b.piece() >= as.parts.length || as.parts[b.piece()] != null) return;
		as.parts[b.piece()] = b.data();
		as.have++;
		if (as.have < as.parts.length) return;
		int total = 0;
		for (byte[] p : as.parts) total += p.length;
		byte[] all = new byte[total];
		int off = 0;
		for (byte[] p : as.parts) {
			System.arraycopy(p, 0, all, off, p.length);
			off += p.length;
		}
		as.seq = -1;
		viewerChunks++;
		boolean toTablet = !(s.viewerMode && target == s.browser);
		Object key = toTablet ? TABLET_KEY : s.pos.immutable();
		boolean ready = toTablet ? tabletReady : s.viewerReady;
		if (!ready) {
			java.util.ArrayDeque<Object[]> q = PENDING.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
			q.addLast(new Object[] {as.init, all});
			while (q.size() > 30) q.pollFirst();
			return;
		}
		deliver(target, as.init, all);
	}

	private static void deliver(CefBrowserView target, boolean init, byte[] data) {
		String js = "if(window.__dsRecv)window.__dsRecv(" + init + ",'" + Base64.getEncoder().encodeToString(data) + "');";
		target.getCefBrowser().executeJavaScript(js, "", 0);
	}

	private static void flush(Object key, CefBrowserView target) {
		java.util.ArrayDeque<Object[]> q = PENDING.remove(key);
		if (q == null) return;
		for (Object[] it : q) {
			deliver(target, (Boolean) it[0], (byte[]) it[1]);
		}
	}

	/** The receiver page (screen) opened its MediaSource: push the pending chunks. */
	public static void onViewerReady(ScreenBrowsers.Screen s) {
		s.viewerReady = true;
		LOGGER.info("[broadcast] receiver ready ({}), pending chunks: {}", s.pos.toShortString(), PENDING.containsKey(s.pos.immutable()) ? PENDING.get(s.pos.immutable()).size() : 0);
		if (s.browser != null) flush(s.pos.immutable(), s.browser);
	}

	/** The receiver page (tablet, test) is ready: push the pending chunks and tell the host to restart (new init + key frame). */
	public static void onTabletReady() {
		tabletReady = true;
		LOGGER.info("[broadcast] receiver (tablet) ready, pending chunks: {}", PENDING.containsKey(TABLET_KEY) ? PENDING.get(TABLET_KEY).size() : 0);
		CefBrowserView t = Browsers.getOrCreateTablet();
		if (t != null) flush(TABLET_KEY, t);
		if (testTabletViewer) {
			for (ScreenBrowsers.Screen s : ScreenBrowsers.liveScreens()) {
				if (s.hostActive && s.browser != null) {
					Browsers.jsAllFrames(s.browser, "if(window.__dsBcRestart)window.__dsBcRestart();");
				}
			}
		}
	}

	public static void reset() {
		ASM.clear();
		PENDING.clear();
		tabletReady = false;
		hostChunks = 0;
		viewerChunks = 0;
		testTabletLoaded = false;
		testTabletCreatedMs = 0L;
	}

	/** Status label for the remote / command. */
	public static String label(@Nullable ScreenBrowsers.Screen s) {
		if (s == null || s.broadcaster == null) return Lang.tr("gui.doomscroll.broadcast.off");
		return isHost(s) ? Lang.tr("gui.doomscroll.you") : (s.broadcasterName.isEmpty() ? Lang.tr("gui.doomscroll.broadcast.on") : s.broadcasterName);
	}

	private static void notice(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(text));
	}

	public static String debugInfo() {
		return Lang.tr("command.doomscroll.broadcast.debug", hostChunks, viewerChunks);
	}
}
