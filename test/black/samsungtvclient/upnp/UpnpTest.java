package black.samsungtvclient.upnp;

import static black.samsungtvclient.Check.eq;
import static black.samsungtvclient.Check.fails;
import static black.samsungtvclient.Check.ok;

import black.samsungtvclient.PseudoHttp;
import black.samsungtvclient.util.Urls;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

public final class UpnpTest {
	private UpnpTest() {}

	private static final URI DESC = URI.create("http://10.0.0.5:9197/dmr");

	private static String description(String urlBase, String services) {
		return "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\">"
			+ (urlBase == null ? "" : "<URLBase>" + urlBase + "</URLBase>")
			+ "<device><friendlyName>Living Room</friendlyName><modelName>X1</modelName>"
			+ "<UDN>uuid:abc</UDN><serviceList>" + services + "</serviceList></device></root>";
	}

	private static String service(String name, String control) {
		return "<service><serviceType>urn:schemas-upnp-org:service:" + name + ":1</serviceType>"
			+ "<controlURL>" + control + "</controlURL><eventSubURL>/ev</eventSubURL></service>";
	}

	public static void run() throws IOException {
		Device d = Device.parse(DESC, description(null,
			service("AVTransport", "/upnp/control/AVTransport1") + service("RenderingControl", "rc")));
		eq(URI.create("http://10.0.0.5:9197/upnp/control/AVTransport1"),
			d.requireService("AVTransport").controlUrl(), "absolute-path control URL");
		eq(URI.create("http://10.0.0.5:9197/rc"), d.requireService("RenderingControl").controlUrl(),
			"relative control URL resolves against the description URL");
		eq(Optional.of("uuid:abc"), d.udn(), "UDN");
		eq("Living Room", d.info().name(), "friendly name");
		ok(d.isMediaRenderer(), "AVTransport + RenderingControl is a MediaRenderer");

		Device based = Device.parse(DESC, description("http://10.0.0.9:7676/", service("AVTransport", "av")));
		eq(URI.create("http://10.0.0.9:7676/av"), based.requireService("AVTransport").controlUrl(),
			"URLBase wins when present");
		ok(!based.isMediaRenderer(), "AVTransport alone isn't enough for Renderer");
		fails(IOException.class, () -> based.requireService("RenderingControl"), "missing service");

		fails(IOException.class, () -> Device.parse(DESC, description("http://bad host/", "")),
			"bad URLBase is an IOException, not an unchecked one");
		fails(IOException.class, () -> Device.parse(DESC, "<root/>"), "no <device>");
		fails(IOException.class, () -> Device.parse(DESC, "not xml"), "malformed XML");
		fails(IOException.class, () -> Device.parse(DESC,
			"<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><root><device>&x;</device></root>"),
			"DOCTYPE (XXE) is refused");

		eq("http://h/My%20Clip%20%C3%A9.mp4?a=1&b=%7Bx%7D", Urls.encodeIllegal("http://h/My Clip é.mp4?a=1&b={x}"),
			"encodeIllegal escapes spaces, braces and non-ASCII");
		eq("http://h/a%20b.mp4", Urls.encodeIllegal("http://h/a%20b.mp4"), "existing escapes untouched");

		eq("video/mp4", Didl.guessMime("http://h/clip.MP4?x=1"), "mime from extension, case-insensitive");
		eq("audio/mpeg", Didl.guessMime("http://h/song.mp3"), "audio mime");
		eq("video/mp4", Didl.guessMime("http://h/watch"), "default mime");
		eq("My Clip.mp4", Didl.titleFrom("http://h/dir/My%20Clip.mp4"), "title is the decoded file name");
		eq("h", Didl.titleFrom("http://h/"), "title falls back to host");
		String didl = Didl.forMedia("http://h/a.mp4?x=1&y=2", "A <b>", "video/mp4");
		ok(didl.contains("<dc:title>A &lt;b&gt;</dc:title>") && didl.contains("<upnp:class>object.item.videoItem<")
			&& didl.contains("protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01\">http://h/a.mp4?x=1&amp;y=2</res>"),
			"DIDL-Lite keeps the Samsung-verified shape and escapes: " + didl);
		ok(Didl.forMedia("http://h/live.m3u8", "a", "application/vnd.apple.mpegurl").contains(":DLNA.ORG_OP=10\""),
			"HLS gets time seek: without an OP flag the TV refuses every seek");
		ok(Didl.forMedia("http://h/a.mp3", "a", "audio/mpeg").contains("object.item.audioItem.musicTrack"),
			"audio class");

		String captioned = Didl.forMedia("http://h/a.mkv", "a", "video/x-matroska", "http://h/a.en.SRT?k=1&v=2");
		ok(captioned.contains(" xmlns:sec=\"http://www.sec.co.kr/\"")
			&& captioned.contains("<sec:CaptionInfoEx sec:type=\"srt\">http://h/a.en.SRT?k=1&amp;v=2</sec:CaptionInfoEx>")
			&& captioned.contains("<sec:CaptionInfo sec:type=\"srt\">http://h/a.en.SRT?k=1&amp;v=2</sec:CaptionInfo>")
			&& captioned.contains("<res protocolInfo=\"http-get:*:smi/caption:*\">http://h/a.en.SRT?k=1&amp;v=2</res>"),
			"subtitles: Samsung's sec:CaptionInfoEx and CaptionInfo, plus a caption res: " + captioned);
		ok(captioned.indexOf("video/x-matroska") < captioned.indexOf("smi/caption"), "the video's res comes first");
		ok(!Didl.forMedia("http://h/a.mp4", "a", "video/mp4").contains("sec:"), "no subtitles, no sec: namespace");
		badReplies();
		eq("smi", Didl.subtitleType("http://h/a.sami"), "sami is smi");
		eq("ass", Didl.subtitleType("http://h/a.ass"), "ass");
		eq("vtt", Didl.subtitleType("http://h/a.vtt"), "vtt");
		eq("srt", Didl.subtitleType("http://h/subs?id=3"), "unknown extension: srt");
		ok(Didl.isSubtitle("http://h/a.SUB") && !Didl.isSubtitle("http://h/a.mp4"), "isSubtitle by extension");
	}

	/** A renderer's answers that aren't what they should be. */
	private static void badReplies() throws IOException {
		try (PseudoHttp tv = new PseudoHttp()) {
			String type = "urn:schemas-upnp-org:service:RenderingControl:1";
			RenderingControl rendering = new RenderingControl(new Device.Service(type, tv.uri("/rc")));
			for (String body : new String[] {"<GetVolumeResponse><CurrentVolume>loud</CurrentVolume></GetVolumeResponse>", "<GetVolumeResponse/>"}) {
				tv.on("POST", "/rc", 200, body);
				IOException e = fails(IOException.class, rendering::getVolume, "a volume that isn't a number");
				ok(e.getMessage().contains("CurrentVolume"), e.getMessage());
			}
			tv.on("POST", "/rc", 200, "<GetMuteResponse/>");
			eq(false, rendering.getMute(), "a TV that doesn't say isn't muted");
			IOException unusable = fails(IOException.class, () -> Device.fetch(URI.create("ftp://127.0.0.1/dmr")), "a LOCATION HTTP can't fetch");
			ok(unusable.getMessage().contains("ftp://"), unusable.getMessage());
		}
	}
}
