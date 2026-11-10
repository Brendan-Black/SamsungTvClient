package black.samsungtvclient.model;

/** AVTransport {@code CurrentTransportState} values, plus {@link #UNKNOWN} for anything vendor-specific. */
public enum TransportState {
	STOPPED,
	PLAYING,
	TRANSITIONING,
	PAUSED_PLAYBACK,
	PAUSED_RECORDING,
	RECORDING,
	NO_MEDIA_PRESENT,
	UNKNOWN;

	public static TransportState parse(String value) {
		try {
			return valueOf(value.trim());
		} catch (IllegalArgumentException e) {
			return UNKNOWN; // vendors add their own, and "what the TV is doing" stays a fair question
		}
	}
}
