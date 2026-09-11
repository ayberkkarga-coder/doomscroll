package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.cef.api.CefBrowserView;
import com.doomscroll.cef.api.CefService;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Elde tablet: cerceveli tarayici. Ekran blogundan bagimsiz kendi tarayicisi var;
 * kapatinca sayfa kalir, sadece ses durur. ESC kapatir. Arac cubugu {@link Ui} diliyle (kumandayla ayni);
 * yildiz = yer imi, menu = yer imleri + gecmis paneli (tarayicinin ustune acilir).
 */
public class TabletScreen extends Screen {
	private long lastWheelKeyNanos = 0L;
	private static final int BEZEL = 9;
	/** Arac cubugu: genis cercevede tek satir, dar (dik/telefon) cercevede iki satir. */
	private static final int BAR = 24;
	private static final int BAR2 = 44;
	private static final int NARROW = 640;
	private int barH = BAR;

	private static final int C_SHELL = 0xFF1D1D22;
	private static final int C_SHELL_HI = 0xFF3C3C46;
	private static final int C_SHELL_LO = 0xFF0E0E11;
	private static final int C_BAR = 0xFF26262C;
	private static final int C_GLASS = 0xFF000000;
	private static final int C_LABEL = 0xFFA8A8B4;

	private static final Identifier TABLET_TEXTURE_ID = Doomscroll.id("tablet_browser");
	private static final CefTexture TABLET_TEXTURE = new CefTexture(Browsers::getIfPresent);
	private static boolean textureRegistered = false;

	private EditBox urlBox;
	private final List<Ui.Btn> bar = new ArrayList<>();
	/** Basisi tarayiciya iletilen fare tuslari: yalnizca bunlarin birakilmasi iletilir (tableti acan sag tikin birakilmasi sayfaya gitmesin). */
	private final java.util.Set<Integer> heldInView = new java.util.HashSet<>();
	// Cerceve ve tarayici alani (GUI koordinatlari)
	private int fx, fy, fw, fh;
	private int vx, vy, vw, vh;

	// Yer imleri + gecmis paneli
	private record Label(int x, int y, String text) {}
	private record MenuRow(int kind, @Nullable TabletBookmarks.Entry e) {}
	private static final int ROW_BOOKMARK_HEADER = 0, ROW_BOOKMARK = 1, ROW_HISTORY_HEADER = 2, ROW_HISTORY = 3, ROW_HINT_BOOKMARK = 4, ROW_HINT_HISTORY = 5;
	private boolean menuOpen = false;
	private int menuScroll = 0;
	private final List<Ui.Btn> menu = new ArrayList<>();
	private final List<Label> menuLabels = new ArrayList<>();
	private int mx, my, mw, mh;

	// Ses balonu: arac cubugundaki hoparlor tusunun altinda acilir; tabletin kendi ses seviyesi
	private boolean volOpen = false;
	private boolean volDrag = false;
	private int volX, volY, volW, volH;
	private int vsx, vsy, vsw, vsh;
	private Ui.Btn volMute;

	public TabletScreen() {
		super(Component.translatable("gui.doomscroll.tablet.title"));
	}

	private int toBrowserX(double x) {
		return (int) ((x - vx) * Browsers.tabletWidth() / Math.max(1, vw));
	}

	private int toBrowserY(double y) {
		return (int) ((y - vy) * Browsers.tabletHeight() / Math.max(1, vh));
	}

	private boolean inView(double x, double y) {
		return x >= vx && x < vx + vw && y >= vy && y < vy + vh;
	}

	private boolean inMenu(double x, double y) {
		return menuOpen && x >= mx && x < mx + mw && y >= my && y < my + mh;
	}

	private boolean inVolume(double x, double y) {
		return volOpen && x >= volX && x < volX + volW && y >= volY && y < volY + volH;
	}

	private boolean inVolSlider(double x, double y) {
		return x >= vsx - 4 && x < vsx + vsw + 4 && y >= vsy - 6 && y < vsy + vsh + 6;
	}

