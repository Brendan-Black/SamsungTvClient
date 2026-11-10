package black.samsungtvclient.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Just enough JSON for the REST API. Writes maps, collections, strings, numbers, booleans, enums and
 * null; parses any JSON text into {@code Map}/{@code List}/{@code String}/{@code Long}/{@code Double}/
 * {@code Boolean}/null.
 */
public final class Json {
	private Json() {}

	public static String write(@Nullable Object value) {
		StringBuilder sb = new StringBuilder();
		write(sb, value);
		return sb.toString();
	}

	/** The field {@code key} of a parsed object as a string; empty if it is absent or JSON null. */
	public static Optional<String> string(Map<?, ?> object, String key) {
		return Optional.ofNullable(object.get(key)).map(String::valueOf);
	}

	/**
	 * Parses any JSON text; the result is null for the JSON value {@code null}.
	 *
	 * @throws IllegalArgumentException if {@code text} isn't valid JSON
	 */
	public static @Nullable Object parse(String text) {
		Parser p = new Parser(text);
		p.skipWhitespace();
		@Nullable
		Object value = p.value();
		p.skipWhitespace();
		if (p.i != text.length()) { throw p.error("unexpected trailing characters"); }
		return value;
	}

	private static void write(StringBuilder sb, @Nullable Object value) {
		switch (value) {
			case null -> sb.append("null");
			case String s -> quote(sb, s);
			case Boolean b -> sb.append(b);
			case Double d when d.isNaN() || d.isInfinite() -> sb.append("null");
			case Number n -> sb.append(n);
			case Enum<?> e -> quote(sb, e.name());
			case Map<?, ?> m -> {
				sb.append('{');
				boolean first = true;
				for (Map.Entry<?, ?> e : m.entrySet()) {
					if (!first) { sb.append(','); }
					first = false;
					quote(sb, String.valueOf(e.getKey()));
					sb.append(':');
					write(sb, e.getValue());
				}
				sb.append('}');
			}
			case Collection<?> c -> {
				sb.append('[');
				boolean first = true;
				for (Object item : c) {
					if (!first) { sb.append(','); }
					first = false;
					write(sb, item);
				}
				sb.append(']');
			}
			default -> quote(sb, value.toString());
		}
	}

	private static void quote(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append("\\u%04x".formatted((int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}

	private static final class Parser {
		private static final int MAX_DEPTH = 32; // the API's bodies are flat; this stops stack exhaustion

		private final String s;
		private int i;
		private int depth;

		Parser(String s) {
			this.s = s;
		}

		@Nullable
		Object value() {
			if (i >= s.length()) { throw error("unexpected end of input"); }
			return switch (s.charAt(i)) {
				case '{' -> nested(this::object);
				case '[' -> nested(this::array);
				case '"' -> string();
				case 't' -> literal("true", Boolean.TRUE);
				case 'f' -> literal("false", Boolean.FALSE);
				case 'n' -> literal("null", null);
				default -> number();
			};
		}

		private Object nested(Supplier<Object> container) {
			if (++depth > MAX_DEPTH) { throw error("nested more than " + MAX_DEPTH + " levels deep"); }
			try {
				return container.get();
			} finally {
				depth--;
			}
		}

		private Map<String, @Nullable Object> object() {
			Map<String, @Nullable Object> map = new LinkedHashMap<>();
			i++;
			skipWhitespace();
			if (consume('}')) { return map; }
			do {
				skipWhitespace();
				if (i >= s.length() || s.charAt(i) != '"') { throw error("expected a string key"); }
				String key = string();
				skipWhitespace();
				expect(':');
				skipWhitespace();
				map.put(key, value());
				skipWhitespace();
			} while (consume(','));
			expect('}');
			return map;
		}

		private List<@Nullable Object> array() {
			List<@Nullable Object> list = new ArrayList<>();
			i++;
			skipWhitespace();
			if (consume(']')) { return list; }
			do {
				skipWhitespace();
				list.add(value());
				skipWhitespace();
			} while (consume(','));
			expect(']');
			return list;
		}

		private String string() {
			i++; // opening quote
			StringBuilder sb = new StringBuilder();
			while (true) {
				if (i >= s.length()) { throw error("unterminated string"); }
				char c = s.charAt(i++);
				if (c == '"') { return sb.toString(); }
				if (c != '\\') {
					sb.append(c);
					continue;
				}
				if (i >= s.length()) { throw error("unterminated escape"); }
				char e = s.charAt(i++);
				switch (e) {
					case '"', '\\', '/' -> sb.append(e);
					case 'b' -> sb.append('\b');
					case 'f' -> sb.append('\f');
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case 'u' -> {
						if (i + 4 > s.length()) { throw error("truncated \\u escape"); }
						try {
							sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
						} catch (NumberFormatException ex) {
							throw error("bad \\u escape");
						}
						i += 4;
					}
					default -> throw error("bad escape \\" + e);
				}
			}
		}

		private Object number() {
			int start = i;
			while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) { i++; }
			String n = s.substring(start, i);
			if (n.isEmpty()) { throw error("unexpected character '" + s.charAt(start) + "'"); }
			try {
				if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
					try {
						return Long.parseLong(n);
					} catch (NumberFormatException overflow) {
						// too big for a long; fall through to double
					}
				}
				return Double.parseDouble(n);
			} catch (NumberFormatException e) {
				throw error("bad number " + n);
			}
		}

		private @Nullable Object literal(String word, @Nullable Object value) {
			if (!s.startsWith(word, i)) { throw error("unexpected token"); }
			i += word.length();
			return value;
		}

		void skipWhitespace() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) { i++; }
		}

		private boolean consume(char c) {
			if (i < s.length() && s.charAt(i) == c) {
				i++;
				return true;
			}
			return false;
		}

		private void expect(char c) {
			if (!consume(c)) { throw error("expected '" + c + "'"); }
		}

		IllegalArgumentException error(String message) {
			return new IllegalArgumentException("invalid JSON at offset " + i + ": " + message);
		}
	}
}
