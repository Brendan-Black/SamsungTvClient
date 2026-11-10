import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in Samsung MediaRenderer for testing without the TV. Single-file program, no build needed:
 *
 * <pre>
 *   java test\PseudoTv.java [port] [--no-ssdp]        (default 9197; 0 picks a free port)
 *   tv.bat --ip 127.0.0.1 status
 * </pre>
 *
 * It serves a {@code /dmr} description, implements the AVTransport and RenderingControl actions
 * the renderer client uses (with TRANSITIONING -> PLAYING after a second and a running clock), returns real UPnP
 * faults for invalid transitions, and answers SSDP M-SEARCH so {@code discover} can find it.
 */
public class PseudoTv {
	static final long DURATION_S = 10 * 60;
	static final long BUFFER_MS = 1000;

	// Transport state; all access synchronized on PseudoTv.class.
	static String uri = "";
	static String metadata = ""; // CurrentURIMetaData as sent, echoed in GetPositionInfo's TrackMetaData
	static String state = "NO_MEDIA_PRESENT";
	static long transitionEndsAt; // while TRANSITIONING: when PLAYING begins
	static long playStartedAt; // while PLAYING: wall clock at offsetMs
	static long offsetMs;
	static int volume = 15;
	static boolean muted;

	public static void main(String[] args) throws IOException {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 9197; // 0 picks a free one, and says which
		HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
		http.createContext("/dmr", ex -> send(ex, 200, description()));
		http.createContext("/upnp/control/", PseudoTv::control);
		http.start();
		int bound = http.getAddress().getPort();
		System.out.println("PseudoTv on http://127.0.0.1:" + bound + "/dmr  (Ctrl+C to stop)");
		if (!Arrays.asList(args).contains("--no-ssdp")) { Thread.ofVirtual().start(() -> ssdp(bound)); }
	}

	static void control(HttpExchange ex) throws IOException {
		String soapAction = ex.getRequestHeaders().getFirst("SOAPAction");
		String body = new String(ex.getRequestBody().readAllBytes(), UTF_8);
		if (soapAction == null || !soapAction.contains("#")) {
			send(ex, 400, "missing SOAPAction");
			return;
		}
		String serviceType = soapAction.replace("\"", "").split("#")[0];
		String action = soapAction.replace("\"", "").split("#")[1];
		String result;
		synchronized (PseudoTv.class) {
			tick();
			result = handle(action, body);
			System.out.printf("%-18s -> %s%n", action, result == null ? "FAULT" : state);
		}
		if (result == null) {
			int code = action.equals("Play") || action.equals("Seek") || action.equals("Pause") ? 701 : 401;
			send(ex, 500, fault(code, code == 701 ? "Transition not available" : "Invalid Action"));
		} else {
			send(ex, 200, envelope("<u:" + action + "Response xmlns:u=\"" + serviceType + "\">" + result
				+ "</u:" + action + "Response>"));
		}
	}

	/** Returns the response's out-argument XML, or null for a UPnP fault. Caller holds the lock. */
	static String handle(String action, String body) {
		switch (action) {
			case "SetAVTransportURI" -> {
				uri = unescape(arg(body, "CurrentURI"));
				metadata = unescape(arg(body, "CurrentURIMetaData"));
				state = "STOPPED";
				offsetMs = 0;
				return "";
			}
			case "Play" -> {
				if (uri.isEmpty()) { return null; }
				if (!state.equals("PLAYING") && !state.equals("TRANSITIONING")) {
					state = "TRANSITIONING";
					transitionEndsAt = System.currentTimeMillis() + BUFFER_MS;
				}
				return "";
			}
			case "Pause" -> {
				if (!state.equals("PLAYING")) { return null; }
				offsetMs = positionMs();
				state = "PAUSED_PLAYBACK";
				return "";
			}
			case "Stop" -> {
				if (!uri.isEmpty()) { state = "STOPPED"; }
				offsetMs = 0;
				return "";
			}
			case "Seek" -> {
				if (uri.isEmpty()) { return null; }
				String[] t = arg(body, "Target").split(":");
				long s = Long.parseLong(t[0]) * 3600 + Long.parseLong(t[1]) * 60 + Long.parseLong(t[2]);
				offsetMs = Math.min(s, DURATION_S) * 1000;
				playStartedAt = System.currentTimeMillis();
				return "";
			}
			case "GetTransportInfo" -> {
				return "<CurrentTransportState>" + state + "</CurrentTransportState>"
					+ "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>";
			}
			case "GetPositionInfo" -> {
				boolean loaded = !uri.isEmpty();
				return "<Track>" + (loaded ? 1 : 0) + "</Track>"
					+ "<TrackDuration>" + (loaded ? hms(DURATION_S) : "00:00:00") + "</TrackDuration>"
					+ "<TrackMetaData>" + escape(metadata) + "</TrackMetaData>"
					+ "<TrackURI>" + escape(uri) + "</TrackURI>"
					+ "<RelTime>" + hms(positionMs() / 1000) + "</RelTime>"
					+ "<AbsTime>NOT_IMPLEMENTED</AbsTime><RelCount>2147483647</RelCount>"
					+ "<AbsCount>2147483647</AbsCount>";
			}
			case "GetVolume" -> {
				return "<CurrentVolume>" + volume + "</CurrentVolume>";
			}
			case "SetVolume" -> {
				volume = Integer.parseInt(arg(body, "DesiredVolume"));
				return "";
			}
			case "GetMute" -> {
				return "<CurrentMute>" + (muted ? 1 : 0) + "</CurrentMute>";
			}
			case "SetMute" -> {
				muted = arg(body, "DesiredMute").equals("1");
				return "";
			}
			default -> {
				return null;
			}
		}
	}

