package com.doomscroll.client;

import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.util.function.Supplier;

/**
 * TextureManager'a kaydedilen sarmalayici: her karede verilen tarayicinin guncel
 * GPU dokusuna isaret eder. GUI blit(Identifier,...) bu yolu kullaninca metin/video
 * dogru cizilir (dogrudan textureView blit'i sadece duz renk basiyordu).
 */
public class CefTexture extends AbstractTexture {
	private final Supplier<CefBrowserView> source;

	public CefTexture(Supplier<CefBrowserView> source) {
		this.source = source;
	}

	public void update() {
		CefBrowserView b = source.get();
		if (b != null && b.getTexture() != null) {
			this.texture = b.getTexture();
			this.textureView = b.getTextureView();
		} else {
			this.texture = null;
			this.textureView = null;
		}
		this.sampler = Browsers.sampler();
	}

	@Override
	public void close() {
		// Dokunun sahibi CEF; burada serbest birakmiyoruz.
		this.texture = null;
		this.textureView = null;
	}
}
