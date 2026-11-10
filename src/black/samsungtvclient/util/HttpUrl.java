package black.samsungtvclient.util;

import black.TypedString;

/**
 * An absolute http(s) URL, percent-encoded as browsers encode what people paste.
 * Constructing one from anything else throws {@link IllegalArgumentException}.
 */
public record HttpUrl(String value) implements TypedString {
	public HttpUrl {
		value = Urls.http(value).toString();
	}

	@Override
	public String toString() {
		return value;
	}
}
