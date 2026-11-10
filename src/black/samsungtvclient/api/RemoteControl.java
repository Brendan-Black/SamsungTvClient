package black.samsungtvclient.api;

import java.io.IOException;
import java.time.Duration;

/** The remote's keys, by Samsung's {@code KEY_...} codes (see {@link Key}), which every generation shares. */
public interface RemoteControl {
	/** Presses and releases a key. */
	void key(KeyCode code) throws IOException;

	/** Holds a key down for {@code duration}. */
	void hold(KeyCode code, Duration duration) throws IOException;
}
