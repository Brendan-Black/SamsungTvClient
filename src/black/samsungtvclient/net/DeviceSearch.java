package black.samsungtvclient.net;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Asking the network which devices are there. {@link Ssdp#search} is the real one; tests stand in their own list. */
@FunctionalInterface
public interface DeviceSearch {
	/**
	 * Searches for {@code window}.
	 *
	 * @return distinct description URLs, in the order they answered
	 */
	List<URI> search(String searchTarget, Duration window) throws IOException;
}
