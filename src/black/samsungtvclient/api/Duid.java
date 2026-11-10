package black.samsungtvclient.api;

import black.samsungtvclient.util.TypedString;
import java.util.regex.Pattern;

/**
 * A TV's unique id, which a distributor certificate names.
 * Constructing one from anything else throws {@link IllegalArgumentException}.
 */
public record Duid(String value) implements TypedString {
	private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9]{8,}");

	public Duid {
		if (!SHAPE.matcher(value).matches()) { throw new IllegalArgumentException("not a DUID: " + value); }
	}

	@Override
	public String toString() {
		return value;
	}
}
