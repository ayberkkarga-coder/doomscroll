package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;


/**
 * Dunyadaki ekrani dogrudan kullanma: crosshair = imlec, sol tik = tikla,
 * tekerlek = kaydir, sag tik = klavyeyi ekrana bagla/birak (ESC de birakir).
 *
 * Kural: dogrudan kontrol yalnizca el bos, kumanda ya da tablet tutuluyorken aktiftir
 * (klavye kilidi aciksa her zaman). Boylece insa ederken ekran araya girmez.
 */
public final class DirectControl {
	private static final double MAX_REACH = 48.0;

	private static boolean keyboardCaptured = false;
	/**
	 * Klavye baglandigi andaki ekran. Tuslar hep bu ekrana gider: yoksa artiisaret
	 * panelden kayinca yazdigin geri kalani yandaki ekran aliyordu.
	 */
	@Nullable
	private static BlockPos capturedPos;
	@Nullable
	private static ScreenTracker.Hit currentHit;
	@Nullable
	private static ScreenTracker.Hit lastHit;
	private static int lastSentPx = -1;
	private static int lastSentPy = -1;
	private static int hintCooldown = 0;
	private static int remoteHintCooldown = 0;
	private static long lastWheelKeyNanos = 0L;
	/** Basmasini bizim yuttugumuz fare tuslari -> basildigi sayfa noktasi (birakma ayni noktaya gider: tik kaybolmaz). */
	private static final java.util.Map<Integer, int[]> heldButtons = new java.util.HashMap<>();
	/** Klavye, sayfa bir yazi alanina odaklandigi icin otomatik baglandi (odak gidince otomatik birakilir). */
	private static boolean autoCaptured = false;
	private static int handHintCooldown = 0;
	/** Son bakilan ekran (simdi oynuyor bildirimi icin). */
	@Nullable private static BlockPos lastLookedAt;
	private static long lookedSinceMs = 0L;
	private static boolean nowPlayingShown = false;

	private DirectControl() {}

	public static boolean isKeyboardCaptured() {
		return keyboardCaptured;
	}

	@Nullable
	public static ScreenTracker.Hit currentHit() {
		return currentHit;
	}

	private static boolean inWorld(Minecraft mc) {
		return mc.player != null && mc.level != null && mc.gui.screen() == null && mc.mouseHandler.isMouseGrabbed();
	}

	private static boolean handAllows(Minecraft mc) {
		if (keyboardCaptured) {
			return true;
		}
		ItemStack main = mc.player.getMainHandItem();
		return main.isEmpty() || main.getItem() == Doomscroll.REMOTE_ITEM || main.getItem() == Doomscroll.TABLET_ITEM;
	}

