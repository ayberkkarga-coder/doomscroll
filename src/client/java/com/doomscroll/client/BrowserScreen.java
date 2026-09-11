package com.doomscroll.client;

import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Ekrana sag tiklaninca acilan tarayici kontrolu: ustte adres cubugu + hizli kaynaklar,
 * altta tam genislik tarayici. ESC ile kapanir; dunyadaki ekran ayni tarayiciyi gostermeye devam eder.
 */
public class BrowserScreen extends Screen {
	private static final int BAR = 24;
	private static final net.minecraft.resources.Identifier GUI_TEXTURE_ID = com.doomscroll.Doomscroll.id("gui_browser");
	private static final CefTexture GUI_TEXTURE = new CefTexture(Browsers::getIfPresent);
	private static boolean textureRegistered = false;

	private EditBox urlBox;

	public BrowserScreen() {
		super(Component.literal("doomscroll"));
	}

	private int viewHeight() {
		return Math.max(1, height - BAR);
	}

	private int toBrowserX(double x) {
		return (int) (x * Browsers.screenWidth() / Math.max(1, width));
	}

	private int toBrowserY(double y) {
		return (int) ((y - BAR) * Browsers.screenHeight() / viewHeight());
	}

	private boolean inBrowserArea(double y) {
		return y >= BAR;
	}

	@Override
	protected void init() {
		int btnW = 52;
		int gap = 4;
		int x = gap;

		addRenderableWidget(Button.builder(Component.literal("Shorts"), b -> Browsers.navigate(Browsers.URL_SHORTS)).bounds(x, 4, btnW, 16).build());
		x += btnW + gap;
		addRenderableWidget(Button.builder(Component.literal("Reels"), b -> Browsers.navigate(Browsers.URL_REELS)).bounds(x, 4, btnW, 16).build());
		x += btnW + gap;
		addRenderableWidget(Button.builder(Component.literal("TikTok"), b -> Browsers.navigate(Browsers.URL_TIKTOK)).bounds(x, 4, btnW, 16).build());
		x += btnW + gap;

		int goW = 36;
		int urlW = Math.max(60, width - x - goW - 2 * gap);
		urlBox = new EditBox(font, x, 4, urlW, 16, Component.literal("url"));
		urlBox.setMaxLength(2048);
		urlBox.setHint(Component.translatable("gui.doomscroll.browser.url_hint"));
		urlBox.setValue(Browsers.currentUrl());
		addRenderableWidget(urlBox);
		x += urlW + gap;

		addRenderableWidget(Button.builder(Component.translatable("gui.doomscroll.browser.go"), b -> Browsers.navigate(urlBox.getValue())).bounds(x, 4, goW, 16).build());

		CefBrowserView b = Browsers.getOrCreate();
		if (b != null) {
			b.setFocus(true);
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (urlBox != null && !urlBox.isFocused()) {
			String cur = Browsers.currentUrl();
			if (!cur.equals(urlBox.getValue())) {
				urlBox.setValue(cur);
			}
		}
	}

	@Override
	public void removed() {
		CefBrowserView b = Browsers.getIfPresent();
		if (b != null) {
			b.setFocus(false);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, 0xFF101014);
		CefBrowserView b = Browsers.getOrCreate();
		if (b == null) {
			g.centeredText(font, Component.translatable("message.doomscroll.chromium_installing", (int) Browsers.initProgress()), width / 2, height / 2, 0xFFFFFFFF);
		} else if (b.getTextureView() != null) {
			if (!textureRegistered) {
				net.minecraft.client.Minecraft.getInstance().getTextureManager().register(GUI_TEXTURE_ID, GUI_TEXTURE);
				textureRegistered = true;
			}
			GUI_TEXTURE.update();
			// blit koseleri alir: (x1, y1, x2, y2)
			g.blit(GUI_TEXTURE_ID, 0, BAR, width, height, 0f, 0f, 1f, 1f);
		}
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void mouseMoved(double x, double y) {
		CefBrowserView b = Browsers.getIfPresent();
		if (b != null && inBrowserArea(y)) {
			b.onMouseMoved(toBrowserX(x), toBrowserY(y));
		}
		super.mouseMoved(x, y);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (!inBrowserArea(event.y())) {
			return super.mouseClicked(event, doubled);
		}
		CefBrowserView b = Browsers.getIfPresent();
		if (b != null) {
			clearFocus();
			b.setFocus(true);
			b.onMouseClicked(scaled(event), doubled);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (!inBrowserArea(event.y())) {
			return super.mouseReleased(event);
		}
		CefBrowserView b = Browsers.getIfPresent();
		if (b != null) {
			b.onMouseReleased(scaled(event));
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		CefBrowserView b = Browsers.getIfPresent();
		if (b != null && inBrowserArea(y)) {
			b.onMouseScrolled(toBrowserX(x), toBrowserY(y), dy);
			return true;
		}
		return super.mouseScrolled(x, y, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (urlBox != null && urlBox.isFocused()) {
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				Browsers.navigate(urlBox.getValue());
				clearFocus();
				CefBrowserView b = Browsers.getIfPresent();
				if (b != null) {
					b.setFocus(true);
				}
				return true;
			}
			return super.keyPressed(event);
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			return super.keyPressed(event);
		}
		CefBrowserView b = Browsers.getIfPresent();
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
		CefBrowserView b = Browsers.getIfPresent();
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
		CefBrowserView b = Browsers.getIfPresent();
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
