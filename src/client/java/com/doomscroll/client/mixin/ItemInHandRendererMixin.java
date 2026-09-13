package com.doomscroll.client.mixin;

import com.doomscroll.Doomscroll;
import com.doomscroll.client.Browsers;
import com.doomscroll.client.DoomscrollConfig;
import com.doomscroll.client.TabletModel;
import com.doomscroll.client.TabletSpecialRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Held tablet: the grip of the WebDisplays 1.20 (CinemaMod branch) MinePadRenderer (one-handed, at the side) is kept
 * exactly; only the body is drawn with {@link TabletModel} (thin slab, bezel, camera). Arm: vanilla renderPlayerArm.
 * WebDisplays is public domain (montoyo).
 */
@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	private static final float PI = (float) Math.PI;
	private static final int FULL_LIGHT = 0xF000F0;
	private static final int NO_OVERLAY = OverlayTexture.NO_OVERLAY;
	/** Slab thickness (block units). */
	private static final float THICK = 0.022f;

	@Shadow
	protected abstract void renderPlayerArm(PoseStack pose, SubmitNodeCollector collector, int light,
										   float equipProgress, float swingProgress, HumanoidArm arm);

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void doomscroll$tablet(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand,
								   float swingProgress, ItemStack stack, float equipProgress,
								   PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (stack.getItem() != Doomscroll.TABLET_ITEM) {
			return;
		}

		ci.cancel();
		pose.pushPose();
		dampBob(player, partialTick, pose);

		HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
		float sign = arm == HumanoidArm.RIGHT ? 1.0f : -1.0f;

		// --- WD: precomputations ---
		float sqrtSwing = (float) Math.sqrt(swingProgress);
		float sinSqrtSwing1 = (float) Math.sin(sqrtSwing * PI);
		float sinSqrtSwing2 = (float) Math.sin(sqrtSwing * PI * 2.0f);
		float sinSwing1 = (float) Math.sin(swingProgress * PI);
		float sinSwing2 = (float) Math.sin(swingProgress * swingProgress * PI);

		boolean portrait = Browsers.isTabletPortrait();
		// Always at the side/in hand (one-handed); no bringing it forward with shift
		boolean sideHold = true;

		// --- Arm (WD renderArmFirstPerson == vanilla renderPlayerArm) ---
		if (!player.isInvisible()) {
			pose.pushPose();
			renderPlayerArm(pose, collector, light, equipProgress, swingProgress, arm);
			pose.popPose();
		}

		// --- WD minePad transform ---
		pose.pushPose();
		pose.translate(sign * -0.4f * sinSqrtSwing1, 0.2f * sinSqrtSwing2, -0.2f * sinSwing1);
		pose.translate(sign * 0.56f, -0.52f - equipProgress * 0.6f, -0.72f);
		pose.mulPose(Axis.YP.rotationDegrees(sign * (45.0f - sinSwing2 * 20.0f)));
		pose.mulPose(Axis.ZP.rotationDegrees(sign * sinSqrtSwing1 * -20.0f));
		pose.mulPose(Axis.XP.rotationDegrees(sinSqrtSwing1 * -80.0f));
		pose.mulPose(Axis.YP.rotationDegrees(sign * -45.0f));

		if (sideHold) {
			pose.translate(0.0f, 0.0f, -0.2f);
			pose.mulPose(Axis.YP.rotationDegrees(20.0f * -sign));
			float total = 0.475f;
			float off = -0.025f; // WD: "gotta love magic numbers"
			pose.translate(-(total - off) + (off * sign), -0.1f, 0.0f);
			pose.mulPose(Axis.ZP.rotationDegrees(1.0f));
		} else if (sign >= 0) {
			pose.translate(-1.065f, 0.0f, 0.0f);
		} else {
			pose.translate(0.065f, 0.0f, 0.0f);
		}

		// --- WD ModelMinePad + web view (same local space): the screen quad is exactly the same as before ---
		pose.translate(0.063f, 0.28f, 0.001f);
		final float x1 = 0.0f, y1 = 0.0f;
		final float x2 = 27.65f / 32.0f + 0.01f;
		final float y2 = 14.0f / 32.0f + 0.002f;

		final float sx0, sy0, sx1, sy1; // screen quad
		if (portrait) {
			// Phone mode: upright 9:16 surface (tablet browser 720x1280)
			final float pw = 0.34f;
			final float ph = pw * 16f / 9f;
			final float cx = x2 / 2f;
			sx0 = cx - pw / 2f;
			sy0 = y1 - 0.06f;
			sx1 = cx + pw / 2f;
			sy1 = sy0 + ph;
		} else {
			sx0 = x1;
			sy0 = y1;
			sx1 = x2;
			sy1 = y2;
		}
		// Body: a slab extending the bezel width around the screen quad, screen faces +Z
		final float b = TabletModel.BEZEL;
		final float ccx = (sx0 + sx1) / 2f, ccy = (sy0 + sy1) / 2f;
		final float hw = (sx1 - sx0) / 2f + b, hh = (sy1 - sy0) / 2f + b;
		collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TabletModel.WHITE), (p, c) ->
			TabletModel.body(p, c, ccx, ccy, -0.002f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, hw, hh, THICK, light, NO_OVERLAY));
		if (TabletSpecialRenderer.ensureTexture()) {
			collector.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(TabletSpecialRenderer.BROWSER_TEXTURE_ID), (p, c) ->
				rect(p, c, sx0, sy0, sx1, sy1, 0.003f, 0f, 0f, 1f, 1f, -1));
		}

		pose.popPose();
		pose.popPose();
	}

	/**
	 * Reduces hand bobbing while walking/running: applies a given percentage of vanilla bobView (translation + Z/X
	 * rotations) in reverse. config tabletSway = remaining bobbing fraction (0 = steady, 1 = vanilla). Only while view
	 * bobbing is enabled and the camera is on this player.
	 */
	private static void dampBob(AbstractClientPlayer player, float partialTick, PoseStack pose) {
		Minecraft mc = Minecraft.getInstance();
		float keep = DoomscrollConfig.get().tabletSway;
		if (keep >= 0.999f || !mc.options.bobView().get() || mc.getCameraEntity() != player) {
			return;
		}
		float k = 1f - Math.max(0f, keep);
		ClientAvatarState st = player.avatarState();
		float f1 = st.getBackwardsInterpolatedWalkDistance(partialTick);
		float f2 = st.getInterpolatedBob(partialTick);
		pose.mulPose(Axis.XP.rotationDegrees(-k * Math.abs(Mth.cos(f1 * PI - 0.2f) * f2) * 5.0f));
		pose.mulPose(Axis.ZP.rotationDegrees(-k * Mth.sin(f1 * PI) * f2 * 3.0f));
		pose.translate(-k * Mth.sin(f1 * PI) * f2 * 0.5f, k * Math.abs(Mth.cos(f1 * PI) * f2), 0.0f);
	}

	/** Draws the (x0,y0)-(x1,y1) rectangle as a face looking towards +Z. UV: (x0,y0)->(u0,v1), (x1,y1)->(u1,v0). */
	private static void rect(PoseStack.Pose p, VertexConsumer c,
							 float x0, float y0, float x1, float y1, float z,
							 float u0, float v0, float u1, float v1, int color) {
		c.addVertex(p, x0, y0, z).setColor(color).setUv(u0, v1).setOverlay(NO_OVERLAY).setLight(FULL_LIGHT).setNormal(p, 0f, 0f, 1f);
		c.addVertex(p, x1, y0, z).setColor(color).setUv(u1, v1).setOverlay(NO_OVERLAY).setLight(FULL_LIGHT).setNormal(p, 0f, 0f, 1f);
		c.addVertex(p, x1, y1, z).setColor(color).setUv(u1, v0).setOverlay(NO_OVERLAY).setLight(FULL_LIGHT).setNormal(p, 0f, 0f, 1f);
		c.addVertex(p, x0, y1, z).setColor(color).setUv(u0, v0).setOverlay(NO_OVERLAY).setLight(FULL_LIGHT).setNormal(p, 0f, 0f, 1f);
	}
}