	private void setVolFromMouse(double mouseX) {
		float v = (float) ((mouseX - vsx) / Math.max(1, vsw));
		Browsers.setTabletVolume(Math.round(Math.max(0f, Math.min(1f, v)) * 20f) / 20f);
	}

	/** Hoparlor tusu: ses balonunu ac/kapat (yer imleri paneliyle ayni anda acik kalmaz). */
	private void toggleVolume() {
		volOpen = !volOpen;
		if (volOpen) {
			closeMenu();
		}
	}

	private Ui.Btn btn(int x, int y, int w, String label, Identifier icon, int color, int hover, Runnable action) {
		return new Ui.Btn(x, y, w, 16, () -> label, () -> icon, color, hover, action, () -> false);
	}

	@Override
	protected void init() {
		bar.clear();
		// Tablet: ekranin %92 yuksekligi, tarayici oraninda (bar + bezel dahil); dar cercevede cubuk iki satir
		int maxH = (int) (height * 0.92);
		int maxW = (int) (width * 0.92);
		barH = BAR;
		layoutFrame(maxH, maxW);
		boolean twoRows = vw < NARROW;
		if (twoRows) {
			barH = BAR2;
			layoutFrame(maxH, maxW);
		}

		int x = vx;
		int y = fy + BEZEL + 4;
		int small = 20;
		int gap = 3;
		bar.add(btn(x, y, small, "", Ui.ICON_BACK, Ui.BTN, Ui.BTN_HOVER, Browsers::goBackTablet));
		x += small + gap;
		bar.add(btn(x, y, small, "", Ui.ICON_FORWARD, Ui.BTN, Ui.BTN_HOVER, Browsers::goForwardTablet));
		x += small + gap;
		bar.add(btn(x, y, small, "", Ui.ICON_RELOAD, Ui.BTN, Ui.BTN_HOVER, Browsers::reloadTablet));
		x += small + gap + 3;
		int homeW = twoRows ? small : 40;
		bar.add(btn(x, y, homeW, twoRows ? "" : Lang.tr("gui.doomscroll.remote.home"), Ui.ICON_HOME, Ui.BTN, Ui.BTN_HOVER, () -> Browsers.tabletNavigate(Browsers.TABLET_HOME_URL)));
		x += homeW + gap;
		int cinW = twoRows ? small : 54;
		bar.add(btn(x, y, cinW, twoRows ? "" : Lang.tr("gui.doomscroll.remote.cinema"), Ui.ICON_CINEMA, Ui.BTN, Ui.BTN_HOVER, Browsers::toggleTabletCinema));
		x += cinW + gap + 3;
		// Yer imi (yildiz) + menu (yer imleri / gecmis)
		bar.add(new Ui.Btn(x, y, small, 16, () -> "", () -> TabletBookmarks.isBookmarked(Browsers.tabletUrl()) ? Ui.ICON_STAR_FILLED : Ui.ICON_STAR,
				Ui.BTN, Ui.BTN_HOVER, this::toggleBookmark, () -> TabletBookmarks.isBookmarked(Browsers.tabletUrl())));
		x += small + gap;
		bar.add(new Ui.Btn(x, y, small, 16, () -> "", () -> Ui.ICON_MENU, Ui.BTN, Ui.BTN_HOVER, this::toggleMenu, () -> menuOpen));
		x += small + gap;
		// Ses: tabletin kendi seviyesi (kumandadaki ekran sesinden ayri)
		int speakerX = x;
		bar.add(new Ui.Btn(x, y, small, 16, () -> "", () -> Browsers.isTabletMuted() ? Ui.ICON_SPEAKER_OFF : Ui.ICON_SPEAKER,
				Ui.BTN, Ui.BTN_HOVER, this::toggleVolume, () -> volOpen || Browsers.isTabletMuted()));
		x += small + gap + 3;

		// Sag taraf: ekrana yolla / siraya ekle (dar cercevede ikinci satir, simge-only)
		int right = vx + vw;
		if (twoRows) {
			y += 20;
			x = vx;
		}
		int castW = twoRows ? 22 : 58;
		int qW = twoRows ? 22 : 58;
		bar.add(btn(right - qW, y, qW, twoRows ? "" : Lang.tr("gui.doomscroll.tablet.queue_btn"), Ui.ICON_QUEUE, Ui.BTN, Ui.BTN_HOVER, this::queueToScreen));
		bar.add(btn(right - qW - gap - castW, y, castW, twoRows ? "" : Lang.tr("gui.doomscroll.tablet.cast_btn"), Ui.ICON_CAST, Ui.BLUE, Ui.BLUE_HOVER, this::castToScreen));
		int goW = 24;
		int goX = right - qW - gap - castW - gap - 3 - goW;
		bar.add(btn(goX, y, goW, "", Ui.ICON_GO, Ui.BLUE, Ui.BLUE_HOVER, this::go));

		int urlW = Math.max(60, goX - gap - x);
		urlBox = new EditBox(font, x, y, urlW, 16, Component.literal("url"));
		urlBox.setMaxLength(2048);
		urlBox.setHint(Component.translatable("gui.doomscroll.tablet.url.hint"));
		urlBox.setValue(Browsers.tabletUrl());
		addRenderableWidget(urlBox);

		// Ses balonu: hoparlor tusunun altinda, cerceve icinde kalir
		volW = 176;
		volH = 44;
		volX = Math.max(vx, Math.min(speakerX - 6, vx + vw - volW));
		volY = fy + BEZEL + barH + 6;
		vsx = volX + 34;
		vsw = 100;
		vsy = volY + 12;
		vsh = 6;
		volMute = new Ui.Btn(volX + 8, volY + 8, 20, 14, () -> "", () -> Browsers.isTabletMuted() ? Ui.ICON_SPEAKER_OFF : Ui.ICON_SPEAKER,
				Ui.BTN, Ui.BTN_HOVER, Browsers::toggleTabletMute, Browsers::isTabletMuted);

		// Menu paneli: tarayicinin sol ustunde
		mx = vx + 4;
		my = vy + 4;
		mw = Math.min(300, vw - 8);
		mh = vh - 8;
		if (menuOpen) {
			buildMenu();
		}

		CefBrowserView b = Browsers.getOrCreateTablet();
		if (b != null) {
			b.setFocus(true);
		}
	}

