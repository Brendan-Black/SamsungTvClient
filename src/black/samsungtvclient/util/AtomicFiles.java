package black.samsungtvclient.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writing a file so that a failure part way leaves the old one intact. */
public final class AtomicFiles {
	private AtomicFiles() {}

	/**
	 * Replaces {@code file} with {@code content} in one step, creating its directory if needed. The file ends up readable
	 * only by its owner where the file system has permissions, since what's kept here is mostly tokens and keys.
	 */
	public static void write(Path file, byte[] content) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
		try {
			Files.write(tmp, content);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); // the closest this file system offers
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}
}
