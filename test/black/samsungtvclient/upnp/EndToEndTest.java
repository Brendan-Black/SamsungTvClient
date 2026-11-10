package black.samsungtvclient.upnp;

import static black.samsungtvclient.Check.eq;
import static black.samsungtvclient.Check.fails;
import static black.samsungtvclient.Check.ok;

import black.samsungtvclient.Discovery;
import black.samsungtvclient.api.Generation;
import black.samsungtvclient.api.Media;
import black.samsungtvclient.model.PlaybackStatus;
import black.samsungtvclient.model.RendererInfo;
import black.samsungtvclient.model.TransportState;
import black.samsungtvclient.net.DeviceSearch;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.net.SoapClient;
import black.samsungtvclient.net.SoapFault;
import black.samsungtvclient.util.HttpUrl;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Drives a real {@code test/PseudoTv.java} process through the renderer library. */
public final class EndToEndTest {
	static final String NAME = "[TV] PseudoTv";

	private static final Duration NO_WAIT = Duration.ZERO;

	private EndToEndTest() {}

	public static void run() throws Exception {
		try (PseudoTvProcess tv = new PseudoTvProcess("--no-ssdp")) {
			library(tv.description());
			discovery(tv.description());
		}
	}

	/** The metadata the TV holds for its track (PseudoTv echoes what SetAVTransportURI sent). */
	private static String trackMetaData(URI desc) throws IOException {
		var av = Device.fetch(desc).requireService(AvTransport.NAME);
		return SoapClient.invoke(av.controlUrl(), av.serviceType(), "GetPositionInfo", Map.of("InstanceID", "0")).getOrDefault("TrackMetaData", "");
	}

	private static void library(URI desc) throws Exception {
		Renderer r = Renderer.connect(desc);
		eq(NAME, r.info().name(), "connect reads the description");
		fails(SoapFault.class, r::pause, "pausing with nothing loaded is UPnP fault 701");

		r.cast(Media.of(new HttpUrl("http://example.com/My Clip.mp4")));
		eq(TransportState.PLAYING, awaitState(r, TransportState.PLAYING), "cast buffers, then plays");
		PlaybackStatus s = r.status();
		eq("http://example.com/My%20Clip.mp4", s.position().trackUri(), "the TV got the encoded URL");
		eq(Optional.of(Duration.ofSeconds(600)), s.position().knownDuration(), "duration");

		r.seek(Duration.ofMinutes(5));
		ok(r.status().position().knownPosition().orElseThrow().toSeconds() >= 300, "seek");
		fails(IllegalArgumentException.class, () -> r.seek(Duration.ofSeconds(-1)), "seeking before the start");
		r.pause();
		eq(TransportState.PAUSED_PLAYBACK, r.transportState(), "pause");
		r.setVolume(33);
		eq(33, r.volume(), "volume");
		r.setMuted(true);
		eq(true, r.muted(), "mute");
		r.setMuted(false);
		fails(IllegalArgumentException.class, () -> r.setVolume(101), "volume over 100");

		r.cast(Media.of(new HttpUrl("http://example.com/a.mkv")).subtitled(new HttpUrl("http://example.com/a b.srt")));
		eq(TransportState.PLAYING, awaitState(r, TransportState.PLAYING), "cast with subtitles plays");
		String sent = trackMetaData(desc);
		ok(sent.contains("<sec:CaptionInfoEx sec:type=\"srt\">http://example.com/a%20b.srt</sec:CaptionInfoEx>")
			&& sent.contains("xmlns:sec=\"http://www.sec.co.kr/\""), "the TV got the subtitles in Samsung's DIDL-Lite: " + sent);
		r.cast(Media.of(new HttpUrl("http://example.com/a.mkv")));
		ok(!trackMetaData(desc).contains("sec:"), "no subtitles: none left over from the last cast");
		r.stop();
		eq(TransportState.STOPPED, r.transportState(), "stop");
	}

	private static void discovery(URI desc) throws Exception {
		URI sameTvAgain = URI.create("http://localhost:" + desc.getPort() + desc.getPath());
		URI dead = URI.create("http://127.0.0.1:1/dmr");
		URI notHttp = URI.create("ftp://127.0.0.1/dmr");
		DeviceSearch answers = (_, _) -> List.of(desc, sameTvAgain, dead, notHttp);

		List<RendererInfo> found = Renderer.discover(NO_WAIT, answers);
		eq(1, found.size(), "once each, and what doesn't load is left out: " + found);
		eq(NAME, found.getFirst().name(), "by its description");
		eq(NAME, Renderer.find(NO_WAIT, answers).info().name(), "the only renderer");
		IOException none = fails(IOException.class, () -> Renderer.find(NO_WAIT, (_, _) -> List.of()), "nothing on the network");
		ok(none.getMessage().contains("--ip"), "says what to try: " + none.getMessage());

		List<Discovery.Found> tvs = Discovery.discover(NO_WAIT, answers, _ -> Optional.of(Generation.TIZEN_TOKEN));
		eq(List.of(new Discovery.Found(new Host("127.0.0.1"), Generation.TIZEN_TOKEN, NAME, "UN75NU8000 (pseudo)")), tvs, "a Samsung TV, with its generation");
		eq(List.of(), Discovery.discover(NO_WAIT, answers, _ -> Optional.empty()), "a renderer that isn't a Samsung TV is left out");
		fails(IllegalStateException.class, () -> Discovery.discover(NO_WAIT, answers, _ -> {
			throw new ArithmeticException("a bug in the detector");
		}), "a broken detector is a bug, and not the same as no TV");
	}

	private static TransportState awaitState(Renderer r, TransportState want) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		TransportState s = r.transportState();
		while (s != want && System.nanoTime() < deadline) {
			Thread.sleep(100);
			s = r.transportState();
		}
		return s;
	}
}