	/** Cerceve ve tarayici alani: mevcut barH ile hesaplar. */
	private void layoutFrame(int maxH, int maxW) {
		vh = maxH - 2 * BEZEL - barH;
		vw = vh * Browsers.tabletWidth() / Browsers.tabletHeight();
		if (vw > maxW - 2 * BEZEL) {
			vw = maxW - 2 * BEZEL;
			vh = vw * Browsers.tabletHeight() / Browsers.tabletWidth();
		}
		fw = vw + 2 * BEZEL;
		fh = vh + 2 * BEZEL + barH;
		fx = (width - fw) / 2;
		fy = (height - fh) / 2;
		vx = fx + BEZEL;
		vy = fy + BEZEL + barH;
	}

	// ---------- yer imleri / gecmis ----------

	private void toggleBookmark() {
		String u = Browsers.tabletUrl();
		if (TabletBookmarks.ignorable(u)) {
			overlay(Lang.tr("message.doomscroll.bookmark.not_allowed"));
			return;
		}
		boolean added = TabletBookmarks.toggleBookmark(u, Browsers.tabletTitle());
		overlay(added
				? Lang.tr("message.doomscroll.bookmark.added",
						RemoteScreen.shortName(Browsers.tabletTitle().isBlank() ? RemoteScreen.siteName(u) : Browsers.tabletTitle(), 40))
				: Lang.tr("message.doomscroll.bookmark.removed"));
		if (menuOpen) {
			buildMenu();
		}
	}

	private void toggleMenu() {
		menuOpen = !menuOpen;
		if (menuOpen) {
			volOpen = false;
			menuScroll = 0;
			buildMenu();
		} else {
			menu.clear();
			menuLabels.clear();
		}
	}

