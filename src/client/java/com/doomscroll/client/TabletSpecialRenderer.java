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
 * Tablet in third person and in other players' hands: {@link TabletModel} body (thin slab, bezel, camera) with
 * the browser on top (your own tablet or that player's page). Same world-render path as the screen block.
 * Placement: the old WD minepad layout (x 1..15, z 0..9, 1/16 units); the screen sits on the slab at a 16:9 ratio.
 */
public class TabletSpecialRenderer implements SpecialModelRenderer<UUID> {
	public static final Identifier ID = Doomscroll.id("tablet");
	public static final Identifier BROWSER_TEXTURE_ID = Doomscroll.id("held_tablet_browser");
	private static final CefTexture TEXTURE = new CefTexture(Browsers::getTabletIfPresent);
	private static boolean textureRegistered = false;
	/** Slab thickness (block units); the screen sits on top of the slab. */
	private static final float T = 0.028f;

	/** Registers/refreshes the browser texture; false if not ready. Also used by the held-item mixin. */
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
			// Phone mode: the pad rotates 90 degrees around the screen normal (Y); the screen still faces the player, long edge vertical
			poseStack.translate(0.5f, T / 2f, 4.5f / 16f);
			poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90f));
			// ... and tilt it up around its short axis: the phone stands upright, the screen faces the player
			poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-80f));
			poseStack.translate(-0.5f, -T / 2f, -4.5f / 16f);
		}

		final int fullLight = 0xF000F0;
		final int noOverlay = OverlayTexture.NO_OVERLAY;

		// Slab: x 1..15, y 0..T, z center 4.5/16; screen 16:9
		final float x0 = 1f / 16f, x1 = 15f / 16f;
		final float sw = x1 - x0;
		final float sh = sw * 9f / 16f;
		final float zc = 4.5f / 16f;
		final float z0 = zc - sh / 2f, z1 = zc + sh / 2f;
		final float y = T + 0.0025f;

		// Body: R = -X (picture right is west: u=0 at x1), U = +Z (picture top is south: v=0 at z1), N = +Y
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TabletModel.WHITE), (pose, consumer) ->
			TabletModel.body(pose, consumer, 0.5f, T, zc, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, sw / 2f, sh / 2f, T, light, noOverlay));

		// Browser: inside the bezel, top face (+Y)
		final float b = TabletModel.BEZEL;
		final float ex0 = x0 + b, ex1 = x1 - b, ez0 = z0 + b, ez1 = z1 - b;
		if (tex != null) collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(tex), (pose, consumer) -> {
			if (portrait) {
				// portrait texture (720x1280): v along the long edge (x), u along the short edge (z)
				consumer.addVertex(pose, ex0, y, ez0).setColor(-1).setUv(0f, 1f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
				consumer.addVertex(pose, ex0, y, ez1).setColor(-1).setUv(1f, 1f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
				consumer.addVertex(pose, ex1, y, ez1).setColor(-1).setUv(1f, 0f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
				consumer.addVertex(pose, ex1, y, ez0).setColor(-1).setUv(0f, 0f).setOverlay(noOverlay).setLight(fullLight).setNormal(pose, 0f, 1f, 0f);
			} else {
				// landscape: picture upright for the holder (top edge away from the body) -> UV flipped 180 degrees
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

	/** For the model loading system: a simple unbaked that carries no data. */
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
