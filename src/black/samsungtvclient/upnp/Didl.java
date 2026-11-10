package black.samsungtvclient.upnp;

import black.samsungtvclient.util.Xml;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * DIDL-Lite metadata for {@code SetAVTransportURI}. Samsung is picky: the shape below
 * ({@code object.item.videoItem} + {@code http-get:*:video/mp4:*}) is the one verified on the UN75NU8000.
 */
public final class Didl {
	/** Subtitle extensions {@link #subtitleType} knows, for messages. */
	public static final String SUBTITLE_FORMATS = "srt, smi, sub, ssa, ass, vtt, ttml";

	/** Samsung's DLNA extension namespace. */
	static final String SEC_NAMESPACE = "http://www.sec.co.kr/";

	/** The MIME type Samsung expects on a subtitle {@code res}, whatever the format. */
	static final String SUBTITLE_MIME = "smi/caption";

	private static final Map<String, String> MIME_BY_EXTENSION = Map.ofEntries(
		Map.entry("mp4", "video/mp4"),
		Map.entry("m4v", "video/mp4"),
		Map.entry("mov", "video/quicktime"),
		Map.entry("mkv", "video/x-matroska"),
		Map.entry("webm", "video/webm"),
		Map.entry("avi", "video/x-msvideo"),
		Map.entry("ts", "video/mp2t"),
		Map.entry("m2ts", "video/mp2t"),
		Map.entry("mpg", "video/mpeg"),
		Map.entry("mpeg", "video/mpeg"),
		Map.entry("m3u8", "application/vnd.apple.mpegurl"),
		Map.entry("mp3", "audio/mpeg"),
		Map.entry("m4a", "audio/mp4"),
		Map.entry("aac", "audio/aac"),
		Map.entry("flac", "audio/flac"),
		Map.entry("wav", "audio/wav"),
		Map.entry("jpg", "image/jpeg"),
		Map.entry("jpeg", "image/jpeg"),
		Map.entry("png", "image/png"));

	/** Subtitle formats Samsung TVs read, by file extension; the value is the {@code sec:type}. */
	private static final Map<String, String> SUBTITLE_TYPES = Map.of(
		"srt", "srt",
		"smi", "smi",
		"sami", "smi",
		"sub", "sub",
		"ssa", "ssa",
		"ass", "ass",
		"vtt", "vtt",
		"ttml", "ttml",
		"xml", "ttml");

	private Didl() {}

	public static String forMedia(String url, String title, String mime) {
		return document(url, title, mime, "", "");
	}

	/**
	 * As {@link #forMedia(String, String, String)}, with an external subtitle file at {@code subtitles} (an
	 * absolute http(s) URL). Samsung TVs read subtitles from their own DIDL-Lite extension:
	 * {@code sec:CaptionInfoEx} (and the older {@code sec:CaptionInfo}) in the {@code http://www.sec.co.kr/}
	 * namespace, typed by format, plus a second {@code res} for the file. Other renderers ignore both.
	 */
	public static String forMedia(String url, String title, String mime, String subtitles) {
		String type = subtitleType(subtitles);
		String href = Xml.escape(subtitles);
		return document(url, title, mime, " xmlns:sec=\"" + SEC_NAMESPACE + "\"",
			"<sec:CaptionInfoEx sec:type=\"" + type + "\">" + href + "</sec:CaptionInfoEx>"
				+ "<sec:CaptionInfo sec:type=\"" + type + "\">" + href + "</sec:CaptionInfo>"
				+ "<res protocolInfo=\"http-get:*:" + SUBTITLE_MIME + ":*\">" + href + "</res>");
	}

	private static String document(String url, String title, String mime, String xmlns, String captions) {
		return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
			+ " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
			+ " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"" + xmlns + ">"
			+ "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
			+ "<dc:title>" + Xml.escape(title) + "</dc:title>"
			+ "<upnp:class>" + upnpClass(mime) + "</upnp:class>"
			+ "<res protocolInfo=\"http-get:*:" + Xml.escape(mime) + ":" + seekFlags(mime) + "\">" + Xml.escape(url) + "</res>"
			+ captions
			+ "</item></DIDL-Lite>";
	}

	/** Whether {@code url}'s file extension is a subtitle format Samsung TVs read. */
	public static boolean isSubtitle(String url) {
		return SUBTITLE_TYPES.containsKey(extension(url));
	}

	/** The {@code sec:type} for the subtitle file at {@code url}, from its extension; {@code srt} when unknown. */
	static String subtitleType(String url) {
		return SUBTITLE_TYPES.getOrDefault(extension(url), "srt");
	}

	private static String extension(String url) {
		String name = lastSegment(url).toLowerCase(Locale.ROOT);
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1);
	}

	/**
	 * DLNA.ORG_OP: without it the UN75NU8000 refuses every Seek (UPnP 701), with either bit set it seeks.
	 * HLS has no byte offsets, so it gets time seek (10); plain files get byte-range seek (01), which any
	 * HTTP server with Range support provides.
	 */
	static String seekFlags(String mime) {
		String m = mime.toLowerCase(Locale.ROOT);
		return m.contains("mpegurl") ? "DLNA.ORG_OP=10" : "DLNA.ORG_OP=01";
	}

	/** MIME type from the URL's file extension; {@code video/mp4} when there isn't a recognisable one. */
	public static String guessMime(String url) {
		return mimeFromExtension(url).orElse("video/mp4");
	}

	/** MIME type from the URL's file extension, if it's one this class knows. */
	public static Optional<String> mimeFromExtension(String url) {
		return Optional.ofNullable(MIME_BY_EXTENSION.get(extension(url)));
	}

	/** A display title: the URL's file name, else its host. */
	public static String titleFrom(String url) {
		String name = lastSegment(url);
		if (!name.isEmpty()) { return name; }
		String host = URI.create(url).getHost();
		return host != null ? host : url;
	}

	private static String upnpClass(String mime) {
		if (mime.startsWith("audio/")) { return "object.item.audioItem.musicTrack"; }
		if (mime.startsWith("image/")) { return "object.item.imageItem.photo"; }
		return "object.item.videoItem";
	}

	private static String lastSegment(String url) {
		String path = URI.create(url).getPath();
		return path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
	}
}
