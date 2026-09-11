package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.ScreenBlock;
import com.doomscroll.ScreenBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity, ScreenRenderState> {
	private static final Identifier OFF_TEXTURE = Doomscroll.id("textures/block/screen_off.png");

	public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public ScreenRenderState createRenderState() {
		return new ScreenRenderState();
	}

	@Override
	public void extractRenderState(ScreenBlockEntity be, ScreenRenderState state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
		BlockEntityRenderer.super.extractRenderState(be, state, partialTick, cameraPos, crumblingOverlay);
		state.anchor = be.isAnchor();
		state.on = be.isOn();
		state.facing = be.getBlockState().getValue(ScreenBlock.FACING);
		state.top = ScreenBlock.top(be.getBlockState());
		state.screenWidth = be.getWidth();
		state.screenHeight = be.getHeight();

		if (!state.anchor) {
			return;
		}

		// Ekran basina tarayici: kaydi guncelle, gerekiyorsa ac; dokusunu al
		ScreenBrowsers.Screen s = ScreenBrowsers.noteRendered(be, state.on);
		state.texId = ScreenBrowsers.textureFor(s);
		state.hasTexture = state.texId != null;

		ScreenTracker.note(be.getBlockPos(), state.facing, state.top, state.screenWidth, state.screenHeight, state.on);
		state.pointers = DoomscrollConfig.get().pointer ? Pointers.at(be.getBlockPos()) : null;
		float glow = DoomscrollConfig.get().screenGlow;
		if (state.on && state.hasTexture && glow > 0.01f && s != null && s.browser != null) {
			state.glow = ScreenGlow.patches(be.getBlockPos(), state.facing, state.top, state.screenWidth, state.screenHeight);
			state.tiles = s.browser.lightTiles();
			state.glowIntensity = glow;
		} else {
			state.glow = null;
			state.tiles = null;
		}
		if (state.on) {
			Browsers.noteScreen(be.getBlockPos());
		} else {
			Browsers.noteScreenOff(be.getBlockPos());
		}
	}

	@Override
	public void submit(ScreenRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraState) {
		if (!state.anchor) {
			return;
		}
		boolean live = state.on && state.hasTexture;
		float w = state.screenWidth;
		float h = state.screenHeight;
		Direction facing = state.facing;

		// Ekran isigi: ekranin onundeki yuzeylere kendinden aydinlik, yari saydam renk yamalari (blok kosesi
		// cercevesinde, donusum yok). Ekranin kendisiyle ayni cizim yolu: shader paketlerinde de gorunur.
		if (live && state.glow != null && state.tiles != null && !state.glow.isEmpty()) {
			final java.util.List<ScreenGlow.Patch> patches = state.glow;
			final float[] tiles = state.tiles;
			final float inten = state.glowIntensity;
			float sr = 0, sg = 0, sb = 0;
			final int nt = Math.max(1, tiles.length / 3);
			for (int i = 0; i + 2 < tiles.length; i += 3) {
				sr += tiles[i];
				sg += tiles[i + 1];
				sb += tiles[i + 2];
			}
			final float avgR = sr / nt, avgG = sg / nt, avgB = sb / nt;
			final boolean smooth = DoomscrollConfig.get().screenGlowSmooth;
			// kare basina renk tablosu: [karisim kovasi][kutucuk] -> algisal parlaklikla duzeltilmis rgb
			final int mbN = ScreenGlow.MIX_BUCKETS;
			final float[] table = new float[mbN * nt * 3];
			for (int mb = 0; mb < mbN; mb++) {
				float mix = ScreenGlow.mixOfBucket(mb);
				for (int t = 0; t < nt; t++) {
					int ti = (mb * nt + t) * 3;
					ScreenGlow.boostRgb(tiles[t * 3] * (1 - mix) + avgR * mix, tiles[t * 3 + 1] * (1 - mix) + avgG * mix,
							tiles[t * 3 + 2] * (1 - mix) + avgB * mix, table, ti);
				}
			}
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE), (pose, consumer) -> {
				for (ScreenGlow.Patch p : patches) {
					if (p.tile() < 0 || p.tile() >= nt) continue;
					int o = (ScreenGlow.mixBucket(p.dist()) * nt + p.tile()) * 3;
					float pr = table[o], pg = table[o + 1], pb = table[o + 2];
					float pa = Math.min(1f, p.weight() * inten * 0.9f);
					boolean blend = smooth && p.vTiles() != null;
					if (!blend && (pa < 0.01f || pr + pg + pb < 0.03f)) continue;
					float[] v = p.v();
					Direction d = p.dir();
					for (int j = 0; j < 4; j++) {
						float r = pr, g = pg, b = pb, a = pa;
						if (blend) {
							// kose rengi = ayni yonlu komsu yuzlerin agirlikli ortalamasi (blok blok yerine kesintisiz)
							float cr = 0, cg = 0, cb = 0, ws = 0;
							int n = 0;
							for (int k = 0; k < 4; k++) {
								int t = p.vTiles()[j * 4 + k];
								if (t < 0 || t >= nt) break;
								float wk = p.vWeights()[j * 4 + k];
								int ti = (p.vMix()[j * 4 + k] * nt + t) * 3;
								cr += table[ti] * wk;
								cg += table[ti + 1] * wk;
								cb += table[ti + 2] * wk;
								ws += wk;
								n++;
							}
							if (n > 0 && ws > 0) {
								r = cr / ws;
								g = cg / ws;
								b = cb / ws;
								a = Math.min(1f, ws / n * inten * 0.9f);
							}
						}
						consumer.addVertex(pose, v[j * 3], v[j * 3 + 1], v[j * 3 + 2]).setColor((int) (r * 255), (int) (g * 255), (int) (b * 255), (int) (a * 255))
								.setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, d.getStepX(), d.getStepY(), d.getStepZ());
					}
				}
			});
		}

		poseStack.pushPose();
		poseStack.translate(0.5, 0.5, 0.5);
		// Yerel cerceve: +X = bakanin sagi (ust x on), +Y = resmin ustu, +Z = on yuz (bakana dogru).
		// Duvar, yer ve tavan ekrani ayni matrisle; ScreenTracker.raycast ve ScreenGlow ayni cerceveyi kullanir.
		Direction topDir = state.top;
		Direction rightDir = ScreenBlock.right(facing, topDir);
		poseStack.mulPose(new org.joml.Matrix4f(new org.joml.Matrix3f(
				rightDir.getStepX(), rightDir.getStepY(), rightDir.getStepZ(),
				topDir.getStepX(), topDir.getStepY(), topDir.getStepZ(),
				facing.getStepX(), facing.getStepY(), facing.getStepZ())));
		// Derinlik araliklari: blok yuzu 0.5, siyah zemin 0.503, canli goruntu +0.004, imlecler +0.006. Yarim milimetrelik
		// araliklar uzaktan/egik bakista (ozellikle shader'da) z-fighting yapiyordu (noktali bant).
		poseStack.translate(0.0, 0.0, 0.503);

		final int light = 0xF000F0; // ekran kendi isigini yayar
		final int overlay = OverlayTexture.NO_OVERLAY;
		final Identifier tex = live ? state.texId : OFF_TEXTURE;
		final int shade = 255;

		if (live) {
			// Canli ekran: "eyes" tipi (shader paketlerinde isik yayan, toplamsal karisim). Siyah pikseller
			// seffaf kalmasin diye altina normal aydinlatilan siyah bir zemin cizilir.
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(OFF_TEXTURE), (pose, consumer) ->
				quad(pose, consumer, w, h, 0f, 0, overlay, light));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(tex), (pose, consumer) ->
				quad(pose, consumer, w, h, 0.004f, shade, overlay, light));
		} else {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(tex), (pose, consumer) ->
				quad(pose, consumer, w, h, 0f, shade, overlay, light));
		}

		// Paylasimli isaretci: baskalarinin crosshair'i (renkli nokta + koyu kenar), panelin hemen onunde
		if (live && state.pointers != null && !state.pointers.isEmpty()) {
			final java.util.List<Pointers.Pointer> pts = state.pointers;
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE), (pose, consumer) -> {
				for (Pointers.Pointer pt : pts) {
					float px = 0.5f - (1f - pt.u()) * w;
					float py = -0.5f + (1f - pt.v()) * h;
					dot(pose, consumer, px, py, 0.075f, 0.006f, 0x202020, 230, overlay, light);
					dot(pose, consumer, px, py, 0.05f, 0.0065f, pt.color(), 255, overlay, light);
				}
			});
		}

		poseStack.popPose();
	}

	private static final Identifier WHITE_TEXTURE = Doomscroll.id("textures/block/white.png");

	/** Ekran duzleminde kucuk kare (isaretci). */
	private static void dot(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer c, float cx, float cy, float r, float z, int rgb, int a, int overlay, int light) {
		int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;
		c.addVertex(pose, cx + r, cy - r, z).setColor(cr, cg, cb, a).setUv(0.5f, 0.5f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
		c.addVertex(pose, cx - r, cy - r, z).setColor(cr, cg, cb, a).setUv(0.5f, 0.5f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
		c.addVertex(pose, cx - r, cy + r, z).setColor(cr, cg, cb, a).setUv(0.5f, 0.5f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
		c.addVertex(pose, cx + r, cy + r, z).setColor(cr, cg, cb, a).setUv(0.5f, 0.5f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
	}

	/** Ekran duzlemi: (0.5,-0.5) sag-alt, (0.5-w, -0.5+h) sol-ust; +Z bakar. */
	private static void quad(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, float w, float h, float z, int c, int overlay, int light) {
		float x0 = 0.5f, x1 = 0.5f - w, y0 = -0.5f, y1 = -0.5f + h;
		consumer.addVertex(pose, x0, y0, z).setColor(c, c, c, 255).setUv(1f, 1f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
		consumer.addVertex(pose, x1, y0, z).setColor(c, c, c, 255).setUv(0f, 1f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
		consumer.addVertex(pose, x1, y1, z).setColor(c, c, c, 255).setUv(0f, 0f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
		consumer.addVertex(pose, x0, y1, z).setColor(c, c, c, 255).setUv(1f, 0f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 128;
	}
}
