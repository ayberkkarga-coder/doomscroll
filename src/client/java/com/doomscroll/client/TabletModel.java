package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.Identifier;

/**
 * Tablet govdesi (elde, ucuncu sahista, baskalarinin elinde ayni geometri): ince koyu plaka, acik renk ince kenar,
 * siyah cerceve (bezel), on kamera noktasi, arkada kamera karesi, yanda iki tus. Ekranin kendisini cagiran cizer.
 * Cerceve: R = ekranin sagi, U = ekranin ustu, N = ekran normali (R x U = N); ekran duzlemi w = 0, govde -N yonunde.
 * Renkler beyaz dokuya vertex rengi olarak verilir (tam renk, dunya isigiyla golgelenir).
 */
public final class TabletModel {
	private TabletModel() {
	}

	public static final Identifier WHITE = Doomscroll.id("textures/block/white.png");
	static final int C_BODY = 0xFF2A2A31;
	static final int C_BACK = 0xFF1E1E23;
	static final int C_RIM = 0xFF45454F;
	static final int C_BEZEL = 0xFF07070A;
	static final int C_CAM = 0xFF2F3F5C;
	static final int C_CAM_RING = 0xFF121216;
	static final int C_BTN = 0xFF3C3C46;
	/** Ekranin cerceveden ici (ekran kenari ile plaka kenari arasi), plaka genisligine oranla degil, mutlak (blok birimi). */
	public static final float BEZEL = 0.02f;
	private static final float RIM = 0.005f;
	private static final float E = 0.0006f;

	/**
	 * Govdeyi cizer. (cx,cy,cz) ekran merkezi; halfW/halfH ekran duzlemindeki yarim boyutlar (plakanin kendisi);
	 * thickness plaka kalinligi (-N yonunde). Ekran dortgeni: merkezden +-(halfW-BEZEL), +-(halfH-BEZEL), w = +2E ustu.
	 */
	public static void body(PoseStack.Pose p, VertexConsumer c, float cx, float cy, float cz,
					 float rx, float ry, float rz, float ux, float uy, float uz, float nx, float ny, float nz,
					 float halfW, float halfH, float thickness, int light, int overlay) {
		// Plaka: on yuz acik kenar rengi (RIM), arka koyu, yanlar govde
		box(p, c, cx, cy, cz, rx, ry, rz, ux, uy, uz, nx, ny, nz, halfW, halfH, 0f, thickness, C_RIM, C_BACK, C_BODY, light, overlay);
		// Siyah cerceve: kenardan RIM kadar iceride, hafif ustte
		quad(p, c,
				cx - rx * (halfW - RIM) - ux * (halfH - RIM) + nx * E, cy - ry * (halfW - RIM) - uy * (halfH - RIM) + ny * E, cz - rz * (halfW - RIM) - uz * (halfH - RIM) + nz * E,
				rx * 2 * (halfW - RIM), ry * 2 * (halfW - RIM), rz * 2 * (halfW - RIM),
				ux * 2 * (halfH - RIM), uy * 2 * (halfH - RIM), uz * 2 * (halfH - RIM),
				nx, ny, nz, C_BEZEL, light, overlay);
		// On kamera: ust cerceve bandinin ortasinda kucuk nokta
		float dot = 0.011f;
		float band = (halfH - RIM) - (halfH - BEZEL); // bandin genisligi
		float dy = halfH - RIM - band / 2f - dot / 2f;
		quad(p, c,
				cx - rx * (dot / 2f) + ux * dy + nx * 2 * E, cy - ry * (dot / 2f) + uy * dy + ny * 2 * E, cz - rz * (dot / 2f) + uz * dy + nz * 2 * E,
				rx * dot, ry * dot, rz * dot, ux * dot, uy * dot, uz * dot, nx, ny, nz, C_CAM_RING, light, overlay);
		float dot2 = 0.006f;
		quad(p, c,
				cx - rx * (dot2 / 2f) + ux * (dy + (dot - dot2) / 2f) + nx * 3 * E, cy - ry * (dot2 / 2f) + uy * (dy + (dot - dot2) / 2f) + ny * 3 * E, cz - rz * (dot2 / 2f) + uz * (dy + (dot - dot2) / 2f) + nz * 3 * E,
				rx * dot2, ry * dot2, rz * dot2, ux * dot2, uy * dot2, uz * dot2, nx, ny, nz, C_CAM, light, overlay);
		// Arka kamera karesi (halka + mercek), arkadan bakinca sol ustte
		float sq = 0.06f;
		float bx = cx + rx * (halfW - 0.05f - sq) + ux * (halfH - 0.04f - sq) - nx * (thickness + E);
		float by = cy + ry * (halfW - 0.05f - sq) + uy * (halfH - 0.04f - sq) - ny * (thickness + E);
		float bz = cz + rz * (halfW - 0.05f - sq) + uz * (halfH - 0.04f - sq) - nz * (thickness + E);
		quad(p, c, bx, by, bz, ux * sq, uy * sq, uz * sq, rx * sq, ry * sq, rz * sq, -nx, -ny, -nz, C_CAM_RING, light, overlay);
		float in = 0.015f;
		quad(p, c, bx + rx * in + ux * in - nx * E, by + ry * in + uy * in - ny * E, bz + rz * in + uz * in - nz * E,
				ux * (sq - 2 * in), uy * (sq - 2 * in), uz * (sq - 2 * in), rx * (sq - 2 * in), ry * (sq - 2 * in), rz * (sq - 2 * in), -nx, -ny, -nz, C_CAM, light, overlay);
		// Yan tuslar (+R kenari, ust bolge): guc + ses; eksenler (-N, U, R)
		float t2 = thickness / 2f;
		for (int i = 0; i < 2; i++) {
			float off = halfH - 0.08f - i * 0.075f;
			float bcx = cx + rx * (halfW + 0.001f) + ux * off - nx * t2;
			float bcy = cy + ry * (halfW + 0.001f) + uy * off - ny * t2;
			float bcz = cz + rz * (halfW + 0.001f) + uz * off - nz * t2;
			box(p, c, bcx, bcy, bcz, -nx, -ny, -nz, ux, uy, uz, rx, ry, rz, thickness * 0.22f, i == 0 ? 0.02f : 0.03f, 0.004f, 0f, C_BTN, C_BTN, C_BTN, light, overlay);
		}
	}

