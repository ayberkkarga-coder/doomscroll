package com.doomscroll.client;

import com.doomscroll.ScreenBlockEntity;
import com.doomscroll.net.ScreenControlPayload;
import com.doomscroll.net.SetScreenPowerPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * TV kumandasi. Yalnizca bagli oldugu ekrani yonetir (kumanda eldeyken ekrana sag tik = bagla).
 * Uc sekme: KUMANDA (ses, kaynaklar, kanal, gezinme, adres, yansit), AYARLAR (oynatma/tarayici), DIGER (ekran/isik/yayin/sira).
 * Cizim dili {@link Ui}: kabartmali koyu govde, yesil durum ekrani, piksel simgeli tuslar.
 */
public class RemoteScreen extends Screen {
	private static final int BODY_W = 170;
	/** En uzun sekmenin yuksekligi; ust kenar buna gore sabitlenir, govde sekme icerigi kadar uzar. */
	private static final int BODY_H_MAX = 268;
	private static final int BODY_H_REMOTE = 232;
	private static final int PAD = 10;
	private static final int ROW = 16;

	private static final int C_DISPLAY = 0xFF0A0E11;
	private static final int C_DISPLAY_TXT = 0xFF7CE8A4;
	private static final int C_DISPLAY_DIM = 0xFF3F8A5E;
	private static final int C_DISPLAY_WARN = 0xFFE0B04A;
	private static final int C_YT = 0xFFB71C1C;
	private static final int C_YT_HOVER = 0xFFD32F2F;
	private static final int C_IG = 0xFFAD1457;
	private static final int C_IG_HOVER = 0xFFC2185B;
	private static final int C_TT = 0xFF17171B;
	private static final int C_TT_HOVER = 0xFF2A2A30;
	private static final int C_LABEL = 0xFFA8A8B4;
	private static final int C_TAB = 0xFF6A6A78;
	private static final int C_TAB_ON = 0xFF3FA7F5;
	private static final int C_FILL = 0xFF3FA7F5;
	private static final int C_KNOB = 0xFFE6E6EC;

	private static final int PAGE_REMOTE = 0;
	private static final int PAGE_SETTINGS = 1;
	private static final int PAGE_OTHER = 2;
	/** Sira listesi: sekme degil, DIGER -> Sira ile acilan alt sayfa. */
	private static final int PAGE_QUEUE = 3;
	private static final int QUEUE_VISIBLE = 7;
	private static int queueScroll = 0;
	/** Son acik sekme (kumanda kapatilip acilinca hatirlanir). */
	private static int page = PAGE_REMOTE;

	private static String tabName(int i) {
		return Lang.tr(i == 0 ? "gui.doomscroll.remote.tab.remote"
				: i == 1 ? "gui.doomscroll.remote.tab.settings" : "gui.doomscroll.remote.tab.other");
	}

	/** Ayar satiri etiketi. */
	private record Label(int x, int y, String text) {}

	private final List<Ui.Btn> buttons = new ArrayList<>();
	private final List<Label> labels = new ArrayList<>();
	private final Ui.Btn[] tabs = new Ui.Btn[3];
	private Ui.Btn powerBtn;
	private EditBox urlBox;
	private int bx, by, bodyH;
	private int sx, sy, sw, sh;
	private int tabY;
	private boolean draggingSlider = false;
	@Nullable
	private BlockPos target;
	private boolean paired = false;
	private String hintText = "";
	private long hintUntilMs = 0L;

	public RemoteScreen() {
		super(Component.translatable("gui.doomscroll.remote.title"));
	}

	// ---------- yardimcilar ----------

	/** Eldeki kumandanin bagli oldugu ekran (yoksa null). */
	@Nullable
	private BlockPos boundTarget() {
		if (minecraft == null || minecraft.player == null) {
			return null;
		}
		for (ItemStack st : new ItemStack[]{minecraft.player.getMainHandItem(), minecraft.player.getOffhandItem()}) {
			if (st.getItem() == com.doomscroll.Doomscroll.REMOTE_ITEM) {
				BlockPos p = st.get(com.doomscroll.Doomscroll.REMOTE_TARGET);
				if (p != null) return p;
			}
		}
		return null;
	}

	@Nullable
	private ScreenBlockEntity targetBe() {
		if (target == null || minecraft == null || minecraft.level == null) {
			return null;
		}
		BlockEntity be = minecraft.level.getBlockEntity(target);
		return be instanceof ScreenBlockEntity s ? s : null;
	}

