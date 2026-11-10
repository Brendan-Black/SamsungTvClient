package black.samsungtvclient;

import black.samsungtvclient.api.Generation;
import black.samsungtvclient.api.SamsungTv;
import black.samsungtvclient.api.TvConnector;
import black.samsungtvclient.api.TvStore;
import black.samsungtvclient.api.UnsupportedGenerationException;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.samsung.TizenConnector;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link TvConnector} for each {@link Generation} that has one. {@link #builtIn()} has this library's;
 * an implementation of another generation joins them with {@link #with(TvConnector)}.
 */
public final class Connectors {
	private final Map<Generation, TvConnector> byGeneration;

	private Connectors(Map<Generation, TvConnector> byGeneration) {
		this.byGeneration = byGeneration;
	}

	/** This library's connectors: Tizen, 2016 on. */
	public static Connectors builtIn() {
		return of(List.of(new TizenConnector(Generation.TIZEN_TOKEN), new TizenConnector(Generation.TIZEN_OPEN)));
	}

	/** Exactly these connectors, at most one per generation. */
	public static Connectors of(List<TvConnector> connectors) {
		Map<Generation, TvConnector> map = new EnumMap<>(Generation.class);
		for (TvConnector c : connectors) {
			if (map.putIfAbsent(c.generation(), c) != null) { throw new IllegalArgumentException("two connectors for " + c.generation()); }
		}
		return new Connectors(map);
	}

	/** These connectors, with {@code connector} in place of any other for its generation. */
	public Connectors with(TvConnector connector) {
		Map<Generation, TvConnector> map = new EnumMap<>(byGeneration);
		map.put(connector.generation(), connector);
		return new Connectors(map);
	}

	public Optional<TvConnector> forGeneration(Generation generation) {
		return Optional.ofNullable(byGeneration.get(generation));
	}

	/**
	 * The TV at {@code host}, through its generation's connector.
	 *
	 * @throws UnsupportedGenerationException if there's no connector for {@code generation}
	 */
	public SamsungTv connect(Host host, Generation generation, String clientName, TvStore store) throws IOException {
		TvConnector c = forGeneration(generation).orElseThrow(() -> new UnsupportedGenerationException(generation));
		return c.connect(host, clientName, store);
	}
}
