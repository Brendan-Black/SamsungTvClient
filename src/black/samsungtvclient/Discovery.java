package black.samsungtvclient;

import black.samsungtvclient.api.Generation;
import black.samsungtvclient.detect.GenerationDetector;
import black.samsungtvclient.model.RendererInfo;
import black.samsungtvclient.net.DeviceSearch;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.upnp.Renderer;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finds Samsung TVs on the LAN, of every generation: every one since 2011 is a UPnP MediaRenderer, so SSDP finds
 * them all, and a {@link GenerationDetector} then tells which are Samsung TVs, and which API each speaks.
 */
public final class Discovery {
	/** A TV found: its address, its API generation, and its name and model as its MediaRenderer gives them. */
	public record Found(Host host, Generation generation, String name, String model) {}

	private Discovery() {}

	/**
	 * Searches for {@code window} and asks {@code detector} about each renderer that answered.
	 *
	 * @param search {@link black.samsungtvclient.net.Ssdp#search} on a real network
	 */
	public static List<Found> discover(Duration window, DeviceSearch search, GenerationDetector detector) throws IOException {
		Map<Host, RendererInfo> byHost = new LinkedHashMap<>();
		for (RendererInfo r : Renderer.discover(window, search)) { byHost.putIfAbsent(r.ip(), r); }
		List<Found> found = new ArrayList<>();
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			Map<RendererInfo, Future<Optional<Generation>>> probes = new LinkedHashMap<>();
			for (RendererInfo r : byHost.values()) { probes.put(r, pool.submit(() -> detector.detect(r.ip()))); }
			for (Map.Entry<RendererInfo, Future<Optional<Generation>>> e : probes.entrySet()) {
				RendererInfo r = e.getKey();
				try {
					e.getValue().get().ifPresent(g -> found.add(new Found(r.ip(), g, r.name(), r.model())));
				} catch (ExecutionException x) {
					// A detector answers "not a TV" with empty, so this is a bug in it, not a device to skip.
					throw new IllegalStateException("detecting the generation of " + r.ip() + " failed", x.getCause());
				} catch (InterruptedException x) {
					Thread.currentThread().interrupt();
					throw new IOException("interrupted detecting TV generations", x);
				}
			}
		}
		return List.copyOf(found);
	}
}
