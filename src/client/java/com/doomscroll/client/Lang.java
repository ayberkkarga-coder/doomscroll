package com.doomscroll.client;

import net.minecraft.network.chat.Component;

/**
 * Language-file shortcut. GUI drawing (GuiGraphicsExtractor.text/centeredText) wants a plain String, so this
 * resolves the translated text and returns it. Do NOT use it for messages going from the server to a player:
 * send Component.translatable there and let the receiving client resolve it in its own language.
 */
public final class Lang {
	private Lang() {}

	public static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	/** ON / OFF (upper case, for the remote and the tablet UI). */
	public static String onOff(boolean on) {
		return tr(on ? "gui.doomscroll.on" : "gui.doomscroll.off");
	}
}
