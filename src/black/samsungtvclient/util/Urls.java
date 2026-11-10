package black.samsungtvclient.util;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Accepting URLs that people paste. */
public final class Urls {
	private Urls() {}

	/**
	 * Parses an absolute http(s) URL, first percent-encoding what can't appear in a URI, as browsers do.
	 *
	 * @throws IllegalArgumentException if it isn't an absolute http(s) URL
	 */
	public static URI http(String url) {
		URI uri;
		try {
			uri = URI.create(encodeIllegal(url.strip()));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("not a valid URL: " + url, e);
		}
		String scheme = uri.getScheme();
		if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
			throw new IllegalArgumentException("not an http(s) URL: " + url);
		}
		return uri;
	}

	/**
	 * Percent-encodes characters that can't appear in a URI (spaces, {@code <>"{}|\^`}, non-ASCII), as
	 * browsers do with pasted links. Existing {@code %XX} escapes and reserved characters are left alone.
	 */
	public static String encodeIllegal(String url) {
		StringBuilder sb = new StringBuilder(url.length());
		for (byte b : url.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xFF;
			if (c <= 0x20 || c >= 0x7F || "<>\"{}|\\^`".indexOf(c) >= 0) {
				sb.append("%%%02X".formatted(c));
			} else {
				sb.append((char) c);
			}
		}
		return sb.toString();
	}
}