	/**
	 * Yonlu kutu: (R,U,N) sag el ucgeni; merkez ekran duzleminde, N boyunca [-back, +front].
	 * Yuz sirasi kose = o, o+a, o+a+b, o+b; a x b = disari normal (sirt yuzu ayiklama icin).
	 */
	static void box(PoseStack.Pose p, VertexConsumer c, float cx, float cy, float cz,
					float rx, float ry, float rz, float ux, float uy, float uz, float nx, float ny, float nz,
					float hw, float hh, float front, float back, int cFront, int cBack, int cSide, int light, int overlay) {
		float w = 2 * hw, h = 2 * hh, d = front + back;
		// sol-alt-arka kose
		float ox = cx - rx * hw - ux * hh - nx * back;
		float oy = cy - ry * hw - uy * hh - ny * back;
		float oz = cz - rz * hw - uz * hh - nz * back;
		// on (+N): o + N*d, a = R*w, b = U*h
		quad(p, c, ox + nx * d, oy + ny * d, oz + nz * d, rx * w, ry * w, rz * w, ux * h, uy * h, uz * h, nx, ny, nz, cFront, light, overlay);
		// arka (-N): a = U*h, b = R*w
		quad(p, c, ox, oy, oz, ux * h, uy * h, uz * h, rx * w, ry * w, rz * w, -nx, -ny, -nz, cBack, light, overlay);
		// +R: o + R*w, a = U*h, b = N*d
		quad(p, c, ox + rx * w, oy + ry * w, oz + rz * w, ux * h, uy * h, uz * h, nx * d, ny * d, nz * d, rx, ry, rz, cSide, light, overlay);
		// -R: a = N*d, b = U*h
		quad(p, c, ox, oy, oz, nx * d, ny * d, nz * d, ux * h, uy * h, uz * h, -rx, -ry, -rz, cSide, light, overlay);
		// +U: o + U*h, a = N*d, b = R*w
		quad(p, c, ox + ux * h, oy + uy * h, oz + uz * h, nx * d, ny * d, nz * d, rx * w, ry * w, rz * w, ux, uy, uz, cSide, light, overlay);
		// -U: a = R*w, b = N*d
		quad(p, c, ox, oy, oz, rx * w, ry * w, rz * w, nx * d, ny * d, nz * d, -ux, -uy, -uz, cSide, light, overlay);
	}

	static void quad(PoseStack.Pose p, VertexConsumer c, float ox, float oy, float oz,
					 float ax, float ay, float az, float bx, float by, float bz,
					 float nx, float ny, float nz, int color, int light, int overlay) {
		c.addVertex(p, ox, oy, oz).setColor(color).setUv(0.5f, 0.5f).setOverlay(overlay).setLight(light).setNormal(p, nx, ny, nz);
		c.addVertex(p, ox + ax, oy + ay, oz + az).setColor(color).setUv(0.5f, 0.5f).setOverlay(overlay).setLight(light).setNormal(p, nx, ny, nz);
		c.addVertex(p, ox + ax + bx, oy + ay + by, oz + az + bz).setColor(color).setUv(0.5f, 0.5f).setOverlay(overlay).setLight(light).setNormal(p, nx, ny, nz);
		c.addVertex(p, ox + bx, oy + by, oz + bz).setColor(color).setUv(0.5f, 0.5f).setOverlay(overlay).setLight(light).setNormal(p, nx, ny, nz);
	}
}