	private void closeMenu() {
		menuOpen = false;
		menu.clear();
		menuLabels.clear();
	}

	private void openFromMenu(String url) {
		closeMenu();
		Browsers.tabletNavigate(url);
		CefBrowserView b = Browsers.getTabletIfPresent();
		if (b != null) {
			b.setFocus(true);
		}
	}

	/** Paneli yeniden kurar: yer imleri (yildizli satirlar, sil), gecmis (saatli satirlar, yildizla). */
	private void buildMenu() {
		menu.clear();
		menuLabels.clear();
		List<MenuRow> rows = new ArrayList<>();
		rows.add(new MenuRow(ROW_BOOKMARK_HEADER, null));
		List<TabletBookmarks.Entry> bm = TabletBookmarks.bookmarks();
		for (TabletBookmarks.Entry e : bm) rows.add(new MenuRow(ROW_BOOKMARK, e));
		if (bm.isEmpty()) rows.add(new MenuRow(ROW_HINT_BOOKMARK, null));
		rows.add(new MenuRow(ROW_HISTORY_HEADER, null));
		List<TabletBookmarks.Entry> hist = TabletBookmarks.history();
		for (TabletBookmarks.Entry e : hist) rows.add(new MenuRow(ROW_HISTORY, e));
		if (hist.isEmpty()) rows.add(new MenuRow(ROW_HINT_HISTORY, null));

		int rowH = 16;
		int visible = Math.max(1, (mh - 8) / rowH);
		int maxScroll = Math.max(0, rows.size() - visible);
		menuScroll = Math.max(0, Math.min(menuScroll, maxScroll));
		int x = mx + 4;
		int w = mw - 8;
		int y = my + 4;
		int end = Math.min(rows.size(), menuScroll + visible);
		for (int i = menuScroll; i < end; i++) {
			MenuRow r = rows.get(i);
			switch (r.kind()) {
				case ROW_BOOKMARK_HEADER -> menuLabels.add(new Label(x + 2, y + 4, bm.isEmpty() ? Lang.tr("gui.doomscroll.tablet.bookmarks") : Lang.tr("gui.doomscroll.tablet.bookmarks_n", bm.size())));
				case ROW_HISTORY_HEADER -> {
					menuLabels.add(new Label(x + 2, y + 4, hist.isEmpty() ? Lang.tr("gui.doomscroll.tablet.history") : Lang.tr("gui.doomscroll.tablet.history_n", hist.size())));
					if (!hist.isEmpty()) {
						menu.add(new Ui.Btn(x + w - 18, y, 18, 14, () -> "", () -> Ui.ICON_TRASH, Ui.BTN, Ui.BTN_HOVER, () -> {
							TabletBookmarks.clearHistory();
							overlay(Lang.tr("message.doomscroll.history.cleared"));
							buildMenu();
						}, () -> false));
					}
				}
				case ROW_BOOKMARK -> {
					final String url = r.e().url;
					final TabletBookmarks.Entry e = r.e();
					menu.add(new Ui.Btn(x, y, w - 21, 14, () -> RemoteScreen.shortName(e.label(), 42), () -> Ui.ICON_STAR_FILLED, Ui.BTN, Ui.BTN_HOVER, () -> openFromMenu(url), () -> false));
					menu.add(new Ui.Btn(x + w - 18, y, 18, 14, () -> "", () -> Ui.ICON_CLOSE, Ui.BTN, Ui.BTN_HOVER, () -> {
						TabletBookmarks.removeBookmark(url);
						buildMenu();
					}, () -> false));
				}
				case ROW_HISTORY -> {
					final String url = r.e().url;
					final TabletBookmarks.Entry e = r.e();
					menu.add(new Ui.Btn(x, y, w - 21, 14, () -> RemoteScreen.shortName(e.label(), 42), () -> Ui.ICON_CLOCK, Ui.BTN, Ui.BTN_HOVER, () -> openFromMenu(url), () -> false));
					menu.add(new Ui.Btn(x + w - 18, y, 18, 14, () -> "", () -> TabletBookmarks.isBookmarked(url) ? Ui.ICON_STAR_FILLED : Ui.ICON_STAR, Ui.BTN, Ui.BTN_HOVER, () -> {
						TabletBookmarks.toggleBookmark(url, e.title);
						buildMenu();
					}, () -> TabletBookmarks.isBookmarked(url)));
				}
				case ROW_HINT_BOOKMARK -> menuLabels.add(new Label(x + 2, y + 4, Lang.tr("gui.doomscroll.tablet.bookmark_hint")));
				default -> menuLabels.add(new Label(x + 2, y + 4, Lang.tr("gui.doomscroll.tablet.history_empty")));
			}
			y += rowH;
		}
		if (maxScroll > 0) {
			menuLabels.add(new Label(x + 2, my + mh - 12, Lang.tr("gui.doomscroll.queue.scroll_hint", menuScroll + 1, end, rows.size())));
		}
	}

