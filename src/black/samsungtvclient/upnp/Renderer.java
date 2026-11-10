package black.samsungtvclient.upnp;

import black.samsungtvclient.api.Media;
import black.samsungtvclient.api.MediaPlayer;
import black.samsungtvclient.api.Volume;
import black.samsungtvclient.model.PlaybackStatus;
import black.samsungtvclient.model.PositionInfo;
import black.samsungtvclient.model.RendererInfo;
import black.samsungtvclient.model.TransportState;
import black.samsungtvclient.net.DeviceSearch;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.net.Http;
import black.samsungtvclient.net.SoapFault;
import black.samsungtvclient.net.Ssdp;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A connected MediaRenderer (UPnP AVTransport + RenderingControl): push a video, then play, pause, seek, volume. */
public final class Renderer implements MediaPlayer, Volume {
	/** Where Samsung TVs serve their MediaRenderer description. */
	public static final int DEFAULT_PORT = 9197;

	private static final String DESCRIPTION_PATH = "/dmr";

	private final Device device;
	private final AvTransport av;
	private final RenderingControl rendering;

	private Renderer(Device device) throws IOException {
		this.device = device;
		this.av = new AvTransport(device.requireService(AvTransport.NAME));
		this.rendering = new RenderingControl(device.requireService(RenderingControl.NAME));
	}

	/** The MediaRenderer description URL of a Samsung TV at {@code host:port}. */
	public static URI descriptionUrl(Host host, int port) {
		return URI.create("http://" + host.forUrl() + ":" + port + DESCRIPTION_PATH);
	}

	/** Fetches the description at {@code descriptionUrl} and binds to its AVTransport + RenderingControl. */
	public static Renderer connect(URI descriptionUrl) throws IOException {
		Device device;
		try {
			device = Device.fetch(descriptionUrl);
		} catch (IOException e) {
			throw new IOException("can't reach TV at " + descriptionUrl + ": " + Http.describe(e), e);
		}
		return new Renderer(device);
	}

	/**
	 * Discovers MediaRenderers for {@code window} and connects to the only one.
	 *
	 * @throws IOException if none answers, or more than one does (the message names them)
	 */
	public static Renderer find(Duration window, DeviceSearch search) throws IOException {
		List<RendererInfo> found = discover(window, search);
		if (found.isEmpty()) {
			throw new IOException("no TV answered discovery; is this PC on the TV's network? (or give --ip)");
		}
		if (found.size() > 1) {
			List<String> names = found.stream().map(r -> r.name() + " at " + r.ip()).toList();
			throw new IOException("found " + found.size() + " TVs (" + String.join(", ", names) + "); choose one with --ip");
		}
		return connect(found.getFirst().descriptionUrl());
	}

	/**
	 * Scans the LAN for {@code window} and returns every MediaRenderer whose description loads, once each (a device
	 * reachable over several adapters answers on each).
	 *
	 * @param search {@link Ssdp#search} on a real network
	 */
	public static List<RendererInfo> discover(Duration window, DeviceSearch search) throws IOException {
		List<URI> locations = search.search(Ssdp.MEDIA_RENDERER, window);
		Map<String, RendererInfo> found = new LinkedHashMap<>(); // by UDN, else description URL
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Device>> fetches = new ArrayList<>();
			for (URI location : locations) { fetches.add(pool.submit(() -> Device.fetch(location))); }
			for (Future<Device> f : fetches) {
				try {
					Device d = f.get();
					if (d.isMediaRenderer()) { found.putIfAbsent(d.udn().orElse(d.descriptionUrl().toString()), d.info()); }
				} catch (ExecutionException e) {
					// It answered SSDP but its description didn't load: leave it out. Anything else is a bug here.
					if (!(e.getCause() instanceof IOException)) { throw new IllegalStateException("reading a discovered device failed", e.getCause()); }
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("interrupted reading discovered devices", e);
				}
			}
		}
		return List.copyOf(found.values());
	}

	/** Loads {@code media} on the TV and starts playback; what it doesn't say is derived from its URL. */
	@Override
	public void cast(Media media) throws IOException {
		String href = media.url().value();
		String type = media.knownMime().orElseGet(() -> Didl.guessMime(href));
		String name = media.knownTitle().orElseGet(() -> Didl.titleFrom(href));
		av.setUri(href, media.knownSubtitles()
			.map(s -> Didl.forMedia(href, name, type, s.value()))
			.orElseGet(() -> Didl.forMedia(href, name, type)));
		try {
			av.play();
		} catch (SoapFault f) {
			// The UN75NU8000 starts loading on SetAVTransportURI by itself; Play then fails with 701 even
			// though the media is on its way. Only a TV that isn't loading or playing has really refused.
			TransportState state = av.getTransportInfo();
			if (f.code() != SoapFault.TRANSITION_NOT_AVAILABLE || (state != TransportState.TRANSITIONING && state != TransportState.PLAYING)) { throw f; }
		}
	}

	@Override
	public void play() throws IOException {
		av.play();
	}

	@Override
	public void pause() throws IOException {
		av.pause();
	}

	@Override
	public void stop() throws IOException {
		av.stop();
	}

	@Override
	public void seek(Duration position) throws IOException {
		if (position.isNegative()) { throw new IllegalArgumentException("can't seek to before the start: " + position); }
		av.seek(PositionInfo.toHms(position.toSeconds()));
	}

	/** What the TV is doing right now; cheaper than {@link #status()}, for waiting on a change. */
	public TransportState transportState() throws IOException {
		return av.getTransportInfo();
	}

	@Override
	public PlaybackStatus status() throws IOException {
		return new PlaybackStatus(av.getTransportInfo(), av.getPositionInfo(), rendering.getVolume(), rendering.getMute());
	}

	@Override
	public int volume() throws IOException {
		return rendering.getVolume();
	}

	@Override
	public void setVolume(int level) throws IOException {
		rendering.setVolume(level);
	}

	@Override
	public boolean muted() throws IOException {
		return rendering.getMute();
	}

	@Override
	public void setMuted(boolean muted) throws IOException {
		rendering.setMute(muted);
	}

	public RendererInfo info() {
		return device.info();
	}
}
