package black.samsungtvclient.net;

import static black.samsungtvclient.Check.eq;
import static black.samsungtvclient.Check.fails;
import static black.samsungtvclient.Check.ok;

import black.samsungtvclient.PseudoHttp;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class NetTest {
	private static final String SERVICE = "urn:schemas-upnp-org:service:AVTransport:1";

	private NetTest() {}

	public static void run() throws Exception {
		eq(Optional.of(URI.create("http://10.0.0.5:9197/dmr")),
			Ssdp.location("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nlocation:  http://10.0.0.5:9197/dmr \r\n\r\n"),
			"LOCATION header, any case, trimmed");
		eq(Optional.empty(), Ssdp.location("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"), "no LOCATION");
		eq(Optional.empty(), Ssdp.location("HTTP/1.1 200 OK\r\nLOCATION: http://bad host/\r\n"), "malformed LOCATION");

		Map<String, String> args = new LinkedHashMap<>();
		args.put("InstanceID", "0");
		args.put("CurrentURI", "http://h/a.mp4?x=1&y=<2>");
		String env = SoapClient.envelope(SERVICE, "SetAVTransportURI", args);
		ok(env.contains("<u:SetAVTransportURI xmlns:u=\"" + SERVICE + "\">"
			+ "<InstanceID>0</InstanceID><CurrentURI>http://h/a.mp4?x=1&amp;y=&lt;2&gt;</CurrentURI>"),
			"envelope escapes argument values, keeps order: " + env);

		eq("Play failed: UPnP error 701 (Transition not available)", new SoapFault("Play", SoapFault.TRANSITION_NOT_AVAILABLE, "").getMessage(),
			"fault explains known codes");
		eq("Seek failed: UPnP error 710 (custom)", new SoapFault("Seek", 710, "custom").getMessage(),
			"fault prefers the device's description");

		byte[] magic = WakeOnLan.packet(new MacAddress("02:00:00:5F:82:78"));
		eq(102, magic.length, "magic packet: 6 + 16 x 6 bytes");
		ok(magic[0] == (byte) 0xFF && magic[5] == (byte) 0xFF && magic[6] == 0x02 && magic[11] == 0x78
			&& magic[96] == 0x02 && magic[101] == 0x78, "0xFF x 6, then the MAC 16 times");
		eq(Arrays.toString(magic), Arrays.toString(WakeOnLan.packet(new MacAddress("0200005f8278"))), "MAC with or without separators");

		soapReplies();
		httpLimits();
	}

	/** What a device can answer a SOAP call with, and what each becomes. */
	private static void soapReplies() throws IOException {
		record Reply(int status, String body, String expected) {}
		String upnpFault = "<s:Envelope xmlns:s='x'><s:Body><UPnPError><errorCode>718</errorCode></UPnPError></s:Body></s:Envelope>";
		try (PseudoHttp tv = new PseudoHttp()) {
			URI control = tv.uri("/control");
			for (Reply r : new Reply[] {
				new Reply(200, "not xml", "unreadable response"),
				new Reply(200, "<root/>", "response has no PlayResponse"),
				new Reply(500, "not xml", "unreadable HTTP 500"),
				new Reply(500, "<f><faultstring>nope</faultstring></f>", "failed: nope"),
				new Reply(500, "<f/>", "failed: HTTP 500"),
				new Reply(503, "", "HTTP 503"),
			}) {
				tv.on("POST", "/control", r.status(), r.body());
				IOException e = fails(IOException.class, () -> SoapClient.invoke(control, SERVICE, "Play", Map.of()), r.expected());
				ok(e.getMessage().contains(r.expected()), r.expected() + " in: " + e.getMessage());
				ok(!(e instanceof SoapFault), "not the device refusing: " + r.expected());
			}

			tv.on("POST", "/control", 500, upnpFault);
			SoapFault fault = fails(SoapFault.class, () -> SoapClient.invoke(control, SERVICE, "Play", Map.of()), "a UPnP error");
			eq(718, fault.code(), "the device's code");
			ok(fault.getMessage().contains("Invalid InstanceID"), "explained: " + fault.getMessage());

			tv.on("POST", "/control", 200, "<PlayResponse><Speed>1</Speed></PlayResponse>");
			eq(Map.of("Speed", "1"), SoapClient.invoke(control, SERVICE, "Play", Map.of()), "out-arguments by name");
		}
	}

	private static void httpLimits() throws IOException {
		try (PseudoHttp tv = new PseudoHttp()) {
			tv.on("GET", "/big", 200, new byte[Http.MAX_RESPONSE_BYTES + 1]);
			tv.on("GET", "/exact", 200, new byte[Http.MAX_RESPONSE_BYTES]);
			tv.on("GET", "/gone", 404, "");
			IOException big = fails(IOException.class, () -> Http.get(tv.uri("/big")), "a reply far bigger than any TV's");
			ok(big.getMessage().contains("larger than"), "says so: " + big.getMessage());
			eq(Http.MAX_RESPONSE_BYTES, Http.get(tv.uri("/exact")).length(), "exactly at the limit is fine");
			IOException gone = fails(IOException.class, () -> Http.get(tv.uri("/gone")), "GET fails on an error status");
			ok(gone.getMessage().contains("404"), "with the status: " + gone.getMessage());
			eq(404, Http.request(Http.Method.GET, tv.uri("/gone")).statusCode(), "request returns it");
		}
		eq("connection refused or host unreachable", Http.describe(new java.net.ConnectException()), "a JDK failure, in words");
		eq("EOFException", Http.describe(new java.io.EOFException()), "one with no message still says something");
	}
}