	/** Advances TRANSITIONING to PLAYING and stops at the end of the track. Caller holds the lock. */
	static void tick() {
		long now = System.currentTimeMillis();
		if (state.equals("TRANSITIONING") && now >= transitionEndsAt) {
			state = "PLAYING";
			playStartedAt = now;
		}
		if (state.equals("PLAYING") && positionMs() >= DURATION_S * 1000) {
			state = "STOPPED";
			offsetMs = 0;
		}
	}

	static long positionMs() {
		return state.equals("PLAYING") ? offsetMs + System.currentTimeMillis() - playStartedAt : offsetMs;
	}

	static void ssdp(int port) {
		try (MulticastSocket socket = new MulticastSocket(1900)) {
			InetSocketAddress group = new InetSocketAddress("239.255.255.250", 1900);
			int joined = 0;
			for (NetworkInterface ni : NetworkInterface.networkInterfaces().toList()) {
				try {
					if (ni.isUp() && ni.supportsMulticast()
						&& ni.inetAddresses().anyMatch(a -> a instanceof Inet4Address)) {
						socket.joinGroup(group, ni);
						joined++;
					}
				} catch (IOException e) {
					// skip interfaces that refuse the join
				}
			}
			System.out.println("SSDP responder listening on " + joined + " interface(s)");
			byte[] buf = new byte[2048];
			while (true) {
				DatagramPacket in = new DatagramPacket(buf, buf.length);
				socket.receive(in);
				String msg = new String(in.getData(), 0, in.getLength(), UTF_8);
				if (!msg.startsWith("M-SEARCH")) { continue; }
				if (!msg.contains("MediaRenderer") && !msg.contains("ssdp:all")) { continue; }
				// PseudoTv runs on the searching machine, so the searcher's own address is also ours, and
				// it's the address this reply comes from (the client only trusts a LOCATION on the sender).
				byte[] reply = ("HTTP/1.1 200 OK\r\n"
					+ "CACHE-CONTROL: max-age=1800\r\n"
					+ "EXT:\r\n"
					+ "LOCATION: http://" + in.getAddress().getHostAddress() + ":" + port + "/dmr\r\n"
					+ "SERVER: PseudoTv/1.0 UPnP/1.0\r\n"
					+ "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
					+ "USN: uuid:pseudo-tv-0001::urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
					+ "\r\n").getBytes(UTF_8);
				socket.send(new DatagramPacket(reply, reply.length, in.getSocketAddress()));
			}
		} catch (IOException e) {
			System.out.println("SSDP responder unavailable (" + e.getMessage() + "); discover won't see PseudoTv");
		}
	}

	static String description() {
		return """
			<?xml version="1.0"?>
			<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
			  <specVersion><major>1</major><minor>0</minor></specVersion>
			  <device>
			    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
			    <friendlyName>[TV] PseudoTv</friendlyName>
			    <manufacturer>Samsung Electronics</manufacturer>
			    <modelName>UN75NU8000 (pseudo)</modelName>
			    <UDN>uuid:pseudo-tv-0001</UDN>
			    <serviceList>
			      <service>
			        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
			        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
			        <controlURL>/upnp/control/RenderingControl1</controlURL>
			        <eventSubURL>/upnp/event/RenderingControl1</eventSubURL>
			        <SCPDURL>RenderingControl_1.xml</SCPDURL>
			      </service>
			      <service>
			        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
			        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
			        <controlURL>/upnp/control/ConnectionManager1</controlURL>
			        <eventSubURL>/upnp/event/ConnectionManager1</eventSubURL>
			        <SCPDURL>ConnectionManager_1.xml</SCPDURL>
			      </service>
			      <service>
			        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
			        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
			        <controlURL>/upnp/control/AVTransport1</controlURL>
			        <eventSubURL>/upnp/event/AVTransport1</eventSubURL>
			        <SCPDURL>AVTransport_1.xml</SCPDURL>
			      </service>
			    </serviceList>
			  </device>
			</root>
			""";
	}

	static String envelope(String inner) {
		return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
			+ " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" + inner + "</s:Body></s:Envelope>";
	}

	static String fault(int code, String description) {
		return envelope("<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
			+ "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code + "</errorCode>"
			+ "<errorDescription>" + description + "</errorDescription></UPnPError></detail></s:Fault>");
	}

	static String arg(String body, String name) {
		Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(body);
		return m.find() ? m.group(1) : "";
	}

	static String hms(long s) {
		return "%02d:%02d:%02d".formatted(s / 3600, s % 3600 / 60, s % 60);
	}

	static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	static String unescape(String s) {
		return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
			.replace("&amp;", "&");
	}

	static void send(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(UTF_8);
		ex.getResponseHeaders().set("Content-Type", "text/xml; charset=\"utf-8\"");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(bytes);
		}
		ex.close();
	}
}
