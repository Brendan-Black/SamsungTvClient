package black.samsungtvclient.api;

import java.io.IOException;
import java.util.function.Consumer;

/** What the TV reports on its own (the on-screen keyboard opening, an app starting, ...), as it arrives. */
public interface Events {
	/** Sends each event, as the TV's JSON, to {@code listener} until the TV is closed. */
	void listen(Consumer<String> listener) throws IOException;
}
