package black.samsungtvclient.samsung;

import black.samsungtvclient.api.About;
import black.samsungtvclient.api.App;
import black.samsungtvclient.api.AppId;
import black.samsungtvclient.api.AppStatus;
import black.samsungtvclient.api.Apps;
import black.samsungtvclient.api.Capability;
import black.samsungtvclient.api.Developer;
import black.samsungtvclient.api.DialApps;
import black.samsungtvclient.api.Duid;
import black.samsungtvclient.api.Events;
import black.samsungtvclient.api.Generation;
import black.samsungtvclient.api.LaunchType;
import black.samsungtvclient.api.MediaPlayer;
import black.samsungtvclient.api.PackageId;
import black.samsungtvclient.api.Pairing;
import black.samsungtvclient.api.Pointer;
import black.samsungtvclient.api.RemoteControl;
import black.samsungtvclient.api.SamsungTv;
import black.samsungtvclient.api.TextInput;
import black.samsungtvclient.api.TvInfo;
import black.samsungtvclient.api.TvStore;
import black.samsungtvclient.api.Volume;
import black.samsungtvclient.dial.Dial;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.net.MacAddress;
import black.samsungtvclient.sdb.Sdb;
import black.samsungtvclient.tizen.AppInstaller;
import black.samsungtvclient.upnp.Renderer;
import black.samsungtvclient.util.HttpUrl;
import black.samsungtvclient.util.Nullable;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A Tizen TV ({@link Generation#TIZEN_TOKEN}, 2017 on, or {@link Generation#TIZEN_OPEN}, 2016), through:
 * <ul>
 * <li>the REST API on port 8001 ({@link SmartApi}): device info; one app's status, launch, close; no pairing</li>
 * <li>the remote channel ({@link RemoteChannel}): keys, text, pointer, app list, launch with a URL, events; a TLS
 * WebSocket on 8002 with a token since 2017, a plain one on 8001 without in 2016</li>
 * <li>the UPnP MediaRenderer on port 9197 ({@link Renderer}): play a URL, volume</li>
 * <li>DIAL on port 8080 ({@link Dial}): YouTube, Netflix</li>
 * <li>the developer connection on port 26101 ({@link Sdb}): install your own apps</li>
 * </ul>
 * Connections open when first used; {@link #close()} closes the remote channel.
 */
public final class TizenTv implements SamsungTv {
	private static final Set<Capability> CAPABILITIES = EnumSet.allOf(Capability.class);

	/** Where each of the TV's interfaces is; tests point them at pseudos. */
	record Endpoints(URI rest, URI channel, URI dial, URI renderer, int developerPort) {
		static Endpoints of(Host host, Generation generation) {
			String h = host.forUrl();
			URI channel = generation == Generation.TIZEN_OPEN
				? URI.create("ws://" + h + ":" + SmartApi.PORT + RemoteChannel.PATH)
				: URI.create("wss://" + h + ":" + RemoteChannel.PORT + RemoteChannel.PATH);
			return new Endpoints(SmartApi.baseUri(host, SmartApi.PORT), channel, Dial.baseUri(host),
				Renderer.descriptionUrl(host, Renderer.DEFAULT_PORT), Sdb.PORT);
		}
	}

	private final Host host;
	private final Generation generation;
	private final String clientName;
	private final TvStore store;
	private final Endpoints endpoints;
	private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
	private @Nullable RemoteChannel channel; // guarded by this
	private @Nullable Renderer renderer; // guarded by this

	TizenTv(Host host, Generation generation, String clientName, TvStore store, Endpoints endpoints) {
		this.host = host;
		this.generation = generation;
		this.clientName = clientName;
		this.store = store;
		this.endpoints = endpoints;
	}

	@Override
	public Host host() {
		return host;
	}

	@Override
	public Generation generation() {
		return generation;
	}

	@Override
	public Set<Capability> capabilities() {
		return CAPABILITIES;
	}

	private SmartApi api() {
		return new SmartApi(endpoints.rest());
	}

	/** The remote channel, connecting (and pairing, the first time) if needed. */
	private synchronized RemoteChannel channel() throws IOException {
		RemoteChannel c = channel;
		if (c == null) {
			c = RemoteChannel.connect(endpoints.channel(), host, clientName, store, e -> listeners.forEach(l -> l.accept(e.json())));
			channel = c;
		}
		return c;
	}

	private synchronized Renderer renderer() throws IOException {
		Renderer r = renderer;
		if (r == null) {
			r = Renderer.connect(endpoints.renderer());
			renderer = r;
		}
		return r;
	}

	/** Also remembers the TV's MAC address, so it can be woken while it's off. */
	@Override
	public About about() {
		return () -> {
			TvInfo info = api().info();
			Optional<MacAddress> mac = info.knownMac();
			if (mac.isPresent() && !store.mac(host).equals(mac)) { store.setMac(host, mac.get()); }
			return info;
		};
	}

	/**
	 * Since 2017 a pairing is a token the TV hands out on the first Allow. In 2016 there's no token: the TV
	 * remembers the clients it allowed, and {@link Pairing#paired()} can't tell.
	 */
	@Override
	public Pairing pairing() {
		return new Pairing() {
			@Override
			public boolean paired() throws IOException {
				return store.token(host).isPresent();
			}

			@Override
			public void pair() throws IOException {
				channel();
			}

			@Override
			public void unpair() throws IOException {
				store.forget(host);
			}
		};
	}

	@Override
	public RemoteControl remote() throws IOException {
		return channel();
	}

	@Override
	public TextInput textInput() throws IOException {
		return channel();
	}

	@Override
	public Pointer pointer() throws IOException {
		return channel();
	}

	@Override
	public Events events() {
		return listener -> {
			listeners.add(listener);
			channel();
		};
	}

	@Override
	public Apps apps() {
		return new Apps() {
			@Override
			public List<App> installed() throws IOException {
				return channel().apps();
			}

			@Override
			public void launch(AppId appId, LaunchType type) throws IOException {
				channel().launch(appId, type);
			}

			@Override
			public void launch(AppId appId, LaunchType type, String metaTag) throws IOException {
				channel().launch(appId, type, metaTag);
			}

			@Override
			public void browse(HttpUrl url) throws IOException {
				channel().browse(url);
			}

			@Override
			public Optional<AppStatus> status(AppId appId) throws IOException {
				return api().appStatus(appId);
			}

			@Override
			public void close(AppId appId) throws IOException {
				api().close(appId);
			}
		};
	}

	@Override
	public DialApps dial() {
		return new Dial(endpoints.dial());
	}

	@Override
	public MediaPlayer media() throws IOException {
		return renderer();
	}

	@Override
	public Volume volume() throws IOException {
		return renderer();
	}

	@Override
	public Developer developer() {
		return () -> new SdbSession(Sdb.connect(host, endpoints.developerPort()));
	}

	@Override
	public synchronized void close() {
		RemoteChannel c = channel;
		if (c != null) { c.close(); }
		channel = null;
	}

	/** The developer connection: SDB, with Samsung's own install commands. */
	private record SdbSession(Sdb sdb) implements Developer.Session {
		@Override
		public Duid duid() throws IOException {
			String said = sdb.shell("0 getduid", Duration.ofSeconds(5)).strip();
			try {
				return new Duid(said);
			} catch (IllegalArgumentException e) {
				throw new IOException("the TV didn't give its DUID (it said: " + said + ")", e);
			}
		}

		@Override
		public Map<String, String> capabilities() throws IOException {
			return sdb.capabilities();
		}

		@Override
		public AppId install(byte[] signedPackage) throws IOException {
			AppInstaller.install(sdb, sdb.capabilities(), signedPackage);
			return AppInstaller.ids(signedPackage).appId();
		}

		@Override
		public void open(AppId appId) throws IOException {
			AppInstaller.open(sdb, appId);
		}

		@Override
		public void uninstall(PackageId packageId) throws IOException {
			AppInstaller.uninstall(sdb, packageId);
		}

		@Override
		public void close() throws IOException {
			sdb.close();
		}
	}
}
