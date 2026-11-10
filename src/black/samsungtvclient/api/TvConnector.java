package black.samsungtvclient.api;

import black.samsungtvclient.net.Host;
import java.io.IOException;

/** Makes {@link SamsungTv}s of one {@link Generation}: the entry point of a generation's implementation. */
public interface TvConnector {
	Generation generation();

	/**
	 * The TV at {@code host}. Connections open when a capability is first used.
	 *
	 * @param clientName how the TV lists this client among its connected devices
	 * @param store      where pairings (tokens, certificates, MAC addresses) are kept
	 */
	SamsungTv connect(Host host, String clientName, TvStore store) throws IOException;
}
