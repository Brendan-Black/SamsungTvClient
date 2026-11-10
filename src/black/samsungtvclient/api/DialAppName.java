package black.samsungtvclient.api;

import black.TypedString;
import java.util.regex.Pattern;

/**
 * An app's name in DIAL, case-sensitive: e.g. {@code YouTube}, {@code Netflix}. The shape keeps it safe in a URL path.
 * Constructing one from anything else throws {@link IllegalArgumentException}.
 */
public record DialAppName(String value) implements TypedString {
	private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9._-]+");

	public DialAppName {
		if (!SHAPE.matcher(value).matches() || value.contains("..")) { throw new IllegalArgumentException("not a DIAL app name: " + value); }
	}

	@Override
	public String toString() {
		return value;
	}
}