	// ---------- ekrana yolla / siraya ----------

	/** Tabletteki sayfayi baktigin / en yakin ekranin sirasina ekle. */
	private void queueToScreen() {
		String u = Browsers.tabletUrl();
		if (u.isEmpty() || u.startsWith("about:")) {
			overlay(Lang.tr("message.doomscroll.tablet.no_page"));
			return;
		}
		net.minecraft.core.BlockPos target = ScreenBrowsers.castTarget();
		if (target == null) {
			overlay(Lang.tr("message.doomscroll.no_screen_near"));
			return;
		}
		if (ScreenQueue.add(target, u)) {
			overlay(Lang.tr("message.doomscroll.tablet.queued", ScreenQueue.size(target), RemoteScreen.siteName(u)));
		} else {
			overlay(Lang.tr("message.doomscroll.tablet.queue_failed"));
		}
	}

	/** Tabletteki sayfayi baktigin ya da en yakin ekrana yolla (herkes gorur). */
	private void castToScreen() {
		String u = Browsers.tabletUrl();
		if (u.isEmpty() || u.startsWith("about:")) {
			overlay(Lang.tr("message.doomscroll.tablet.no_page"));
			return;
		}
		net.minecraft.core.BlockPos target = ScreenBrowsers.castTarget();
		if (target == null) {
			overlay(Lang.tr("message.doomscroll.no_screen_near"));
			return;
		}
		ScreenBrowsers.requestNavigate(target, u);
		overlay(Lang.tr("message.doomscroll.tablet.cast", RemoteScreen.siteName(u)));
	}

