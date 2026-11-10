package black.samsungtvclient.model;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AVTransport {@code GetPositionInfo} result. Times are as the TV reports them ({@code H+:MM:SS[.F]}, or
 * {@code NOT_IMPLEMENTED}), empty if it left one out; {@link #knownDuration()} and {@link #knownPosition()} read them.
 */
public record PositionInfo(String trackUri, String duration, String relTime) {
	// Up to three colon-separated fields; the first may be any size (e.g. 90:00), the rest are 0-59.
	private static final Pattern TIME = Pattern.compile("(\\d{1,9})(?::(\\d{1,2}))?(?::(\\d{1,2}))?(?:\\.\\d*)?");

	/** The track's length, if the TV gave a time. */
	public Optional<Duration> knownDuration() {
		return parseTime(duration);
	}

	/** The playback position, if the TV gave a time. */
	public Optional<Duration> knownPosition() {
		return parseTime(relTime);
	}

	/** Parses {@code H:MM:SS}, {@code MM:SS} or plain seconds (fractions dropped); empty if it's none of those. */
	public static Optional<Duration> parseTime(String time) {
		Matcher m = TIME.matcher(time.strip());
		if (!m.matches()) { return Optional.empty(); }
		long total = 0;
		for (int g = 1; g <= 3 && m.group(g) != null; g++) {
			long n = Long.parseLong(m.group(g));
			if (g > 1 && n >= 60) { return Optional.empty(); }
			total = total * 60 + n;
		}
		return Optional.of(Duration.ofSeconds(total));
	}

	/** Formats seconds as {@code HH:MM:SS}, the form AVTransport {@code Seek} expects for REL_TIME. */
	public static String toHms(long seconds) {
		long s = Math.max(0, seconds);
		return "%02d:%02d:%02d".formatted(s / 3600, s % 3600 / 60, s % 60);
	}
}
