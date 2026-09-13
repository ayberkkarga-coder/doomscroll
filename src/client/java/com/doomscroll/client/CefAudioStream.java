package com.doomscroll.client;

import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * AudioStream that pipes the browser's audio into the Minecraft sound engine.
 * Mono 16-bit; every read returns a fixed-size chunk (the missing part is soft silence) so the OpenAL queue never runs dry.
 */
public final class CefAudioStream implements AudioStream {
	// Chunk duration comes from the profile: Minecraft queues 4 chunks -> latency ~ 4 x chunk (25 ms -> ~100 ms)
	private final Supplier<CefBrowserView> source;
	private final AudioFormat format;
	private final int chunkBytes;

	public CefAudioStream(Supplier<CefBrowserView> source) {
		this.source = source;
		CefBrowserView b = source.get();
		int rate = b != null && b.audioSampleRate() > 0 ? b.audioSampleRate() : 48000;
		this.format = new AudioFormat(rate, 16, 1, true, false);
		this.chunkBytes = (rate * DoomscrollConfig.get().audioChunkMs() / 1000) * 2;
	}

	/** A single buffer instead of reallocating on every read (~40 reads per second per stream). */
	@Nullable
	private ByteBuffer buffer;

	@Override
	public AudioFormat getFormat() {
		return format;
	}

	@Override
	public ByteBuffer read(int size) {
		ByteBuffer buf = buffer;
		if (buf == null) {
			buf = BufferUtils.createByteBuffer(chunkBytes);
			buffer = buf;
		}
		buf.clear();
		CefBrowserView b = source.get();
		if (b != null && b.hasAudioStream()) {
			b.readAudio(buf, chunkBytes);
			applyGain(buf, chunkBytes, DoomscrollConfig.get().audioBoost);
		} else {
			for (int i = 0; i < chunkBytes; i += 2) {
				buf.putShort((short) 0);
			}
		}
		buf.flip();
		return buf;
	}

	/**
	 * Multiplies the 16-bit mono samples by the gain; linear up to 0.7 of full scale, above that soft-limited
	 * with tanh (no clipping crackle). Leaves the samples untouched when the gain is 1.
	 */
	private static void applyGain(ByteBuffer buf, int bytes, float gain) {
		if (Math.abs(gain - 1f) < 0.01f) {
			return;
		}
		final float knee = 0.7f;
		final float room = 1f - knee;
		for (int i = 0; i + 1 < bytes; i += 2) {
			float x = buf.getShort(i) * gain / 32768f;
			float a = Math.abs(x);
			float y;
			if (a <= knee) {
				y = x;
			} else {
				float over = (float) Math.tanh((a - knee) / room) * room;
				y = x < 0 ? -(knee + over) : knee + over;
			}
			int v = Math.round(y * 32767f);
			if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
			buf.putShort(i, (short) v);
		}
	}

	@Override
	public void close() {
	}
}