	private void overlay(String text) {
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.sendOverlayMessage(Component.literal(text));
		}
	}

	private void go() {
		Browsers.tabletNavigate(urlBox.getValue());
		clearFocus();
		CefBrowserView b = Browsers.getTabletIfPresent();
		if (b != null) {
			b.setFocus(true);
		}
	}

	@Override
	public void tick() {
		super.tick();
		// Tablet acikken ana tarayici canli kalsin (ekran blogu yoksa idle-kapanmasin)
		Browsers.keepAlive();
		if (urlBox != null && !urlBox.isFocused()) {
			String cur = Browsers.tabletUrl();
			if (!cur.equals(urlBox.getValue())) {
				urlBox.setValue(cur);
			}
		}
	}

	@Override
	public void removed() {
		CefBrowserView b = Browsers.getTabletIfPresent();
		if (b != null) {
			b.setFocus(false);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, 0x88000000);
		// Kasa: kabartmali koyu govde
		Ui.panel(g, fx - 2, fy - 2, fw + 4, fh + 4, C_SHELL, C_SHELL_HI, C_SHELL_LO);
		// Arac cubugu zemini
		g.fill(vx, fy + BEZEL, vx + vw, fy + BEZEL + barH, C_BAR);
		g.fill(vx, fy + BEZEL + barH - 1, vx + vw, fy + BEZEL + barH, C_SHELL_LO);
		// Cam (cukur)
		Ui.inset(g, vx - 1, vy - 1, vw + 2, vh + 2, C_GLASS);
		// Kamera noktasi + ana tus (susleme)
		g.fill(fx + fw / 2 - 1, fy + 3, fx + fw / 2 + 1, fy + 5, 0xFF0B0B0E);
		g.fill(fx + fw / 2 - 1, fy + 3, fx + fw / 2, fy + 4, 0xFF3A4A6A);
		g.fill(fx + fw / 2 - 9, fy + fh - 7, fx + fw / 2 + 9, fy + fh - 4, 0xFF0B0B0E);
		g.fill(fx + fw / 2 - 8, fy + fh - 6, fx + fw / 2 + 8, fy + fh - 5, 0xFF3C3C46);

		CefBrowserView b = Browsers.getOrCreateTablet();
		if (b == null) {
			var init = CefService.initialize();
			g.centeredText(font, Lang.tr("gui.doomscroll.tablet.chromium_loading", init.getStage())
							+ (init.getPercentage() >= 0 ? Lang.tr("gui.doomscroll.percent", (int) init.getPercentage()) : ""),
					vx + vw / 2, vy + vh / 2, 0xFFFFFFFF);
		} else if (b.getTextureView() != null) {
			// Dogrudan tarayici dokusu blit'i (paylasimli tarayici artik icerik ciziyor)
			g.blit(b.getTextureView(), Browsers.sampler(), vx, vy, vx + vw, vy + vh, 0f, 1f, 0f, 1f); // (x0,y0,x1,y1, u0,u1,v0,v1)
		}
		for (Ui.Btn bt : bar) {
			Ui.button(g, font, bt, bt.contains(mouseX, mouseY), Ui.TXT);
		}
		if (menuOpen) {
			Ui.panel(g, mx, my, mw, mh, Ui.BODY, Ui.BODY_HI, Ui.BODY_LO);
			for (Label l : menuLabels) {
				g.text(font, l.text(), l.x(), l.y(), C_LABEL, false);
			}
			for (Ui.Btn bt : menu) {
				Ui.button(g, font, bt, bt.contains(mouseX, mouseY), Ui.TXT, bt.w() > 40);
			}
		}
		if (volOpen) {
			Ui.panel(g, volX, volY, volW, volH, Ui.BODY, Ui.BODY_HI, Ui.BODY_LO);
			Ui.button(g, font, volMute, volMute.contains(mouseX, mouseY), Ui.TXT);
			float v = Browsers.getTabletVolume();
			int fill = Math.round((vsw - 2) * v);
			Ui.inset(g, vsx - 1, vsy - 1, vsw + 2, vsh + 2, 0xFF0E0E10);
			g.fill(vsx + 1, vsy + 1, vsx + 1 + fill, vsy + vsh - 1, Browsers.isTabletMuted() ? 0xFF555560 : 0xFF3FA7F5);
			int kx = vsx + 1 + fill;
			g.fill(kx - 3, vsy - 3, kx + 3, vsy + vsh + 3, Ui.OUTLINE);
			g.fill(kx - 2, vsy - 2, kx + 2, vsy + vsh + 2, 0xFFE6E6EC);
			g.text(font, Lang.tr("gui.doomscroll.percent", Math.round(v * 100)).trim(), volX + volW - 32, vsy - 3, C_LABEL, false);
			g.text(font, Lang.tr("gui.doomscroll.tablet.device_volume"), volX + 9, volY + 28, C_LABEL, false);
		}
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void mouseMoved(double x, double y) {
		CefBrowserView b = Browsers.getTabletIfPresent();
		if (b != null && inView(x, y) && !inMenu(x, y) && !inVolume(x, y)) {
			b.onMouseMoved(toBrowserX(x), toBrowserY(y));
		}
		super.mouseMoved(x, y);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			for (Ui.Btn bt : bar) {
				if (bt.contains(event.x(), event.y())) {
					bt.action().run();
					return true;
				}
			}
			if (volOpen) {
				if (inVolume(event.x(), event.y())) {
					if (volMute.contains(event.x(), event.y())) {
						volMute.action().run();
					} else if (inVolSlider(event.x(), event.y())) {
						volDrag = true;
						setVolFromMouse(event.x());
					}
					return true;
				}
				volOpen = false; // disari tik: balonu kapat, tik sayfaya gitmesin
				return true;
			}
			if (menuOpen) {
				if (inMenu(event.x(), event.y())) {
					for (Ui.Btn bt : menu) {
						if (bt.contains(event.x(), event.y())) {
							bt.action().run();
							return true;
						}
					}
					return true; // panelin bos yeri: tarayiciya gitmesin
				}
				closeMenu(); // disari tik: paneli kapat, tik sayfaya gitmesin
				return true;
			}
		} else if ((menuOpen && inMenu(event.x(), event.y())) || inVolume(event.x(), event.y())) {
			return true;
		}
		if (!inView(event.x(), event.y())) {
			return super.mouseClicked(event, doubled);
		}
		CefBrowserView b = Browsers.getTabletIfPresent();
		if (b != null) {
			clearFocus();
			b.setFocus(true);
			heldInView.add(event.button());
			b.onMouseClicked(scaled(event), doubled);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (volDrag) {
			volDrag = false;
			return true;
		}
		if (!heldInView.remove(event.button())) {
			// Basisi tarayiciya gitmemis bir birakma: ornegin tableti acan sag tik (Chromium sag tik menusunu
			// birakmada acar) ya da arac cubugunda baslayan tik. Sayfaya iletilmez.
			return super.mouseReleased(event);
		}
		CefBrowserView b = Browsers.getTabletIfPresent();
		if (b != null) {
			b.onMouseReleased(scaled(event));
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (volDrag) {
			setVolFromMouse(event.x());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		if (inVolume(x, y)) {
			if (dy != 0) {
				Browsers.setTabletVolume(Math.round((Browsers.getTabletVolume() + (dy > 0 ? 0.05f : -0.05f)) * 20f) / 20f);
			}
			return true;
		}
		if (inMenu(x, y)) {
			if (dy != 0) {
				menuScroll += dy < 0 ? 1 : -1;
				buildMenu();
			}
			return true;
		}
		CefBrowserView b = Browsers.getTabletIfPresent();
		if (b != null && inView(x, y)) {
			if (Browsers.isShortFormPage(Browsers.tabletUrl())) {
				long now = System.nanoTime();
				if (now - lastWheelKeyNanos > 250_000_000L) {
					lastWheelKeyNanos = now;
					if (dy < 0) Browsers.tabletNextVideo(); else Browsers.tabletPrevVideo();
				}
			} else {
				b.onMouseScrolled(toBrowserX(x), toBrowserY(y), dy);
			}
			return true;
		}
		return super.mouseScrolled(x, y, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (urlBox != null && urlBox.isFocused()) {
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				go();
				return true;
			}
			return super.keyPressed(event);
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			if (volOpen) {
				volOpen = false;
				return true;
			}
			if (menuOpen) {
				closeMenu();
				return true;
			}
			return super.keyPressed(event);
		}
		CefBrowserView b = Browsers.getTabletIfPresent();
		if (b != null) {
			b.onKeyPressed(event);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (urlBox != null && urlBox.isFocused()) {
			return super.keyReleased(event);
		}
		CefBrowserView b = Browsers.getTabletIfPresent();
		if (b != null) {
			b.onKeyReleased(event);
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (urlBox != null && urlBox.isFocused()) {
			return super.charTyped(event);
		}
		CefBrowserView b = Browsers.getTabletIfPresent();
		if (b != null) {
			b.onCharTyped(event);
			return true;
		}
		return super.charTyped(event);
	}

	private MouseButtonEvent scaled(MouseButtonEvent e) {
		return new MouseButtonEvent(toBrowserX(e.x()), toBrowserY(e.y()), e.buttonInfo());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
