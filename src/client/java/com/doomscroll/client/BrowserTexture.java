package com.doomscroll.client;

import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * TextureManager kaydi: her karede alanlar MCEF tarayicisinin
 * guncel dokusuna isaret edecek sekilde tazelenir. Boylece
 * RenderTypes.entityTranslucentEmissive(ID) dogrudan tarayiciyi orneklendirir.
 */
public class BrowserTexture extends AbstractTexture {

	public void update() {
		CefBrowserView b = Browsers.getIfPresent();
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
		// Dokunun sahibi MCEF; burada kapatmiyoruz.
		this.texture = null;
		this.textureView = null;
	}
}
