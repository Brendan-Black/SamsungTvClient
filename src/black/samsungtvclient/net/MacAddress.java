package black.samsungtvclient.net;

import black.samsungtvclient.util.TypedString;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * A MAC address: six hex bytes, with any of {@code :}, {@code -} or nothing between them.
 * Constructing one from anything else throws {@link IllegalArgumentException}.
 */
public record MacAddress(String value) implements TypedString {
	private static final Pattern SEPARATORS = Pattern.compile("[:-]");
	private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]{12}");

	public MacAddress {
		value = value.strip();
		if (!HEX.matcher(SEPARATORS.matcher(value).replaceAll("")).matches()) {
			throw new IllegalArgumentException("not a MAC address: " + value);
		}
	}

	byte[] bytes() {
		return HexFormat.of().parseHex(SEPARATORS.matcher(value).replaceAll(""));
	}

	@Override
	public String toString() {
		return value;
	}
}
