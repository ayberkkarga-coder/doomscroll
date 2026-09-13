package com.doomscroll.client;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers for YouTube URLs (used by the quality cap and auto-advance). */
public final class YouTube {
	private static final Pattern[] ID_PATTERNS = {
			Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})"),
			Pattern.compile("/shorts/([A-Za-z0-9_-]{11})"),
			Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})"),
			Pattern.compile("/embed/([A-Za-z0-9_-]{11})"),
			Pattern.compile("/live/([A-Za-z0-9_-]{11})"),
	};

	private YouTube() {}

	/** The YouTube video id (11 characters), or null. */
	@Nullable
	public static String videoId(@Nullable String url) {
		if (url == null || !url.contains("youtu")) {
			return null;
		}
		for (Pattern p : ID_PATTERNS) {
			Matcher m = p.matcher(url);
			if (m.find()) {
				return m.group(1);
			}
		}
		return null;
	}
}
