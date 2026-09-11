package com.doomscroll.client;

import com.doomscroll.ScreenBlockEntity;
import net.minecraft.client.Minecraft;

/** Ekran adresi senkronu artik ekran basina ScreenBrowsers icinde; bu sinif ince bir sarmalayici. */
public final class ScreenSync {
	private ScreenSync() {}

	public static void applyScreenUrl(ScreenBlockEntity be) {
		// ScreenBrowsers.noteRendered icinde uygulanir
	}

	public static void tick(Minecraft mc) {
		ScreenBrowsers.tick(mc);
	}

	public static void clear() {
		ScreenBrowsers.closeAll();
	}
}
