package black.samsungtvclient.api;

import black.TypedString;
import java.util.regex.Pattern;

/**
 * One of Samsung's {@code KEY_...} codes. {@link Key} names the common ones; the TV knows more.
 * Constructing one from anything else throws {@link IllegalArgumentException}.
 */
public record KeyCode(String value) implements TypedString {
	private static final Pattern SHAPE = Pattern.compile("KEY_[A-Z0-9_]+");

	public KeyCode {
		if (!SHAPE.matcher(value).matches()) { throw new IllegalArgumentException("not a KEY_... code: " + value); }
	}

	@Override
	public String toString() {
		return value;
	}
}
