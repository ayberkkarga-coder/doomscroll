package com.doomscroll.client;

import net.minecraft.network.chat.Component;

/**
 * Dil dosyasi kisayolu. GUI cizimi (GuiGraphicsExtractor.text/centeredText) duz String istedigi icin
 * cevrilmis metni cozup dondurur. Sunucudan oyuncuya giden mesajlarda bunu KULLANMA: orada
 * Component.translatable gonder, alan istemci kendi diliyle cozsun.
 */
public final class Lang {
	private Lang() {}

	public static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	/** AÇIK / KAPALI (buyuk harf, kumanda ve tablet arayuzu icin). */
	public static String onOff(boolean on) {
		return tr(on ? "gui.doomscroll.on" : "gui.doomscroll.off");
	}
}
