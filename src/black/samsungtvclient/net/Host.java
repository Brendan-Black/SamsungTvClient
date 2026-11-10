package black.samsungtvclient.net;

import black.TypedString;
import java.util.regex.Pattern;

/**
 * A host name or IP address, trimmed and, for IPv6, without brackets (given with or without them).
 * Constructing one from anything else throws {@link IllegalArgumentException}.
 */
public record Host(String value) implements TypedString {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9.-]+");
	private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

	public Host {
		String h = value.strip();
		if (h.startsWith("[") && h.endsWith("]")) { h = h.substring(1, h.length() - 1); }
		if (!(h.indexOf(':') >= 0 ? IPV6 : NAME).matcher(h).matches()) {
			throw new IllegalArgumentException("not a host name or IP address: " + value);
		}
		value = h;
	}

	/** As it goes in a URL's authority: IPv6 addresses get their brackets. */
	public String forUrl() {
		return value.indexOf(':') >= 0 ? "[" + value + "]" : value;
	}

	@Override
	public String toString() {
		return value;
	}
}
