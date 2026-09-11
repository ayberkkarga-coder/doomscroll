package com.doomscroll.client;

import com.doomscroll.ScreenBlockEntity;
import com.doomscroll.net.ScreenControlPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Gelistirici duman testi (-Ddoomscroll.selftest=true ile). Dunyaya girince oyuncunun yanina 3x2 panel kurar,
 * YouTube acar ve ozellikleri sirayla sinar: sayfa raporu, baslik, SponsorBlock atlama, ekran isigi, sira,
 * video bitince siradaki, sunucu adres engeli, redstone. Sonucu loga yazar ve oyunu kapatir.
 */
public final class SelfTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("doomscroll-selftest");
	private static final String URL_SB = "https://www.youtube.com/watch?v=e-ORhEE9VVg"; // selfpromo 235-261 + outro 260-272
	private static final String URL_SHORT = "https://www.youtube.com/watch?v=jNQXAC9IVRw"; // 19 sn
	private static final String URL_NEXT = "https://www.youtube.com/watch?v=aqz-KE-bpKQ";

	private record Step(String name, int timeoutTicks, BooleanSupplier done) {}

	private static boolean joined = false;
	private static int tick = 0;
	private static int stage = -1;
	private static int stageStart = 0;
	private static final List<Step> STEPS = new ArrayList<>();
	private static final List<String> RESULTS = new ArrayList<>();
	@Nullable private static BlockPos anchor;
	private static int px, py, pz;
	private static boolean stopped = false;

	private SelfTest() {}

	public static boolean enabled() {
		return Boolean.getBoolean("doomscroll.selftest");
	}

	public static void register() {
		LOGGER.info("[selftest] etkin: dunyaya girince baslayacak");
		ClientPlayConnectionEvents.JOIN.register((h, s, c) -> {
			joined = true;
			tick = 0;
			stage = -1;
			buildSteps();
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!joined || stopped || mc.player == null || mc.level == null) return;
			tick++;
			if (tick == 80) {
				LOGGER.info("[selftest] basliyor");
				stage = 0;
				stageStart = tick;
				LOGGER.info("[selftest] adim 1/{}: {}", STEPS.size(), STEPS.get(0).name());
			}
			if (stage < 0 || stage >= STEPS.size()) return;
			Step st = STEPS.get(stage);
			boolean ok;
			try {
				ok = st.done().getAsBoolean();
			} catch (Throwable t) {
				LOGGER.error("[selftest] adim hata verdi: {}", st.name(), t);
				fail(st.name() + " (istisna: " + t + ")");
				advance();
				return;
			}
			if (ok) {
				pass(st.name());
				advance();
			} else if (tick - stageStart > st.timeoutTicks()) {
				fail(st.name() + " (zaman asimi)");
				advance();
			}
		});
	}

	private static void advance() {
		stage++;
		stageStart = tick;
		if (stage < STEPS.size()) {
			LOGGER.info("[selftest] adim {}/{}: {}", stage + 1, STEPS.size(), STEPS.get(stage).name());
		} else {
			finish();
		}
	}

	private static void pass(String name) {
		RESULTS.add("OK   " + name);
		LOGGER.info("[selftest] OK   {}", name);
	}

	private static void fail(String name) {
		RESULTS.add("FAIL " + name);
		LOGGER.warn("[selftest] FAIL {}", name);
	}

	private static void finish() {
		long fails = RESULTS.stream().filter(r -> r.startsWith("FAIL")).count();
		LOGGER.info("[selftest] ---- SONUC: {} adim, {} basarisiz ----", RESULTS.size(), fails);
		for (String r : RESULTS) LOGGER.info("[selftest]   {}", r);
		LOGGER.info("[selftest] TAMAM");
		stopped = true;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			try {
				Browsers.close();
				Browsers.closeTablet();
			} catch (Throwable ignored) {
			}
			mc.stop();
		});
		// CEF kapanisi bazen takiliyor: dunya kaydedildikten sonra sureci kesin bitir
		Thread killer = new Thread(() -> {
			try {
				Thread.sleep(8000);
			} catch (InterruptedException ignored) {
			}
			LOGGER.info("[selftest] surec sonlandiriliyor");
			Runtime.getRuntime().halt(0);
		}, "doomscroll-selftest-halt");
		killer.setDaemon(true);
		killer.start();
	}

	private static void cmd(String c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.connection.sendCommand(c);
		}
	}

	@Nullable
	private static ScreenBrowsers.Screen screen() {
		return anchor == null ? null : ScreenBrowsers.get(anchor);
	}

	@Nullable
	private static ScreenBlockEntity be() {
		Minecraft mc = Minecraft.getInstance();
		if (anchor == null || mc.level == null) return null;
		return mc.level.getBlockEntity(anchor) instanceof ScreenBlockEntity b ? b : null;
	}

	private static boolean playing(String id) {
		ScreenBrowsers.Screen s = screen();
		return s != null && s.localUrl.contains(id) && s.localDuration > 0 && System.currentTimeMillis() - s.localStampMs < 3000;
	}

	private static void js(String code) {
		ScreenBrowsers.Screen s = screen();
		if (s != null && s.browser != null) {
			s.browser.getCefBrowser().executeJavaScript(code, s.currentUrl(), 0);
		}
	}

	private static boolean[] once = new boolean[32];

	private static boolean firstTime(int i) {
		if (once[i]) return false;
		once[i] = true;
		return true;
	}

	private static void buildSteps() {
		STEPS.clear();
		RESULTS.clear();
		once = new boolean[32];
		anchor = null;
		// 1) sahne: yaratici mod, alan temizle, zemin, 3x2 panel (batiya bakar), oyuncu panele baksin
		STEPS.add(new Step("sahne kuruldu", 100, () -> {
			Minecraft mc = Minecraft.getInstance();
			if (firstTime(0)) {
				px = (int) Math.floor(mc.player.getX());
				py = (int) Math.floor(mc.player.getY());
				pz = (int) Math.floor(mc.player.getZ());
				cmd("gamemode creative");
				cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", px - 1, py - 1, pz - 4, px + 6, py + 5, pz + 4));
				cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:black_concrete", px - 1, py - 1, pz - 4, px + 6, py - 1, pz + 4));
				for (int y = py; y <= py + 1; y++) {
					for (int z = pz - 1; z <= pz + 1; z++) {
						cmd(String.format(Locale.ROOT, "setblock %d %d %d doomscroll:screen[facing=west]", px + 4, y, z));
					}
				}
				cmd(String.format(Locale.ROOT, "tp @s %d.5 %d %d.5 facing %d.5 %d.5 %d.5", px, py, pz, px + 4, py + 1, pz));
				return false;
			}
			return tick - stageStart > 40;
		}));
		// 2) panel 3x2 olarak birlesti mi (anchor bul)
		STEPS.add(new Step("panel 3x2 birlesti", 200, () -> {
			Minecraft mc = Minecraft.getInstance();
			for (int y = py; y <= py + 1; y++) {
				for (int z = pz - 1; z <= pz + 1; z++) {
					if (mc.level.getBlockEntity(new BlockPos(px + 4, y, z)) instanceof ScreenBlockEntity b && b.isAnchor() && b.getWidth() == 3 && b.getHeight() == 2) {
						anchor = b.getBlockPos().immutable();
						LOGGER.info("[selftest] anchor {}", anchor.toShortString());
						return true;
					}
				}
			}
			return false;
		}));
		// 3) tarayici acildi (renderer ekrani cizince)
		STEPS.add(new Step("tarayici acildi", 1200, () -> {
			ScreenBrowsers.Screen s = screen();
			return s != null && s.browser != null;
		}));
		// 4) YouTube sayfasi acildi, rapor geliyor, baslik var
		STEPS.add(new Step("YouTube acildi + rapor + baslik", 1200, () -> {
			if (firstTime(1)) {
				ScreenBrowsers.requestNavigate(anchor, URL_SB);
				return false;
			}
			ScreenBrowsers.Screen s = screen();
			if (s != null && playing("e-ORhEE9VVg") && !s.localTitle.isEmpty()) {
				LOGGER.info("[selftest] baslik='{}' sure={}", s.localTitle, s.localDuration);
				return true;
			}
			return false;
		}));
		// 5) SponsorBlock bolumleri geldi
		STEPS.add(new Step("SponsorBlock bolumleri cekildi", 600, () -> {
			ScreenBrowsers.Screen s = screen();
			return s != null && "e-ORhEE9VVg".equals(s.sbVideoId) && SponsorBlock.cachedVideos() >= 1;
		}));
		// 6) asil video (272 sn) oynarken 232'ye sar, sponsor bolumu (235-272) atlanmali (reklam bitene kadar bekle)
		STEPS.add(new Step("SponsorBlock bolumu atladi", 1800, () -> {
			ScreenBrowsers.Screen s = screen();
			if (s == null) return false;
			if (s.localDuration < 200) {
				if ((tick - stageStart) % 100 == 0) LOGGER.info("[selftest] asil video bekleniyor (sure={}, t={})", s.localDuration, s.localTime);
				return false;
			}
			if (firstTime(2) || ((tick - stageStart) % 120 == 0 && s.localTime < 232)) {
				js("if(window.__dsSetPaused)window.__dsSetPaused(false);if(window.__dsSeek)window.__dsSeek(232);");
				LOGGER.info("[selftest] 232'ye sarildi (t={})", s.localTime);
				return false;
			}
			if ((tick - stageStart) % 40 == 0) LOGGER.info("[selftest] t={}", s.localTime);
			return s.localTime >= 271 && s.localTime < 300;
		}));
		// 7) ekran isigi yamalari
		STEPS.add(new Step("ekran isigi yamalari > 0", 200, () -> {
			String info = ScreenGlow.info(anchor);
			if (firstTime(3)) LOGGER.info("[selftest] isik: {}", info);
			return ScreenGlow.patchCount(anchor) > 0;
		}));
		// 8) sira: ekle + atla -> kisa video acilir
		STEPS.add(new Step("sira: atla ile gecis", 600, () -> {
			if (firstTime(4)) {
				ScreenQueue.add(anchor, URL_SHORT);
				ScreenQueue.add(anchor, URL_NEXT);
				ScreenQueue.next(anchor);
				return false;
			}
			return playing("jNQXAC9IVRw");
		}));
		// 9) kisa video bitince siradaki otomatik acilir
		STEPS.add(new Step("video bitince siradaki", 1400, () -> {
			if (tick - stageStart == 60) js("if(window.__dsSetPaused)window.__dsSetPaused(false);");
			return playing("aqz-KE-bpKQ");
		}));
		// 10) sunucu adres engeli: example.org engelli, ekran adresi degismemeli
		STEPS.add(new Step("sunucu adres engeli", 160, () -> {
			if (firstTime(5)) {
				ScreenBrowsers.requestNavigate(anchor, "https://example.org/");
				return false;
			}
			ScreenBlockEntity b = be();
			var sc = screen();
				return tick - stageStart > 100 && b != null && sc != null
						&& !b.getUrl().contains("example.org") && !sc.localUrl.contains("example.org");
		}));
		// 10b) yayin: baslat -> yakalama + parcalar; test modunda sunucu parcalari geri yansitir, tabletteki alici oynatir
		STEPS.add(new Step("yayin basladi ve parca gonderiyor", 800, () -> {
			if (firstTime(12)) {
				Broadcast.testTabletViewer = true;
				Broadcast.requestStart(anchor);
				return false;
			}
			ScreenBrowsers.Screen s = screen();
			if ((tick - stageStart) % 100 == 0 && s != null) LOGGER.info("[selftest] yayin: host={} active={} chunks={}", Broadcast.isHost(s), s.hostActive, Broadcast.hostChunks());
			return s != null && Broadcast.isHost(s) && s.hostActive && Broadcast.hostChunks() >= 4;
		}));
		STEPS.add(new Step("yayin alicisi (tablet) oynatiyor", 800, () -> {
			if ((tick - stageStart) % 100 == 0) LOGGER.info("[selftest] alici: alinan parca={} tablet t={}", Broadcast.viewerChunks(), Browsers.tabletTime());
			return Broadcast.viewerChunks() >= 4 && Browsers.tabletTime() > 1.5;
		}));
		STEPS.add(new Step("yayin durdu", 200, () -> {
			if (firstTime(13)) {
				Broadcast.requestStop(anchor);
				return false;
			}
			ScreenBlockEntity b = be();
			ScreenBrowsers.Screen s = screen();
			return b != null && b.getBroadcaster() == null && s != null && !s.hostActive;
		}));
		// 11) redstone: ayari ac, sinyal ver -> kapanir, kes+ver -> acilir
		STEPS.add(new Step("redstone ayari acildi", 200, () -> {
			if (firstTime(6)) {
				ScreenBrowsers.sendControl(anchor, ScreenControlPayload.TOGGLE_REDSTONE);
				return false;
			}
			ScreenBlockEntity b = be();
			return b != null && b.isRedstone();
		}));
		STEPS.add(new Step("redstone sinyali ekrani kapatti", 200, () -> {
			if (firstTime(7)) {
				cmd(String.format(Locale.ROOT, "setblock %d %d %d minecraft:redstone_block", px + 5, py, pz));
				return false;
			}
			ScreenBlockEntity b = be();
			return b != null && !b.isOn();
		}));
		STEPS.add(new Step("ikinci sinyal ekrani acti", 300, () -> {
			if (firstTime(8)) {
				cmd(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", px + 5, py, pz));
				return false;
			}
			if (tick - stageStart == 40) {
				cmd(String.format(Locale.ROOT, "setblock %d %d %d minecraft:redstone_block", px + 5, py, pz));
			}
			ScreenBlockEntity b = be();
			return tick - stageStart > 40 && b != null && b.isOn();
		}));
		// 11b) yer ekrani: 2x2 blok (facing=up, top=north) tek panel olur; sonra kaldirilir
		STEPS.add(new Step("yer ekrani 2x2 birlesti", 200, () -> {
			Minecraft mc = Minecraft.getInstance();
			if (firstTime(15)) {
				for (int dx = 0; dx <= 1; dx++) {
					for (int dz = 0; dz <= 1; dz++) {
						cmd(String.format(Locale.ROOT, "setblock %d %d %d doomscroll:screen[facing=up,top=north]", px + 1 + dx, py, pz + 2 + dz));
					}
				}
				return false;
			}
			if (mc.level == null) return false;
			boolean ok = false;
			for (int dx = 0; dx <= 1 && !ok; dx++) {
				for (int dz = 0; dz <= 1 && !ok; dz++) {
					if (mc.level.getBlockEntity(new net.minecraft.core.BlockPos(px + 1 + dx, py, pz + 2 + dz)) instanceof ScreenBlockEntity b
							&& b.isAnchor() && b.getWidth() == 2 && b.getHeight() == 2) {
						ok = true;
						LOGGER.info("[selftest] yer ekrani anchor {} {}x{}", b.getBlockPos().toShortString(), b.getWidth(), b.getHeight());
					}
				}
			}
			if (ok) {
				cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", px + 1, py, pz + 2, px + 2, py, pz + 3));
			}
			return ok;
		}));
		// 11c) kumanda ekrani: acilir, 3 sekme, 1 sn sonra kapanir (istisna yok)
		STEPS.add(new Step("kumanda ekrani acildi ve kapandi", 200, () -> {
			Minecraft mc = Minecraft.getInstance();
			if (firstTime(14)) {
				mc.gui.setScreen(new RemoteScreen());
				return false;
			}
			if (tick - stageStart == 20) {
				if (!(mc.gui.screen() instanceof RemoteScreen)) return false;
				RemoteScreen r = (RemoteScreen) mc.gui.screen();
				LOGGER.info("[selftest] kumanda widget sayisi: {}", r.children().size());
			}
			if (tick - stageStart == 40) {
				mc.gui.setScreen(null);
			}
			return tick - stageStart > 45 && mc.gui.screen() == null;
		}));
		// 12) yonetici komutu: /doomscroll engelle -> sunucu ayarina yazilir (tek oyunculuda ayni JVM)
		STEPS.add(new Step("yonetici komutu engelle", 200, () -> {
			if (firstTime(10)) {
				cmd("doomscroll engelle example.net");
				return false;
			}
			return com.doomscroll.ServerConfig.get().blockedDomains.contains("example.net");
		}));
		STEPS.add(new Step("yonetici komutu engelkaldir", 200, () -> {
			if (firstTime(11)) {
				cmd("doomscroll engelkaldir example.net");
				return false;
			}
			return !com.doomscroll.ServerConfig.get().blockedDomains.contains("example.net");
		}));
		// 13) isaretci: dogrudan gonder, sunucu (test modunda) geri yansitsin
		STEPS.add(new Step("isaretci sunucudan dondu", 100, () -> {
			if (firstTime(9)) {
				Pointers.sendTest(anchor);
				return false;
			}
			return Pointers.echoCount() > 0;
		}));
	}
}
