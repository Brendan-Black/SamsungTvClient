package black.samsungtvclient.api;

import black.samsungtvclient.util.TypedString;
import java.util.regex.Pattern;

/**
 * A web app's package id: 10 letters or digits, the part of its {@link AppId} before the dot.
 * Constructing one from anything else throws {@link IllegalArgumentException}.
 */
public record PackageId(String value) implements TypedString {
	private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9]{10}");

	public PackageId {
		if (!SHAPE.matcher(value).matches()) { throw new IllegalArgumentException("not a package id (10 letters or digits): " + value); }
	}

	@Override
	public String toString() {
		return value;
	}
}
