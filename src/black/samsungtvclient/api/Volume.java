package black.samsungtvclient.api;

import java.io.IOException;

/** The TV's volume (0-100) and mute. */
public interface Volume {
	int volume() throws IOException;

	/** @throws IllegalArgumentException if {@code level} isn't 0-100 */
	void setVolume(int level) throws IOException;

	boolean muted() throws IOException;

	void setMuted(boolean muted) throws IOException;
}
