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
 * Developer smoke test (with -Ddoomscroll.selftest=true). On joining a world it builds a 3x2 panel next to the player,
 * opens YouTube and exercises the features in turn: page report, title, screen glow, queue,
 * next in queue when the video ends, server URL block, redstone. Writes the result to the log and quits the game.
 */
public final class SelfTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("doomscroll-selftest");
	private static final String URL_SB = "https://www.youtube.com/watch?v=e-ORhEE9VVg"; // selfpromo 235-261 + outro 260-272
	private static final String URL_SHORT = "https://www.youtube.com/watch?v=jNQXAC9IVRw"; // 19 s
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
		LOGGER.info("[selftest] enabled: will start on world join");
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
				LOGGER.info("[selftest] starting");
				stage = 0;
				stageStart = tick;
				LOGGER.info("[selftest] step 1/{}: {}", STEPS.size(), STEPS.get(0).name());
			}
			if (stage < 0 || stage >= STEPS.size()) return;
			Step st = STEPS.get(stage);
			boolean ok;
			try {
				ok = st.done().getAsBoolean();
			} catch (Throwable t) {
				LOGGER.error("[selftest] step threw: {}", st.name(), t);
				fail(st.name() + " (exception: " + t + ")");
				advance();
				return;
			}
			if (ok) {
				pass(st.name());
				advance();
			} else if (tick - stageStart > st.timeoutTicks()) {
				fail(st.name() + " (timeout)");
				advance();
			}
		});
	}

	private static void advance() {
		stage++;
		stageStart = tick;
		if (stage < STEPS.size()) {
			LOGGER.info("[selftest] step {}/{}: {}", stage + 1, STEPS.size(), STEPS.get(stage).name());
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
		LOGGER.info("[selftest] ---- RESULT: {} steps, {} failed ----", RESULTS.size(), fails);
		for (String r : RESULTS) LOGGER.info("[selftest]   {}", r);
		LOGGER.info("[selftest] DONE");
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
		// CEF shutdown sometimes hangs: terminate the process for good once the world has been saved
		Thread killer = new Thread(() -> {
			try {
				Thread.sleep(8000);
			} catch (InterruptedException ignored) {
			}
			LOGGER.info("[selftest] terminating the process");
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
		// 1) scene: creative mode, clear the area, floor, 3x2 panel (facing west), make the player look at the panel
		STEPS.add(new Step("scene built", 100, () -> {
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
		// 2) did the panel merge as 3x2 (find the anchor)
		STEPS.add(new Step("panel merged as 3x2", 200, () -> {
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
		// 3) browser opened (once the renderer draws the screen)
		STEPS.add(new Step("browser opened", 1200, () -> {
			ScreenBrowsers.Screen s = screen();
			return s != null && s.browser != null;
		}));
		// 4) YouTube page opened, reports arriving, title present
		STEPS.add(new Step("YouTube opened + report + title", 1200, () -> {
			if (firstTime(1)) {
				ScreenBrowsers.requestNavigate(anchor, URL_SB);
				return false;
			}
			ScreenBrowsers.Screen s = screen();
			if (s != null && playing("e-ORhEE9VVg") && !s.localTitle.isEmpty()) {
				LOGGER.info("[selftest] title='{}' duration={}", s.localTitle, s.localDuration);
				return true;
			}
			return false;
		}));
		// 7) screen glow patches
		STEPS.add(new Step("screen glow patches > 0", 200, () -> {
			String info = ScreenGlow.info(anchor);
			if (firstTime(3)) LOGGER.info("[selftest] glow: {}", info);
			return ScreenGlow.patchCount(anchor) > 0;
		}));
		// 8) queue: add + skip -> the short video opens
		STEPS.add(new Step("queue: switch via skip", 600, () -> {
			if (firstTime(4)) {
				ScreenQueue.add(anchor, URL_SHORT);
				ScreenQueue.add(anchor, URL_NEXT);
				ScreenQueue.next(anchor);
				return false;
			}
			return playing("jNQXAC9IVRw");
		}));
		// 9) when the short video ends the next in queue opens automatically
		STEPS.add(new Step("next in queue when video ends", 1400, () -> {
			if (tick - stageStart == 60) js("if(window.__dsSetPaused)window.__dsSetPaused(false);");
			return playing("aqz-KE-bpKQ");
		}));
		// 10) server URL block: example.org is blocked, the screen URL must not change
		STEPS.add(new Step("server URL block", 160, () -> {
			if (firstTime(5)) {
				ScreenBrowsers.requestNavigate(anchor, "https://example.org/");
				return false;
			}
			ScreenBlockEntity b = be();
			var sc = screen();
				return tick - stageStart > 100 && b != null && sc != null
						&& !b.getUrl().contains("example.org") && !sc.localUrl.contains("example.org");
		}));
		// 10b) broadcast: start -> capture + chunks; in test mode the server echoes the chunks back and the viewer on the tablet plays them
		STEPS.add(new Step("broadcast started and sending chunks", 800, () -> {
			if (firstTime(12)) {
				Broadcast.testTabletViewer = true;
				Broadcast.requestStart(anchor);
				return false;
			}
			ScreenBrowsers.Screen s = screen();
			if ((tick - stageStart) % 100 == 0 && s != null) LOGGER.info("[selftest] broadcast: host={} active={} chunks={}", Broadcast.isHost(s), s.hostActive, Broadcast.hostChunks());
			return s != null && Broadcast.isHost(s) && s.hostActive && Broadcast.hostChunks() >= 4;
		}));
		STEPS.add(new Step("broadcast viewer (tablet) playing", 800, () -> {
			if ((tick - stageStart) % 100 == 0) LOGGER.info("[selftest] viewer: chunks received={} tablet t={}", Broadcast.viewerChunks(), Browsers.tabletTime());
			return Broadcast.viewerChunks() >= 4 && Browsers.tabletTime() > 1.5;
		}));
		STEPS.add(new Step("broadcast stopped", 200, () -> {
			if (firstTime(13)) {
				Broadcast.requestStop(anchor);
				return false;
			}
			ScreenBlockEntity b = be();
			ScreenBrowsers.Screen s = screen();
			return b != null && b.getBroadcaster() == null && s != null && !s.hostActive;
		}));
		// 11) redstone: enable the setting, apply a signal -> turns off, cut + apply -> turns on
		STEPS.add(new Step("redstone setting enabled", 200, () -> {
			if (firstTime(6)) {
				ScreenBrowsers.sendControl(anchor, ScreenControlPayload.TOGGLE_REDSTONE);
				return false;
			}
			ScreenBlockEntity b = be();
			return b != null && b.isRedstone();
		}));
		STEPS.add(new Step("redstone signal turned the screen off", 200, () -> {
			if (firstTime(7)) {
				cmd(String.format(Locale.ROOT, "setblock %d %d %d minecraft:redstone_block", px + 5, py, pz));
				return false;
			}
			ScreenBlockEntity b = be();
			return b != null && !b.isOn();
		}));
		STEPS.add(new Step("second signal turned the screen on", 300, () -> {
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
		// 11b) floor screen: 2x2 blocks (facing=up, top=north) become a single panel; removed afterwards
		STEPS.add(new Step("floor screen merged as 2x2", 200, () -> {
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
						LOGGER.info("[selftest] floor screen anchor {} {}x{}", b.getBlockPos().toShortString(), b.getWidth(), b.getHeight());
					}
				}
			}
			if (ok) {
				cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", px + 1, py, pz + 2, px + 2, py, pz + 3));
			}
			return ok;
		}));
		// 11c) remote screen: opens, 3 tabs, closes after 1 s (no exception)
		STEPS.add(new Step("remote screen opened and closed", 200, () -> {
			Minecraft mc = Minecraft.getInstance();
			if (firstTime(14)) {
				mc.gui.setScreen(new RemoteScreen());
				return false;
			}
			if (tick - stageStart == 20) {
				if (!(mc.gui.screen() instanceof RemoteScreen)) return false;
				RemoteScreen r = (RemoteScreen) mc.gui.screen();
				LOGGER.info("[selftest] remote widget count: {}", r.children().size());
			}
			if (tick - stageStart == 40) {
				mc.gui.setScreen(null);
			}
			return tick - stageStart > 45 && mc.gui.screen() == null;
		}));
		// 12) admin command: /doomscroll engelle -> written to the server config (same JVM in singleplayer)
		STEPS.add(new Step("admin command engelle", 200, () -> {
			if (firstTime(10)) {
				cmd("doomscroll engelle example.net");
				return false;
			}
			return com.doomscroll.ServerConfig.get().blockedDomains.contains("example.net");
		}));
		STEPS.add(new Step("admin command engelkaldir", 200, () -> {
			if (firstTime(11)) {
				cmd("doomscroll engelkaldir example.net");
				return false;
			}
			return !com.doomscroll.ServerConfig.get().blockedDomains.contains("example.net");
		}));
		// 13) pointer: send directly, the server (in test mode) should echo it back
		STEPS.add(new Step("pointer echoed back from the server", 100, () -> {
			if (firstTime(9)) {
				Pointers.sendTest(anchor);
				return false;
			}
			return Pointers.echoCount() > 0;
		}));
	}
}
