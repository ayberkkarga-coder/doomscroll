package com.doomscroll.client;

import com.doomscroll.ScreenBlockEntity;
import net.minecraft.client.Minecraft;

/** Screen URL sync now lives per screen inside ScreenBrowsers; this class is a thin wrapper. */
public final class ScreenSync {
	private ScreenSync() {}

	public static void applyScreenUrl(ScreenBlockEntity be) {
		// applied inside ScreenBrowsers.noteRendered
	}

	public static void tick(Minecraft mc) {
		ScreenBrowsers.tick(mc);
	}

	public static void clear() {
		ScreenBrowsers.closeAll();
	}
}
