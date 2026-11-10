package black.samsungtvclient.api;

import black.samsungtvclient.model.PlaybackStatus;
import java.io.IOException;
import java.time.Duration;

/**
 * Playing a URL on the TV (UPnP AVTransport, which every generation has). The TV's own remote can't pause or skip
 * what it's given this way.
 */
public interface MediaPlayer {
	/** Loads {@code media} and starts playing it. */
	void cast(Media media) throws IOException;

	void play() throws IOException;

	void pause() throws IOException;

	void stop() throws IOException;

	/**
	 * Seeks to {@code position} from the start.
	 *
	 * @throws IllegalArgumentException if {@code position} is negative
	 */
	void seek(Duration position) throws IOException;

	PlaybackStatus status() throws IOException;
}
