package black.samsungtvclient.samsung;

import black.samsungtvclient.api.Generation;
import black.samsungtvclient.api.SamsungTv;
import black.samsungtvclient.api.TvConnector;
import black.samsungtvclient.api.TvStore;
import black.samsungtvclient.net.Host;

/** Connects to Tizen TVs: {@link Generation#TIZEN_TOKEN} (2017 on) or {@link Generation#TIZEN_OPEN} (2016). */
public record TizenConnector(Generation generation) implements TvConnector {
	public TizenConnector {
		if (generation != Generation.TIZEN_TOKEN && generation != Generation.TIZEN_OPEN) {
			throw new IllegalArgumentException("not a Tizen generation: " + generation);
		}
	}

	@Override
	public SamsungTv connect(Host host, String clientName, TvStore store) {
		return new TizenTv(host, generation, clientName, store, TizenTv.Endpoints.of(host, generation));
	}
}
