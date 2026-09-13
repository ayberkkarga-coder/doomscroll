package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Shows the nearby screen's subtitles on the game HUD (above the hotbar), so they stay readable even when you
 * are not looking at the screen. The text comes from the page (YouTube caption box, HTML5 text tracks, the
 * player's subtitle layer); every player reads it from their own browser.
 */
public final class SubtitleHud implements HudElement {
	private static final int MAX_LINES = 3;

	private SubtitleHud() {}

	public static void register() {
		HudElementRegistry.addLast(Doomscroll.id("subtitles"), new SubtitleHud());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!DoomscrollConfig.get().subtitles) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		String text = ScreenBrowsers.currentSubtitle();
		if (text == null || text.isBlank()) {
			return;
		}
		Font font = mc.font;
		int maxW = Math.min(g.guiWidth() * 3 / 5, 380);
		List<FormattedCharSequence> lines = font.split(Component.literal(text), maxW);
		if (lines.size() > MAX_LINES) {
			lines = lines.subList(lines.size() - MAX_LINES, lines.size());
		}
		int lineH = font.lineHeight + 2;
		int y = g.guiHeight() - 70 - lines.size() * lineH;
		int cx = g.guiWidth() / 2;
		for (FormattedCharSequence line : lines) {
			int w = font.width(line);
			g.fill(cx - w / 2 - 4, y - 2, cx + w / 2 + 4, y + lineH - 1, 0xA0000000);
			g.text(font, line, cx - w / 2, y, 0xFFFFFFFF, true);
			y += lineH;
		}
	}
}