	/** Ekran gerektiren tus: bagli ekran yoksa uyarir. */
	private void add(int x, int y, int w, int h, String label, @Nullable Identifier icon, int color, int hover, Runnable action) {
		buttons.add(new Ui.Btn(x, y, w, h, () -> label, icon == null ? null : () -> icon, color, hover, guarded(action), () -> false));
	}

	/** Genel ayar: ekrana bagli olmayi gerektirmez. */
	private void addFree(int x, int y, int w, int h, Supplier<String> label, Runnable action, BooleanSupplier on) {
		buttons.add(new Ui.Btn(x, y, w, h, label, null, Ui.BTN, Ui.BTN_HOVER, action, on));
	}

	private Runnable guarded(Runnable action) {
		return () -> {
			if (target == null) {
				hint(Lang.tr("message.doomscroll.remote.unbound"));
				return;
			}
			action.run();
		};
	}

	/** Kisa bildirim: hem panel ekraninda hem eylem cubugunda. */
	private void hint(String text) {
		hintText = text;
		hintUntilMs = System.currentTimeMillis() + 3000L;
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.sendOverlayMessage(Component.literal(text));
		}
	}

	private static String onOff(boolean on) {
		return Lang.onOff(on);
	}

	@Override
	public void removed() {
		ScreenBrowsers.setRemoteLock(false);
		super.removed();
	}

	// ---------- duzen ----------

	@Override
	protected void init() {
		buttons.clear();
		labels.clear();
		urlBox = null;
		sw = 0;
		bx = (width - BODY_W) / 2;
		by = (height - BODY_H_MAX) / 2;
		bodyH = page == PAGE_REMOTE ? BODY_H_REMOTE : BODY_H_MAX;

		target = null;
		paired = false;
		if (minecraft != null && minecraft.player != null) {
			BlockPos bound = boundTarget();
			if (bound != null) {
				target = bound;
				paired = true;
			}
			// Bagli degilse hicbir ekrani yonetmez (bakilan/en yakin ekrana dusmez)
			ScreenBrowsers.setActive(target);
			ScreenBrowsers.setRemoteLock(true); // panel acikken yalnizca bagli ekran
		}

		int left = bx + PAD;
		int inner = BODY_W - 2 * PAD; // 150

		// Guc (sag ust)
		powerBtn = new Ui.Btn(bx + BODY_W - PAD - 26, by + 9, 26, 18, () -> "", () -> Ui.ICON_POWER, Ui.RED, Ui.RED_HOVER, guarded(this::togglePower), () -> false);
		buttons.add(powerBtn);

		// Sekmeler (durum ekraninin altinda)
		tabY = by + 82;
		int tw = (inner - 8) / 3;
		for (int i = 0; i < 3; i++) {
			final int p = i;
			int x = left + i * (tw + 4);
			int w = i == 2 ? inner - 2 * (tw + 4) : tw;
			tabs[i] = new Ui.Btn(x, tabY, w, 14, () -> tabName(p), null, 0, 0, () -> switchPage(p), () -> false);
			buttons.add(tabs[i]);
		}

		int top = tabY + 20;
		if (page == PAGE_SETTINGS) {
			initSettings(left, inner, top);
		} else if (page == PAGE_OTHER) {
			initOther(left, inner, top);
		} else if (page == PAGE_QUEUE) {
			initQueue(left, inner, top);
		} else {
			initRemote(left, inner, top);
		}
	}

	private void switchPage(int p) {
		if (page != p) {
			page = p;
			rebuildWidgets();
		}
	}

	private boolean isTab(Ui.Btn b) {
		return b == tabs[0] || b == tabs[1] || b == tabs[2];
	}

	private void initRemote(int left, int inner, int top) {
		// Ses: sessiz + kaydirici
		int volY = top;
		buttons.add(new Ui.Btn(left, volY, 24, 14, () -> "", () -> Browsers.isMuted() ? Ui.ICON_SPEAKER_OFF : Ui.ICON_SPEAKER,
				Ui.BTN, Ui.BTN_HOVER, guarded(Browsers::toggleMute), Browsers::isMuted));
		sx = left + 30;
		sy = volY + 4;
		sw = inner - 30;
		sh = 6;

		// Kaynaklar
		int third = (inner - 8) / 3;
		int srcY = volY + 20;
		add(left, srcY, third, ROW, "Shorts", null, C_YT, C_YT_HOVER, () -> Browsers.navigate(Browsers.URL_SHORTS));
		add(left + third + 4, srcY, third, ROW, "Reels", null, C_IG, C_IG_HOVER, () -> Browsers.navigate(Browsers.URL_REELS));
		add(left + 2 * (third + 4), srcY, inner - 2 * (third + 4), ROW, "TikTok", null, C_TT, C_TT_HOVER, () -> Browsers.navigate(Browsers.URL_TIKTOK));

		// Kanal: < [kanal adi] > — ortadaki tus gecerli kanala gider
		int chY = srcY + ROW + 5;
		add(left, chY, 20, ROW, "", Ui.ICON_BACK, Ui.BTN, Ui.BTN_HOVER, () -> tune(-1));
		buttons.add(new Ui.Btn(left + 23, chY, inner - 46, ROW, this::channelLabel, null, C_DISPLAY, 0xFF14201A,
				guarded(() -> Browsers.navigate(Channels.current().url())), () -> false));
		add(left + inner - 20, chY, 20, ROW, "", Ui.ICON_FORWARD, Ui.BTN, Ui.BTN_HOVER, () -> tune(1));

		// Gezinme + sinema
		int navY = chY + ROW + 5;
		int navW = 20;
		add(left, navY, navW, ROW, "", Ui.ICON_BACK, Ui.BTN, Ui.BTN_HOVER, Browsers::goBack);
		add(left + navW + 3, navY, navW, ROW, "", Ui.ICON_FORWARD, Ui.BTN, Ui.BTN_HOVER, Browsers::goForward);
		add(left + 2 * (navW + 3), navY, navW, ROW, "", Ui.ICON_RELOAD, Ui.BTN, Ui.BTN_HOVER, Browsers::reload);
		int anaX = left + 3 * (navW + 3);
		add(anaX, navY, navW, ROW, "", Ui.ICON_HOME, Ui.BTN, Ui.BTN_HOVER, () -> Browsers.navigate(HomePages.screenUrl(target)));
		add(anaX + navW + 3, navY, inner - 4 * (navW + 3), ROW, Lang.tr("gui.doomscroll.remote.cinema"), Ui.ICON_CINEMA, Ui.BTN, Ui.BTN_HOVER, Browsers::toggleCinema);

		// Adres
		int urlY = navY + ROW + 5;
		urlBox = new EditBox(font, left, urlY, inner - 52, ROW, Component.literal("url"));
		urlBox.setMaxLength(2048);
		urlBox.setHint(Component.translatable("gui.doomscroll.remote.url_hint"));
		addRenderableWidget(urlBox);
		add(left + inner - 49, urlY, 27, ROW, "", Ui.ICON_GO, Ui.BLUE, Ui.BLUE_HOVER, () -> go(urlBox.getValue()));
		add(left + inner - 19, urlY, 19, ROW, "", Ui.ICON_PLUS, Ui.BTN, Ui.BTN_HOVER, () -> queueUrl(urlBox.getValue()));

		// Ekrandaki sayfayi tablete al (tablet -> ekran yonu tabletin kendi "Yansit" tusunda)
		int castY = urlY + ROW + 5;
		add(left, castY, inner, ROW, Lang.tr("gui.doomscroll.remote.cast_to_tablet"), Ui.ICON_TO_TABLET, Ui.BLUE, Ui.BLUE_HOVER, this::screenToTablet);
	}

	private void initSettings(int left, int inner, int top) {
		int y = top;
		DoomscrollConfig cfg = DoomscrollConfig.get();
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.auto_next"), () -> onOff(cfg.autoScroll), () -> {
			cfg.autoScroll = !cfg.autoScroll;
			DoomscrollConfig.save();
		}, () -> cfg.autoScroll, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.sync"), () -> onOff(cfg.syncPlayback), () -> {
			cfg.syncPlayback = !cfg.syncPlayback;
			DoomscrollConfig.save();
		}, () -> cfg.syncPlayback, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.adblock"), () -> onOff(cfg.adBlock), () -> {
			cfg.adBlock = !cfg.adBlock;
			DoomscrollConfig.save();
			com.doomscroll.cef.api.CefLaunchOptions.adBlock = cfg.adBlock;
		}, () -> cfg.adBlock, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.subtitles"), () -> onOff(cfg.subtitles), () -> {
			cfg.subtitles = !cfg.subtitles;
			DoomscrollConfig.save();
		}, () -> cfg.subtitles, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.tv_login"), () -> onOff(cfg.tvLogin), () -> {
			boolean on = DoomscrollClient.toggleTvLogin();
			if (minecraft != null && minecraft.player != null) {
				minecraft.player.sendSystemMessage(Component.literal(DoomscrollClient.tvLoginMessage(on)));
			}
		}, () -> cfg.tvLogin, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.lock"), () -> {
			ScreenBlockEntity be = targetBe();
			return be == null ? "—" : onOff(be.isLocked());
		}, this::toggleLock, () -> {
			ScreenBlockEntity be = targetBe();
			return be != null && be.isLocked();
		}, true);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.resolution"), cfg::resolutionLabel, () -> {
			cfg.cycleResolution();
			Browsers.applyScreenResolution();
		}, () -> false, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.fps"), () -> Lang.tr("gui.doomscroll.remote.fps_value", cfg.browserFps), () -> {
			cfg.browserFps = cfg.browserFps >= 60 ? 30 : cfg.browserFps >= 45 ? 60 : 45;
			DoomscrollConfig.save();
			com.doomscroll.cef.api.CefLaunchOptions.frameRate = cfg.browserFps;
		}, () -> false, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.remote_tablet"), Browsers::remoteTabletLabel, Browsers::cycleRemoteTabletVolume,
				() -> Browsers.getRemoteTabletVolume() > 0.01f, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.audio_latency"), () -> latencyLabel(cfg.audioLatency), () -> {
			cfg.audioLatency = switch (cfg.audioLatency == null ? "normal" : cfg.audioLatency) {
				case "dusuk" -> "normal";
				case "normal" -> "yuksek";
				default -> "dusuk";
			};
			DoomscrollConfig.save();
			com.doomscroll.cef.api.CefAudioDefaults.targetBacklogMs = cfg.audioTargetBacklogMs();
			ScreenBrowsers.restartSounds();
			BrowserAudio.stopAll();
		}, () -> false, false);
	}

	private void initOther(int left, int inner, int top) {
		int y = top;
		DoomscrollConfig cfg = DoomscrollConfig.get();
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.sponsorblock"), () -> onOff(cfg.sponsorBlock), () -> {
			cfg.sponsorBlock = !cfg.sponsorBlock;
			DoomscrollConfig.save();
			ScreenBrowsers.refreshSponsorBlock();
		}, () -> cfg.sponsorBlock, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.pointer"), () -> onOff(cfg.pointer), () -> {
			cfg.pointer = !cfg.pointer;
			DoomscrollConfig.save();
			if (!cfg.pointer) Pointers.clear();
		}, () -> cfg.pointer, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.others_screen"),
				Browsers::othersScreenLabel, Browsers::cycleOthersScreenVolume,
				() -> Browsers.getOthersScreenVolume() > 0.01f, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.screen_volume"), () -> {
			ScreenBlockEntity be = targetBe();
			return be == null ? "—" : Lang.tr(ScreenBlockEntity.volumeKey(be.getVolume())).toUpperCase(java.util.Locale.ROOT);
		}, () -> ScreenBrowsers.sendControl(target, ScreenControlPayload.CYCLE_VOLUME), () -> {
			ScreenBlockEntity be = targetBe();
			return be != null && be.getVolume() > 0.01f;
		}, true);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.redstone"), () -> {
			ScreenBlockEntity be = targetBe();
			return be == null ? "—" : onOff(be.isRedstone());
		}, () -> ScreenBrowsers.sendControl(target, ScreenControlPayload.TOGGLE_REDSTONE), () -> {
			ScreenBlockEntity be = targetBe();
			return be != null && be.isRedstone();
		}, true);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.broadcast"), () -> Broadcast.label(ScreenBrowsers.get(target)), () -> {
			ScreenBrowsers.Screen s = ScreenBrowsers.get(target);
			if (s == null) {
				hint(Lang.tr("message.doomscroll.screen_far"));
			} else if (s.broadcaster == null) {
				Broadcast.requestStart(target);
				hint(Lang.tr("message.doomscroll.broadcast.requested"));
			} else if (Broadcast.isHost(s)) {
				Broadcast.requestStop(target);
			} else {
				hint(Lang.tr("message.doomscroll.broadcast.other_host", s.broadcasterName));
			}
		}, () -> {
			ScreenBrowsers.Screen s = ScreenBrowsers.get(target);
			return s != null && s.broadcaster != null;
		}, true);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.glow"), cfg::glowLabel, () -> {
			cfg.cycleGlow();
			DoomscrollConfig.save();
		}, () -> cfg.screenGlow > 0.01f, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.glow_smooth"), () -> onOff(cfg.screenGlowSmooth), () -> {
			cfg.screenGlowSmooth = !cfg.screenGlowSmooth;
			DoomscrollConfig.save();
		}, () -> cfg.screenGlowSmooth, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.glow_range"), () -> Lang.tr("gui.doomscroll.remote.blocks_value", cfg.screenGlowRange), () -> {
			cfg.screenGlowRange = cfg.screenGlowRange >= 20 ? 6 : cfg.screenGlowRange >= 14 ? 20 : cfg.screenGlowRange >= 10 ? 14 : 10;
			DoomscrollConfig.save();
			ScreenGlow.clear();
		}, () -> false, false);
		y += ROW;
		settingRow(left, inner, y, Lang.tr("gui.doomscroll.remote.setting.queue"), () -> Lang.tr("gui.doomscroll.remote.videos_value", ScreenQueue.size(target)), () -> switchPage(PAGE_QUEUE), () -> ScreenQueue.size(target) > 0, false);
	}

	/** Sira listesi: her satir [oynat] [baslik] [sil]; ustte geri/sayac/temizle; tekerlekle kayar. */
	private void initQueue(int left, int inner, int top) {
		ScreenQueue.refresh(target);
		List<com.doomscroll.net.QueueBroadcast.Row> q = ScreenQueue.list(target);
		int y = top;
		buttons.add(new Ui.Btn(left, y, 20, 14, () -> "", () -> Ui.ICON_BACK, Ui.BTN, Ui.BTN_HOVER, () -> switchPage(PAGE_OTHER), () -> false));
		labels.add(new Label(left + 26, y + 3, Lang.tr("gui.doomscroll.queue.title", q.size())));
		buttons.add(new Ui.Btn(left + inner - 20, y, 20, 14, () -> "", () -> Ui.ICON_TRASH, Ui.BTN, Ui.BTN_HOVER, () -> {
			ScreenQueue.clear(target);
			hint(Lang.tr("message.doomscroll.queue.cleared"));
			rebuildWidgets();
		}, () -> false));
		y += ROW + 2;
		if (q.isEmpty()) {
			labels.add(new Label(left, y + 3, Lang.tr("gui.doomscroll.queue.empty")));
			labels.add(new Label(left, y + 16, Lang.tr("gui.doomscroll.queue.empty_hint1")));
			labels.add(new Label(left, y + 28, Lang.tr("gui.doomscroll.queue.empty_hint2")));
			return;
		}
		int maxScroll = Math.max(0, q.size() - QUEUE_VISIBLE);
		queueScroll = Math.max(0, Math.min(queueScroll, maxScroll));
		int end = Math.min(q.size(), queueScroll + QUEUE_VISIBLE);
		// Satir: oynat | baslik | oy sayisi (basinca oy ver/geri al) | kaldir
		for (int i = queueScroll; i < end; i++) {
			final int no = i + 1;
			final com.doomscroll.net.QueueBroadcast.Row row = q.get(i);
			final String url = row.url();
			buttons.add(new Ui.Btn(left, y, 16, 14, () -> "", () -> Ui.ICON_PLAY, Ui.BTN, Ui.BTN_HOVER, guarded(() -> {
				ScreenQueue.playNow(target, url);
				hint(Lang.tr("message.doomscroll.queue.opening", shortName(ScreenQueue.label(row), 22)));
				rebuildWidgets();
			}), () -> false));
			buttons.add(new Ui.Btn(left + 18, y, inner - 64, 14,
					() -> no + ". " + shortName(ScreenQueue.label(row), 12), null, C_DISPLAY, 0xFF14201A,
					() -> hint(row.by().isEmpty() ? shortName(ScreenQueue.label(row), 26)
							: Lang.tr("message.doomscroll.queue.added_by", shortName(ScreenQueue.label(row), 18), row.by())),
					() -> false));
			buttons.add(new Ui.Btn(left + inner - 44, y, 24, 14, () -> String.valueOf(row.votes()), null,
					row.mine() ? C_TAB_ON : Ui.BTN, Ui.BTN_HOVER, () -> {
						ScreenQueue.vote(target, url);
						rebuildWidgets();
					}, row::mine));
			buttons.add(new Ui.Btn(left + inner - 16, y, 16, 14, () -> "", () -> Ui.ICON_CLOSE, Ui.BTN, Ui.BTN_HOVER, () -> {
				ScreenQueue.remove(target, url);
				rebuildWidgets();
			}, () -> false));
			y += ROW;
		}
		labels.add(new Label(left, y + 2, maxScroll > 0
				? Lang.tr("gui.doomscroll.queue.scroll_hint", queueScroll + 1, end, q.size())
				: Lang.tr("gui.doomscroll.queue.vote_hint")));
	}
	private static String latencyLabel(String p) {
		return switch (p == null ? "normal" : p) {
			case "dusuk" -> Lang.tr("gui.doomscroll.latency.low");
			case "yuksek" -> Lang.tr("gui.doomscroll.latency.high");
			default -> Lang.tr("gui.doomscroll.latency.normal");
		};
	}

	/** Ayar satiri: solda etiket, sagda degeri gosteren tus (acik ise yesil). */
	private void settingRow(int left, int inner, int y, String label, Supplier<String> value, Runnable action, BooleanSupplier on, boolean needsScreen) {
		labels.add(new Label(left, y + 3, label));
		int bw = 52;
		if (needsScreen) {
			buttons.add(new Ui.Btn(left + inner - bw, y, bw, 14, value, null, Ui.BTN, Ui.BTN_HOVER, guarded(action), on));
		} else {
			addFree(left + inner - bw, y, bw, 14, value, action, on);
		}
	}

	// ---------- eylemler ----------

	private String channelLabel() {
		String url = Browsers.currentUrl();
		String name = Channels.nameFor(url, "");
		if (name.isEmpty()) {
			name = Channels.current().name();
		}
		return shortName(name, 16);
	}

	/** "+": adres kutusundaki sayfayi bagli ekranin sirasina ekle. */
	private void queueUrl(String text) {
		if (text == null || text.isBlank()) {
			hint(Lang.tr("message.doomscroll.queue.type_first"));
			return;
		}
		if (target == null) {
			hint(Lang.tr("message.doomscroll.remote.unbound"));
			return;
		}
		if (ScreenQueue.add(target, text)) {
			hint(Lang.tr("message.doomscroll.queue.sent"));
			if (urlBox != null) urlBox.setValue("");
		} else {
			hint(Lang.tr("message.doomscroll.queue.add_failed"));
		}
	}

	private void go(String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		if (target == null) {
			hint(Lang.tr("message.doomscroll.remote.unbound"));
			return;
		}
		Browsers.navigate(text);
		urlBox.setValue("");
		clearFocus();
	}

	private void tune(int dir) {
		Channels.Channel c = dir > 0 ? Channels.next() : Channels.prev();
		Browsers.navigate(c.url());
		hint(Lang.tr("message.doomscroll.channel", c.name()));
	}

	/** Bagli ekrandaki sayfayi tablete al (YouTube'da kaldigi saniyeden) ve tableti ac. */
	private void screenToTablet() {
		String u = Browsers.currentUrl();
		if (u == null || u.isEmpty() || u.startsWith("about:")) {
			hint(Lang.tr("message.doomscroll.no_page_on_screen"));
			return;
		}
		Browsers.tabletNavigate(withTime(u, ScreenBrowsers.get(target)));
		hint(Lang.tr("message.doomscroll.sent_to_tablet", siteName(u)));
		if (minecraft != null) {
			minecraft.gui.setScreen(new TabletScreen());
		}
	}

	/** YouTube izleme adresine ekranin kaldigi saniyeyi ekler (Shorts/diger siteler oldugu gibi kalir). */
	static String withTime(String url, @Nullable ScreenBrowsers.Screen s) {
		if (s == null) return url;
		double t = s.localNow();
		if (t < 5) return url;
		String u = url.toLowerCase(java.util.Locale.ROOT);
		boolean watch = u.contains("youtube.com/watch") || u.contains("youtu.be/");
		if (!watch || u.contains("/shorts/")) return url;
		String base = url.replaceAll("[?&]t=\\d+s?", "");
		return base + (base.contains("?") ? "&" : "?") + "t=" + (int) t + "s";
	}

	private void toggleLock() {
		ScreenBlockEntity be = targetBe();
		if (be == null) {
			hint(Lang.tr("message.doomscroll.screen_not_loaded"));
			return;
		}
		boolean mine = minecraft != null && minecraft.player != null && be.isOwner(minecraft.player.getUUID());
		if (!mine) {
			hint(be.getOwnerName().isEmpty() ? Lang.tr("message.doomscroll.lock.owner_only")
					: Lang.tr("message.doomscroll.lock.owner_only_named", be.getOwnerName()));
			return;
		}
		ScreenBrowsers.sendControl(be.getAnchor(), ScreenControlPayload.TOGGLE_LOCK);
	}

	private void togglePower() {
		ScreenBlockEntity be = targetBe();
		if (be == null) {
			return;
		}
		ClientPlayNetworking.send(new SetScreenPowerPayload(be.getAnchor(), !be.isOn()));
	}

	private boolean inSlider(double mx, double my) {
		return sw > 0 && mx >= sx - 4 && mx < sx + sw + 4 && my >= sy - 6 && my < sy + sh + 6;
	}

	private void setVolumeFromMouse(double mx) {
		float v = (float) ((mx - sx) / sw);
		Browsers.setUserVolume(Math.round(Math.max(0f, Math.min(1f, v)) * 20f) / 20f);
	}

	/** Adresten okunabilir kaynak adi. */
	static String siteName(String url) {
		if (url == null || url.isEmpty() || url.startsWith("about:")) {
			return Lang.tr("gui.doomscroll.empty_page");
		}
		if (HomePages.isHome(url)) {
			return Lang.tr("gui.doomscroll.home_page");
		}
		String u = url.toLowerCase();
		if (u.contains("youtube.com/shorts")) return "YouTube Shorts";
		if (u.contains("youtube.com")) return "YouTube";
		if (u.contains("instagram.com")) return "Instagram";
		if (u.contains("tiktok.com")) return "TikTok";
		try {
			String host = java.net.URI.create(url).getHost();
			if (host != null) {
				return host.startsWith("www.") ? host.substring(4) : host;
			}
		} catch (Exception ignored) {
		}
		return url.length() > 18 ? url.substring(0, 18) + "…" : url;
	}

	static String shortName(String s, int max) {
		return s.length() > max ? s.substring(0, max - 1) + "…" : s;
	}

	// ---------- cizim ----------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, 0x70000000);

		// Govde: kabartmali koyu kumanda, ustte biraz daha acik baslik bandi
		Ui.panel(g, bx - 2, by - 2, BODY_W + 4, bodyH + 4, Ui.BODY, Ui.BODY_HI, Ui.BODY_LO);
		g.fill(bx, by, bx + BODY_W, by + 36, 0xFF34343C);
		g.fill(bx, by + 36, bx + BODY_W, by + 37, Ui.BODY_LO);
		Ui.iconAt(g, Ui.ICON_TV, bx + PAD, by + 9, Ui.TXT_DIM);
		g.text(font, "doomscroll", bx + PAD + 13, by + 9, Ui.TXT_DIM, false);
		g.text(font, Lang.tr("gui.doomscroll.remote.subtitle"), bx + PAD, by + 21, Ui.TXT, false);

		// Durum ekrani (yesil LCD)
		ScreenBlockEntity be = targetBe();
		int dTop = by + 41;
		Ui.inset(g, bx + PAD - 2, dTop - 2, BODY_W - 2 * PAD + 4, 40, C_DISPLAY);
		String l1;
		String l2;
		String l3;
		int c3 = C_DISPLAY_DIM;
		boolean showHint = System.currentTimeMillis() < hintUntilMs && !hintText.isEmpty();
		if (be == null) {
			if (paired && target != null) {
				l1 = Lang.tr("gui.doomscroll.lcd.far");
				l2 = target.getX() + " " + target.getY() + " " + target.getZ();
				l3 = Lang.tr("gui.doomscroll.lcd.far_hint");
			} else {
				l1 = Lang.tr("gui.doomscroll.lcd.unbound");
				l2 = Lang.tr("gui.doomscroll.lcd.unbound_hint1");
				l3 = Lang.tr("gui.doomscroll.lcd.unbound_hint2");
			}
		} else {
			boolean mine = minecraft != null && minecraft.player != null && be.isOwner(minecraft.player.getUUID());
			String owner = mine ? Lang.tr("gui.doomscroll.you") : shortName(be.getOwnerName().isEmpty() ? Lang.tr("gui.doomscroll.lcd.ownerless") : be.getOwnerName(), 10);
			l1 = Lang.tr("gui.doomscroll.lcd.status", Lang.onOff(be.isOn()), owner, Math.round(Browsers.getUserVolume() * 100));
			String url = Browsers.currentUrl();
			String title = ScreenBrowsers.pageTitle(be.getAnchor());
			String name = title.isEmpty() ? Channels.nameFor(url, siteName(url)) : title;
			String time = ScreenBrowsers.timeLabel(be.getAnchor());
			int qn = ScreenQueue.size(be.getAnchor());
			l2 = (time.isEmpty() ? shortName(name, 24) : shortName(name, 12) + " " + time) + (qn > 0 ? " +" + qn : "");
			ScreenBrowsers.Screen s = ScreenBrowsers.get(be.getAnchor());
			String ctl = s != null ? s.controllerLabel() : (be.getController() == null ? Lang.tr("gui.doomscroll.none") : be.getControllerName());
			l3 = Lang.tr("gui.doomscroll.lcd.control", shortName(ctl, 10)) + (be.isLocked() ? Lang.tr("gui.doomscroll.lcd.locked_suffix") : "");
			if (be.getBroadcaster() != null) {
				l3 = Lang.tr("gui.doomscroll.lcd.broadcast", shortName(Broadcast.label(s), 14)) + (be.isLocked() ? Lang.tr("gui.doomscroll.lcd.locked_suffix") : "");
			}
			if (be.isLocked() && !mine) {
				c3 = C_DISPLAY_WARN;
			}
		}
		if (showHint) {
			l3 = shortName(hintText, 26);
			c3 = C_DISPLAY_WARN;
		}
		g.centeredText(font, l1, bx + BODY_W / 2, dTop + 4, C_DISPLAY_TXT);
		g.centeredText(font, l2, bx + BODY_W / 2, dTop + 15, C_DISPLAY_TXT);
		g.centeredText(font, l3, bx + BODY_W / 2, dTop + 26, c3);

		// Sekmeler: metin + aktif sekmenin altinda mavi cizgi
		int inner = BODY_W - 2 * PAD;
		int leftX = bx + PAD;
		g.fill(leftX, tabY + 15, leftX + inner, tabY + 16, Ui.BODY_LO);
		g.fill(leftX, tabY + 16, leftX + inner, tabY + 17, 0xFF3A3A44);
		for (int i = 0; i < 3; i++) {
			Ui.Btn t = tabs[i];
			boolean hover = t.contains(mouseX, mouseY);
			boolean active = page == i || (page == PAGE_QUEUE && i == PAGE_OTHER);
			g.centeredText(font, tabName(i), t.x() + t.w() / 2, t.y() + 3, active ? C_TAB_ON : (hover ? Ui.TXT : C_TAB));
			if (active) {
				g.fill(t.x(), tabY + 14, t.x() + t.w(), tabY + 16, C_TAB_ON);
			}
		}

		// Tuslar
		for (Ui.Btn b : buttons) {
			if (isTab(b)) continue;
			boolean hover = b.contains(mouseX, mouseY);
			if (b == powerBtn) {
				int face = be == null ? 0xFF3A3A42 : be.isOn() ? Ui.RED : 0xFF5A2A2A;
				int hov = be == null ? 0xFF3A3A42 : be.isOn() ? Ui.RED_HOVER : 0xFF7A3434;
				Ui.button(g, font, new Ui.Btn(b.x(), b.y(), b.w(), b.h(), b.label(), b.icon(), face, hov, b.action(), b.on()), hover, Ui.TXT);
				continue;
			}
			int tc = b.color() == C_DISPLAY ? C_DISPLAY_TXT : Ui.TXT;
			Ui.button(g, font, b, hover, tc);
		}

		// Ayar etiketleri
		for (Label l : labels) {
			g.text(font, l.text(), l.x(), l.y(), C_LABEL, false);
		}

		// Ses kaydiricisi (kumanda sekmesi)
		if (sw > 0) {
			float vol = Browsers.getUserVolume();
			int fillW = Math.round((sw - 2) * vol);
			Ui.inset(g, sx - 1, sy - 1, sw + 2, sh + 2, 0xFF0E0E10);
			g.fill(sx + 1, sy + 1, sx + 1 + fillW, sy + sh - 1, Browsers.isMuted() ? 0xFF555560 : C_FILL);
			int kx = sx + 1 + fillW;
			g.fill(kx - 3, sy - 3, kx + 3, sy + sh + 3, Ui.OUTLINE);
			g.fill(kx - 2, sy - 2, kx + 2, sy + sh + 2, C_KNOB);
			g.fill(kx - 2, sy - 2, kx + 2, sy - 1, 0xFFFFFFFF);
			g.fill(kx - 2, sy + sh + 1, kx + 2, sy + sh + 2, 0xFFA8A8B4);
		}

		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	// ---------- girdi ----------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			if (inSlider(event.x(), event.y())) {
				draggingSlider = true;
				setVolumeFromMouse(event.x());
				return true;
			}
			for (Ui.Btn b : buttons) {
				if (b.contains(event.x(), event.y())) {
					b.action().run();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		if (page == PAGE_QUEUE && dy != 0) {
			queueScroll += dy < 0 ? 1 : -1;
			rebuildWidgets();
			return true;
		}
		return super.mouseScrolled(x, y, dx, dy);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingSlider) {
			setVolumeFromMouse(event.x());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingSlider) {
			draggingSlider = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (urlBox != null && urlBox.isFocused()
				&& (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			go(urlBox.getValue());
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
