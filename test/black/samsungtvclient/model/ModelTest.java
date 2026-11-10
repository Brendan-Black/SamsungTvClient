package black.samsungtvclient.model;

import static black.samsungtvclient.Check.eq;

import java.time.Duration;
import java.util.Optional;

public final class ModelTest {
	private ModelTest() {}

	public static void run() {
		seconds(90, "1:30", "mm:ss");
		seconds(5400, "90:00", "minutes may exceed 59 when leading");
		seconds(3723, "1:02:03", "h:mm:ss");
		seconds(90, "0:01:30.500", "fractions are dropped");
		seconds(42, " 42 ", "plain seconds, trimmed");
		for (String bad : new String[] {"", "NOT_IMPLEMENTED", "NaN", "Infinity", "1e3", "+5", "-5",
			"1:60", "1:2:3:4", "1.5:00", "99999999999999999999"}) {
			eq(Optional.empty(), PositionInfo.parseTime(bad), "rejects " + bad);
		}
		eq("01:02:03", PositionInfo.toHms(3723), "toHms");
		eq("00:00:00", PositionInfo.toHms(-7), "toHms clamps negatives");
		eq(Optional.empty(), new PositionInfo("", "NOT_IMPLEMENTED", "").knownDuration(), "unknown duration");
		eq(Optional.of(Duration.ofSeconds(90)), new PositionInfo("", "", "0:01:30").knownPosition(), "known position");

		eq(TransportState.PLAYING, TransportState.parse(" PLAYING "), "known state");
		eq(TransportState.UNKNOWN, TransportState.parse("VENDOR_THING"), "vendor state");
		eq(TransportState.UNKNOWN, TransportState.parse(""), "missing state");
	}

	private static void seconds(long expected, String time, String what) {
		eq(Optional.of(Duration.ofSeconds(expected)), PositionInfo.parseTime(time), what);
	}
}