	/** Ekranin onunde daha yakin gercek bir blok varsa (ekran blogunun kendisi haric) vanilla kazanir. */
	private static boolean vanillaTargetCloser(Minecraft mc, ScreenTracker.Hit hit) {
		HitResult hr = mc.hitResult;
		if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK) {
			return false;
		}
		if (mc.level.getBlockState(bhr.getBlockPos()).getBlock() == Doomscroll.SCREEN_BLOCK) {
			return false;
		}
		double d = mc.player.getEyePosition().distanceTo(hr.getLocation());
		return d < hit.distance() - 0.05;
	}

	/** Su an ekran kontrolu devrede mi? */
	private static boolean controlActive(Minecraft mc) {
		return inWorld(mc) && currentHit != null && handAllows(mc) && !vanillaTargetCloser(mc, currentHit);
	}

	public static void tick(Minecraft mc) {
		if (!inWorld(mc)) {
			currentHit = null;
			if (keyboardCaptured && mc.gui.screen() != null) {
				setCaptured(mc, false);
			}
			Pointers.tick(mc); // ekrandan cikildi: "kayboldu" gonderilsin
			return;
		}

		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getViewVector(1.0f);
		currentHit = ScreenTracker.raycast(eye, look, MAX_REACH, false);
		if (currentHit != null) {
			lastHit = currentHit;
		}
		// "Simdi oynuyor": yeni bir ekrana 1 sn bakinca baslik + sure + kontrol (kisa, bir kez)
		BlockPos looking = currentHit == null ? null : currentHit.pos();
		if (looking == null || !looking.equals(lastLookedAt)) {
			lastLookedAt = looking;
			lookedSinceMs = System.currentTimeMillis();
			nowPlayingShown = false;
		} else if (!nowPlayingShown && !keyboardCaptured && System.currentTimeMillis() - lookedSinceMs > 1000L) {
			nowPlayingShown = true;
			String title = ScreenBrowsers.pageTitle(looking);
			if (!title.isEmpty()) {
				String time = ScreenBrowsers.timeLabel(looking);
				ScreenBrowsers.Screen s = ScreenBrowsers.get(looking);
				String ctl = s == null ? "" : s.controllerLabel();
				int others = Pointers.at(looking).size();
				String bc = s != null && s.broadcaster != null
						? "  · " + Lang.tr("gui.doomscroll.lcd.broadcast_short", Broadcast.label(s)) : "";
				boolean hasCtl = s != null && !s.isFree() && !ctl.isEmpty();
				String dom = ServerPolicy.showDomain() ? ServerPolicy.host(s == null ? "" : s.currentUrl()) : "";
				// Alan adi HUD'da yazar: sayfa oyunun arayuzune dokunamaz, sahte giris sayfasi
				// gercek adresi gizleyemez.
				mc.player.sendOverlayMessage(Component.literal((title.length() > 40 ? title.substring(0, 40) + "…" : title)
						+ (dom.isEmpty() ? "" : "  §7" + dom + "§r")
						+ (time.isEmpty() ? "" : "  " + time) + (hasCtl ? "  · " + Lang.tr("gui.doomscroll.lcd.control", ctl) : "") + bc
						+ (others > 0 ? "  · " + Lang.tr("gui.doomscroll.viewers_short", others + 1) : "")));
			}
		}

		CefBrowserView b = Browsers.getIfPresent();
		if (b != null && controlActive(mc)) {
			if (currentHit.px() != lastSentPx || currentHit.py() != lastSentPy) {
				b.onMouseMoved(currentHit.px(), currentHit.py());
				lastSentPx = currentHit.px();
				lastSentPy = currentHit.py();
			}
		}

		if (handHintCooldown > 0) {
			handHintCooldown--;
		}
		Pointers.tick(mc);
		if (keyboardCaptured && hintCooldown-- <= 0) {
			hintCooldown = 40;
			mc.player.sendOverlayMessage(Component.translatable("message.doomscroll.keyboard.bound"));
		}
		// Elde bagli olmayan kumanda + bakilan ekran: nasil baglanacagini soyle (3 sn'de bir)
		if (!keyboardCaptured && currentHit != null && remoteHintCooldown-- <= 0) {
			remoteHintCooldown = 60;
			ItemStack main = mc.player.getMainHandItem();
			if (main.getItem() == Doomscroll.REMOTE_ITEM && !main.has(Doomscroll.REMOTE_TARGET)) {
				mc.player.sendOverlayMessage(Component.translatable("message.doomscroll.remote.unbound_hint"));
			}
		}
	}

	/** Fare tusu. true donerse vanilla islenmez. */
	public static boolean onMouseButton(Minecraft mc, MouseButtonInfo info, int action) {
		int button = info.input();

		// Birakma: yalnizca basmasini bizim aldigimiz tuslar icin
		if (action == GLFW.GLFW_RELEASE) {
			int[] at = heldButtons.remove(button);
			if (at == null) {
				return false;
			}
			CefBrowserView b = Browsers.getIfPresent();
			if (b != null && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
				// Birakma, basilan noktaya gider: crosshair bu arada kaysa da tik ayni ogeye iner
				b.onMouseReleased(new MouseButtonEvent(at[0], at[1], info));
			}
			return true;
		}
		if (action != GLFW.GLFW_PRESS) {
			return false;
		}
		if (!controlActive(mc)) {
			// Ekrana bakiyor ama elinde baska bir esya var: neden calismadigini soyle (3 sn'de bir)
			if (inWorld(mc) && currentHit != null && !handAllows(mc) && !vanillaTargetCloser(mc, currentHit) && handHintCooldown <= 0) {
				handHintCooldown = 60;
				mc.player.sendOverlayMessage(Component.translatable("message.doomscroll.free_hand"));
			}
			return false;
		}
		// Elde kumanda + ekrana sag tik: vanilla islesin -> RemoteItem.useOn kumandayi ekrana baglar
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && mc.player.getMainHandItem().getItem() == Doomscroll.REMOTE_ITEM) {
			return false;
		}

		CefBrowserView b = Browsers.getOrCreate();
		if (b == null) {
			mc.player.sendOverlayMessage(Component.translatable("message.doomscroll.chromium_installing", (int) Browsers.initProgress()));
			heldButtons.put(button, new int[] {currentHit.px(), currentHit.py()});
			return true;
		}

		int[] stale = heldButtons.get(button);
		if (stale != null && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			// Birakmasi kaybolmus bir basma (GUI acilmis vb.): once onu bitir, yoksa tarayici tusu basili sanir
			b.onMouseReleased(new MouseButtonEvent(stale[0], stale[1], info));
		}
		heldButtons.put(button, new int[] {currentHit.px(), currentHit.py()});
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			if ((info.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) {
				Browsers.togglePlayback();
			} else {
				setCaptured(mc, !keyboardCaptured);
			}
			return true;
		}

		ScreenBrowsers.noteExplicitAt(currentHit.pos()); // tiklama = ekrani ben suruyorum
		b.setFocus(true);
		b.onMouseClicked(new MouseButtonEvent(currentHit.px(), currentHit.py(), info), false);
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			// Tik bir yazi alanina indiyse sayfa odak raporunu tazelesin (ayni alana ikinci tikta focusin gelmez)
			try {
				var cb = b.getCefBrowser();
				cb.executeJavaScript("if(window.__dsFocusSend){window.__dsClickPt=[" + currentHit.px() + "," + currentHit.py() + ",Date.now()];window.__dsLastFocus=-1;setTimeout(window.__dsFocusSend,80);}", cb.getURL(), 0);
			} catch (Exception ignored) {
			}
		}
		return true;
	}

	/** Tekerlek. true donerse vanilla (hotbar) islenmez. */
	public static boolean onScroll(Minecraft mc, double dy) {
		if (!controlActive(mc)) {
			return onTabletScroll(mc, dy);
		}
		CefBrowserView b = Browsers.getIfPresent();
		if (b == null) {
			return false;
		}
		ScreenBrowsers.noteExplicitAt(currentHit.pos());
		if (Browsers.isShortFormPage(Browsers.currentUrl())) {
			// Shorts/Reels/TikTok: bir tik = bir video (tekerlek kaydirmasi yetmiyor)
			long now = System.nanoTime();
			if (now - lastWheelKeyNanos > 250_000_000L) { // yuksek cozunurluklu tekerlek: tek centik = tek tus
				lastWheelKeyNanos = now;
				if (dy < 0) Browsers.nextVideo(); else Browsers.prevVideo();
			}
		} else {
			b.onMouseScrolled(currentHit.px(), currentHit.py(), dy);
		}
		return true;
	}

	/**
	 * Tablet ana eldeyken tekerlek tablete gider: Shorts/Reels/TikTok'ta bir centik = bir video, diger sayfalarda
	 * sayfa kaydirilir. Egilerek (Shift) cevirince hotbar degisir; tablet yan eldeyken hotbar normal calisir.
	 */
	private static boolean onTabletScroll(Minecraft mc, double dy) {
		if (!inWorld(mc) || mc.player.isShiftKeyDown()) {
			return false;
		}
		if (mc.player.getMainHandItem().getItem() != Doomscroll.TABLET_ITEM) {
			return false;
		}
		CefBrowserView b = Browsers.getTabletIfPresent();
		if (b == null) {
			return false;
		}
		if (Browsers.isShortFormPage(Browsers.tabletUrl())) {
			long now = System.nanoTime();
			if (now - lastWheelKeyNanos > 250_000_000L) {
				lastWheelKeyNanos = now;
				if (dy < 0) Browsers.tabletNextVideo(); else Browsers.tabletPrevVideo();
			}
		} else {
			b.onMouseScrolled(Browsers.tabletWidth() / 2, Browsers.tabletHeight() / 2, dy);
		}
		return true;
	}

	/** Klavye. true donerse vanilla islenmez. */
	public static boolean onKey(Minecraft mc, int action, KeyEvent event) {
		if (!keyboardCaptured || mc.gui.screen() != null) {
			return false;
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			if (action == GLFW.GLFW_PRESS) {
				setCaptured(mc, false);
			}
			return true;
		}
		CefBrowserView b = capturedBrowser();
		if (b == null) {
			return true;
		}
		if (action == GLFW.GLFW_RELEASE) {
			b.onKeyReleased(event);
		} else {
			ScreenBrowsers.noteExplicitActive();
			b.onKeyPressed(event);
		}
		return true;
	}

	public static boolean onChar(Minecraft mc, CharacterEvent event) {
		if (!keyboardCaptured || mc.gui.screen() != null) {
			return false;
		}
		CefBrowserView b = capturedBrowser();
		if (b != null) {
			ScreenBrowsers.noteExplicitActive();
			b.onCharTyped(event);
		}
		return true;
	}

	/**
	 * Sayfa bir yazi alanina odaklandi (editing=true) ya da odagi birakti: bakilan ekransa klavyeyi
	 * otomatik bagla; otomatik baglanan klavye odak gidince (Enter, sayfa degisimi) otomatik birakilir.
	 */
	private static long lastFocusChangeMs;

	public static void onPageFocus(BlockPos pos, boolean editing) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (now - lastFocusChangeMs < 500L) {
			return; // sayfa odagi hizli yanip sonduruyorsa gormezden gel
		}
		lastFocusChangeMs = now;
		if (editing) {
			if (!keyboardCaptured && inWorld(mc) && currentHit != null && currentHit.pos().equals(pos)) {
				setCaptured(mc, true);
				autoCaptured = true;
			}
		} else if (keyboardCaptured && autoCaptured) {
			setCaptured(mc, false);
		}
	}

	/** Klavyenin bagli oldugu ekranin tarayicisi (bagli degilse bakilan/etkin ekran). */
	@Nullable
	private static CefBrowserView capturedBrowser() {
		if (capturedPos != null) {
			return ScreenBrowsers.browserAt(capturedPos);
		}
		return Browsers.getIfPresent();
	}

	public static void setCaptured(Minecraft mc, boolean captured) {
		CefBrowserView old = capturedBrowser();
		keyboardCaptured = captured;
		autoCaptured = false;
		hintCooldown = 0;
		if (captured) {
			ScreenTracker.Hit h = currentHit != null ? currentHit : lastHit;
			capturedPos = h == null ? null : h.pos();
		} else {
			capturedPos = null;
		}
		CefBrowserView b = captured ? capturedBrowser() : old;
		if (b != null) {
			b.setFocus(captured);
		}
		if (captured) {
			KeyMapping.releaseAll();
		} else if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.translatable("message.doomscroll.keyboard.released"));
		}
	}

	/** Bir ekran yok oldu: yalnizca o ekrana bagli durumu birak. */
	public static void screenGone(BlockPos pos) {
		if (capturedPos != null && capturedPos.equals(pos)) {
			keyboardCaptured = false;
			capturedPos = null;
			autoCaptured = false;
		}
		if (currentHit != null && currentHit.pos().equals(pos)) {
			currentHit = null;
		}
		if (lastHit != null && lastHit.pos().equals(pos)) {
			lastHit = null;
		}
		ScreenGlow.forget(pos);
	}

	public static void reset() {
		keyboardCaptured = false;
		capturedPos = null;
		currentHit = null;
		lastHit = null;
		lastSentPx = -1;
		lastSentPy = -1;
		heldButtons.clear();
		ScreenTracker.clear();
		ScreenGlow.clear();
		Pointers.clear();
	}
}
