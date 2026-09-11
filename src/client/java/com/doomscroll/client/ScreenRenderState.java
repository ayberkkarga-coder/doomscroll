package com.doomscroll.client;

import net.minecraft.resources.Identifier;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

public class ScreenRenderState extends BlockEntityRenderState {
	public boolean anchor = true;
	public boolean on = true;
	/** On yuz (duvarda yatay, yerde UP, tavanda DOWN) ve resmin ust kenari (duvarda UP). */
	public Direction facing = Direction.NORTH;
	public Direction top = Direction.UP;
	public float screenWidth = 1.0f;
	public float screenHeight = 1.0f;
	public boolean hasTexture = false;
	public Identifier texId;
	/** Ekran isigi yamalari (null = kapali), tarayicinin renk haritasi ve yogunluk. */
	public java.util.List<ScreenGlow.Patch> glow;
	public float[] tiles;
	public float glowIntensity;
	/** Baskalarinin imlecleri (null = kapali). */
	public java.util.List<Pointers.Pointer> pointers;
}
