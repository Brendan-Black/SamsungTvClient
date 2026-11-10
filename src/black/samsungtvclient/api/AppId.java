package black.samsungtvclient.api;

import black.TypedString;
import java.util.regex.Pattern;

/**
 * An app's id on the TV, e.g. {@code 111299001912} or {@code org.tizen.browser}. The shape keeps it safe to put in a
 * URL path or a command on the TV.
 * Constructing one from anything else throws {@link IllegalArgumentException}.
 */
public record AppId(String value) implements TypedString {
	private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9._-]+");

	public AppId {
		if (!SHAPE.matcher(value).matches() || value.contains("..")) { throw new IllegalArgumentException("not an app id: " + value); }
	}

	@Override
	public String toString() {
		return value;
	}
}
