package black.samsungtvclient.api;

import black.TypedString;

/**
 * What a TV hands a client it has paired with: anyone holding it can drive the TV. It's opaque, and its
 * {@code toString()} doesn't give it away, so it can't reach a log or an error message by accident.
 */
public record PairingToken(String value) implements TypedString {
	public PairingToken {
		if (value.isBlank()) { throw new IllegalArgumentException("a pairing token can't be blank"); }
	}

	@Override
	public String toString() {
		return "PairingToken[redacted]";
	}
}
