package black.samsungtvclient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Just enough of a test framework: named checks, a pass/fail tally, temporary folders, and a summary. No dependencies. */
public final class Check {
	/** A check body that may throw anything. */
	@FunctionalInterface
	public interface Body {
		void run() throws Exception;
	}

	private static int passed;
	private static final List<String> failures = new ArrayList<>();
	private static final List<Path> tempDirs = new ArrayList<>();

	private Check() {}

	public static void eq(Object expected, Object actual, String what) {
		if (Objects.equals(expected, actual)) {
			passed++;
		} else {
			fail(what + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	public static void ok(boolean condition, String what) {
		if (condition) {
			passed++;
		} else {
			fail(what);
		}
	}

	/**
	 * Passes if {@code body} throws a {@code type}, and returns the exception to check further. If it doesn't, the group
	 * stops here: whatever came next was checking an exception that doesn't exist.
	 */
	public static <T extends Throwable> T fails(Class<T> type, Body body, String what) {
		try {
			body.run();
		} catch (Throwable t) {
			if (type.isInstance(t)) {
				passed++;
				return type.cast(t);
			}
			throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + t, t);
		}
		throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
	}

	/** A new empty folder, removed when the run ends. */
	public static synchronized Path tempDir(String name) throws IOException {
		Path dir = Files.createTempDirectory("samsungtvclient-" + name);
		tempDirs.add(dir);
		return dir;
	}

	/** Runs one group of checks; an exception escaping the group counts as a failure. */
	public static void group(String name, Body body) {
		System.out.println(name);
		try {
			body.run();
		} catch (Throwable t) {
			fail(name + " threw " + t);
			t.printStackTrace(System.out);
		}
	}

	/** Removes the temporary folders, prints the tally and returns a process exit code: 0 if nothing failed. */
	public static int summary() {
		for (Path dir : tempDirs) {
			try (Stream<Path> files = Files.walk(dir)) {
				for (Path p : files.sorted(Comparator.reverseOrder()).toList()) { Files.delete(p); }
			} catch (IOException e) {
				fail("couldn't clean up " + dir + ": " + e);
			}
		}
		System.out.printf("%n%d passed, %d failed%n", passed, failures.size());
		return failures.isEmpty() ? 0 : 1;
	}

	private static void fail(String message) {
		failures.add(message);
		System.out.println("  FAIL " + message);
	}
}
