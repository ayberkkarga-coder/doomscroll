package com.doomscroll.client;

import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.util.function.Supplier;

/**
 * Wrapper registered with the TextureManager: every frame it points at the given browser's
 * current GPU texture. When GUI blit(Identifier,...) goes through this path, text/video is
 * drawn correctly (blitting the textureView directly only produced a flat colour).
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
		// CEF owns the texture; we do not release it here.
		this.texture = null;
		this.textureView = null;
	}
}
