package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Shared drawing language of the remote and the tablet: dark body, 1 px outline, top/left highlight + bottom/right shadow (vanilla button
 * emboss), clipped corners and 9x9 pixel icons (assets/doomscroll/textures/gui/sprites/icon/*).
 */
final class Ui {
	private Ui() {
	}

	static final int OUTLINE = 0xFF0B0B0E;
	static final int OUTLINE_HOVER = 0xFFE6E6EC;
	static final int BODY = 0xFF2C2C33;
	static final int BODY_HI = 0xFF4A4A55;
	static final int BODY_LO = 0xFF17171B;
	static final int BTN = 0xFF3B3B44;
	static final int BTN_HOVER = 0xFF4C4C58;
	static final int BTN_ON = 0xFF2E7A4D;
	static final int BTN_ON_HOVER = 0xFF3A9560;
	static final int BLUE = 0xFF2F7FD0;
	static final int BLUE_HOVER = 0xFF3F93E6;
	static final int RED = 0xFFC62828;
	static final int RED_HOVER = 0xFFE03A3A;
	static final int TXT = 0xFFF2F2F2;
	static final int TXT_DIM = 0xFF9A9AA8;
	static final int ICON = 9;

	static final Identifier ICON_POWER = icon("power");
	static final Identifier ICON_SPEAKER = icon("speaker");
	static final Identifier ICON_SPEAKER_OFF = icon("speaker_off");
	static final Identifier ICON_BACK = icon("back");
	static final Identifier ICON_FORWARD = icon("forward");
	static final Identifier ICON_RELOAD = icon("reload");
	static final Identifier ICON_HOME = icon("home");
	static final Identifier ICON_CINEMA = icon("cinema");
	static final Identifier ICON_PLUS = icon("plus");
	static final Identifier ICON_CAST = icon("cast");
	static final Identifier ICON_TO_TABLET = icon("to_tablet");
	static final Identifier ICON_QUEUE = icon("queue");
	static final Identifier ICON_TV = icon("tv");
	static final Identifier ICON_GO = icon("go");
	static final Identifier ICON_LOCK = icon("lock");
	static final Identifier ICON_BROADCAST = icon("broadcast");
	static final Identifier ICON_STAR = icon("star");
	static final Identifier ICON_STAR_FILLED = icon("star_filled");
	static final Identifier ICON_MENU = icon("menu");
	static final Identifier ICON_CLOSE = icon("close");
	static final Identifier ICON_PLAY = icon("play");
	static final Identifier ICON_TRASH = icon("trash");
	static final Identifier ICON_CLOCK = icon("clock");

	private static Identifier icon(String name) {
		return Doomscroll.id("icon/" + name);
	}

	/** Button: label and/or icon (both may be dynamic), color, action; drawn green (on) when "on" is true. */
	record Btn(int x, int y, int w, int h, Supplier<String> label, @Nullable Supplier<Identifier> icon,
			   int color, int hover, Runnable action, BooleanSupplier on) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	static int mix(int a, int b, float t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	static int lighten(int c, float t) {
		return mix(c, 0xFFFFFFFF, t);
	}

	static int darken(int c, float t) {
		return mix(c, 0xFF000000, t);
	}

	/** Outline: rectangle with 1 px clipped corners. */
	static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x + 1, y, x + w - 1, y + 1, color);
		g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/** Embossed panel: outline + body + top/left highlight, bottom/right shadow (raised feel). */
	static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int face, int hi, int lo) {
		outline(g, x, y, w, h, OUTLINE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, hi);
		g.fill(x + 1, y + 1, x + 2, y + h - 1, hi);
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, lo);
		g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, lo);
	}

	/** Sunken area (display, slider track): outline + body + top/left shadow, faint bottom/right highlight. */
	static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h, int face) {
		outline(g, x, y, w, h, OUTLINE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
		int lo = darken(face, 0.5f);
		int hi = lighten(face, 0.10f);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, lo);
		g.fill(x + 1, y + 1, x + 2, y + h - 1, lo);
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, hi);
		g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, hi);
	}

	static void iconAt(GuiGraphicsExtractor g, Identifier icon, int x, int y, int color) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, icon, x, y, ICON, ICON, color);
	}

	/** Draw the button: embossed body (light outline on hover), centered icon + label. */
	static void button(GuiGraphicsExtractor g, Font font, Btn b, boolean hovered, int textColor) {
		button(g, font, b, hovered, textColor, false);
	}

	/** leftAlign: for list rows the icon + label start from the left. */
	static void button(GuiGraphicsExtractor g, Font font, Btn b, boolean hovered, int textColor, boolean leftAlign) {
		boolean on = b.on().getAsBoolean();
		int face = on ? (hovered ? BTN_ON_HOVER : BTN_ON) : (hovered ? b.hover() : b.color());
		outline(g, b.x(), b.y(), b.w(), b.h(), hovered ? OUTLINE_HOVER : OUTLINE);
		g.fill(b.x() + 1, b.y() + 1, b.x() + b.w() - 1, b.y() + b.h() - 1, face);
		int hi = lighten(face, 0.22f);
		int lo = darken(face, 0.38f);
		g.fill(b.x() + 1, b.y() + 1, b.x() + b.w() - 1, b.y() + 2, hi);
		g.fill(b.x() + 1, b.y() + 1, b.x() + 2, b.y() + b.h() - 1, hi);
		g.fill(b.x() + 1, b.y() + b.h() - 2, b.x() + b.w() - 1, b.y() + b.h() - 1, lo);
		g.fill(b.x() + b.w() - 2, b.y() + 1, b.x() + b.w() - 1, b.y() + b.h() - 1, lo);

		String label = b.label().get();
		Identifier ic = b.icon() == null ? null : b.icon().get();
		int tw = label.isEmpty() ? 0 : font.width(label);
		int iw = ic == null ? 0 : ICON + (tw > 0 ? 3 : 0);
		int total = iw + tw;
		int cx = leftAlign ? b.x() + 5 : b.x() + (b.w() - total) / 2;
		int cy = b.y() + b.h() / 2;
		if (ic != null) {
			iconAt(g, ic, cx, cy - ICON / 2, textColor);
			cx += iw;
		}
		if (tw > 0) {
			g.text(font, label, cx, cy - 4, textColor, true);
		}
	}
}
