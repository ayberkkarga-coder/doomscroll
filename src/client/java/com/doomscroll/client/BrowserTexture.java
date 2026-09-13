package com.doomscroll.client;

import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * TextureManager entry: every frame the fields are refreshed to point
 * at the MCEF browser's current texture. That way
 * RenderTypes.entityTranslucentEmissive(ID) samples the browser directly.
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
		// MCEF owns the texture; we do not close it here.
		this.texture = null;
		this.textureView = null;
	}
}
