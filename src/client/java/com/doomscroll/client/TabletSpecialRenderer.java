package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.cef.api.CefBrowserView;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.function.Consumer;

/**
 * Ucuncu sahista ve baskalarinin elinde tablet: {@link TabletModel} govdesi (ince plaka, cerceve, kamera) ve
 * ustunde tarayici (kendi tabletin ya da o oyuncunun sayfasi). Ekran blogu ile ayni dunya-render yolu.
 * Konum: eski WD minepad yerlesimi (x 1..15, z 0..9, 1/16 birim); ekran 16:9 oranla plakaya oturur.
 */
public class TabletSpecialRenderer implements SpecialModelRenderer<UUID> {
	public static final Identifier ID = Doomscroll.id("tablet");
	public static final Identifier BROWSER_TEXTURE_ID = Doomscroll.id("held_tablet_browser");
	private static final CefTexture TEXTURE = new CefTexture(Browsers::getTabletIfPresent);
	private static boolean textureRegistered = false;
	/** Plaka kalinligi (blok birimi); ekran plakanin ustunde. */
	private static final float T = 0.028f;

	/** Tarayici dokusunu kaydeder/tazeler; hazir degilse false. Held-item mixin de kullanir. */
	public static boolean ensureTexture() {
		CefBrowserView b = Browsers.getOrCreateTablet();
		if (b == null || b.getTextureView() == null) {
			return false;
		}
		if (!textureRegistered) {
			Minecraft.getInstance().getTextureManager().register(BROWSER_TEXTURE_ID, TEXTURE);
			textureRegistered = true;
		}
		TEXTURE.update();
		return true;
	}

	@Override
	public void submit(UUID owner, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean hasFoilType, int color) {
		LocalPlayer me = Minecraft.getInstance().player;
		boolean local = owner == null || (me != null && owner.equals(me.getUUID()));
		final Identifier tex;
		final boolean portraitMode;
		if (local) {
			ensureTexture();
			CefBrowserView lb = Browsers.getTabletIfPresent();
			tex = lb != null && lb.getTextureView() != null ? BROWSER_TEXTURE_ID : null;
			portraitMode = Browsers.isTabletPortrait();
		} else {
			tex = RemoteTablets.textureFor(owner);
			portraitMode = RemoteTablets.isPortrait(owner);
		}

		poseStack.pushPose();
		final boolean portrait = portraitMode;
		if (portrait) {
			// Telefon modu: pad ekran normali (Y) etrafinda 90 derece doner; ekran yine oyuncuya bakar, uzun kenar dik
			poseStack.translate(0.5f, T / 2f, 4.5f / 16f);
			poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90f));
			// ... ve kisa ekseni etrafinda kaldir: telefon dik dursun, ekran oyuncuya baksin
			poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-80f));
			poseStack.translate(-0.5f, -T / 2f, -4.5f / 16f);
		}

		final int fullLight = 0xF000F0;
		final int noOverlay = OverlayTexture.NO_OVERLAY;

		// Plaka: x 1..15, y 0..T, z ortasi 4.5/16; ekran 16:9
		final float x0 = 1f / 16f, x1 = 15f / 16f;
		final float sw = x1 - x0;
		final float sh = sw * 9f / 16f;
		final float zc = 4.5f / 16f;
		final float z0 = zc - sh / 2f, z1 = zc + sh / 2f;
		final float y = T + 0.0025f;

		// Govde: R = -X (resmin sagi batida: u=0 x1'de), U = +Z (resmin ustu guneyde: v=0 z1'de), N = +Y
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TabletModel.WHITE), (pose, consumer) ->
			TabletModel.body(pose, consumer, 0.5f, T, zc, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, sw / 2f, sh / 2f, T, light, noOverlay));

		// Tarayici: cerceve icinde, ust yuz (+Y)
		final float b = TabletModel.BEZEL;
		final float ex0 = x0 + b, ex1 = x1 - b, ez0 = z0 + b, ez1 = z1 - b;
		if (tex != null) collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(tex), (pose, consumer) -> {
			if (portrait) {
				// dik doku (720x1280): v uzun kenar (x) boyunca, u kisa kenar (z) boyunca
				consumer.addVertex(pose, ex0, y, ez0).setColor(-1).setUv(0f, 1f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
				consumer.addVertex(pose, ex0, y, ez1).setColor(-1).setUv(1f, 1f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
				consumer.addVertex(pose, ex1, y, ez1).setColor(-1).setUv(1f, 0f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
				consumer.addVertex(pose, ex1, y, ez0).setColor(-1).setUv(0f, 0f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
			} else {
				// yatay: goruntu tutan kisiye duz (ust kenar govdeden uzakta) -> 180 derece cevrili UV
				consumer.addVertex(pose, ex0, y, ez0).setColor(-1).setUv(1f, 1f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
				consumer.addVertex(pose, ex0, y, ez1).setColor(-1).setUv(1f, 0f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
				consumer.addVertex(pose, ex1, y, ez1).setColor(-1).setUv(0f, 0f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
				consumer.addVertex(pose, ex1, y, ez0).setColor(-1).setUv(0f, 1f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
			}
		});

		poseStack.popPose();
	}

	@Override
	public void getExtents(Consumer<Vector3fc> consumer) {
		consumer.accept(new Vector3f(0f, 0f, 0f));
		consumer.accept(new Vector3f(1f, 0.1f, 0.5625f));
	}

	/** Model yukleme sistemi icin: veri tasimayan basit unbaked. */
	@Override
	public UUID extractArgument(net.minecraft.world.item.ItemStack stack) {
		return stack.get(Doomscroll.TABLET_OWNER);
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked<UUID> {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(Unbaked::new);

		@Override
		public SpecialModelRenderer<UUID> bake(SpecialModelRenderer.BakingContext context) {
			return new TabletSpecialRenderer();
		}

		@Override
		public MapCodec<? extends SpecialModelRenderer.Unbaked<UUID>> type() {
			return MAP_CODEC;
		}
	}
}
