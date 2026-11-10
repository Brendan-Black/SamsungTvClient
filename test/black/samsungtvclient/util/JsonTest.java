package black.samsungtvclient.util;

import static black.samsungtvclient.Check.eq;
import static black.samsungtvclient.Check.fails;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonTest {
	private JsonTest() {}

	public static void run() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("s", "a\"b\\c\n\u0001é");
		m.put("n", 3);
		m.put("d", 1.5);
		m.put("nan", Double.NaN);
		m.put("b", true);
		m.put("z", null);
		m.put("l", List.of(1, "x"));
		String text = Json.write(m);
		eq("{\"s\":\"a\\\"b\\\\c\\n\\u0001é\",\"n\":3,\"d\":1.5,\"nan\":null,\"b\":true,\"z\":null,\"l\":[1,\"x\"]}",
			text, "write");

		Object back = Json.parse(text);
		eq("a\"b\\c\n\u0001é", ((Map<?, ?>) back).get("s"), "string round-trip");
		eq(3L, ((Map<?, ?>) back).get("n"), "integers parse as Long");
		eq(1.5, ((Map<?, ?>) back).get("d"), "decimals parse as Double");
		eq(List.of(1L, "x"), ((Map<?, ?>) back).get("l"), "arrays");
		eq("é/", Json.parse("\"\\u00e9\\/\""), "\\u and \\/ escapes");
		eq(Map.of(), Json.parse(" { } "), "empty object with whitespace");

		for (String bad : new String[] {"", "{", "{\"a\" 1}", "[1,]", "tru", "\"\\x\"", "\"\\u12\"", "1 2", "{1:2}"}) {
			fails(IllegalArgumentException.class, () -> Json.parse(bad), "rejects " + bad);
		}
		char[] deep = new char[5000];
		Arrays.fill(deep, '[');
		fails(IllegalArgumentException.class, () -> Json.parse(new String(deep)), "depth limit, not a stack overflow");
		Json.parse("[".repeat(32) + "]".repeat(32)); // exactly at the limit is fine
	}
}
