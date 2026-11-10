package black.samsungtvclient.net;

import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.util.Xml;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Invokes UPnP actions: builds the SOAP envelope, POSTs it, and returns the action's out-arguments. */
public final class SoapClient {
	private SoapClient() {}

	/**
	 * Calls {@code action} on the service at {@code controlUrl}.
	 *
	 * @param args in-arguments, in the order the service declares them (values are escaped here)
	 * @return out-arguments by name, in response order
	 * @throws SoapFault   if the device answers with a UPnP error
	 * @throws IOException if the device can't be reached or answers unexpectedly
	 */
	public static Map<String, String> invoke(URI controlUrl, String serviceType, String action,
		Map<String, String> args) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(controlUrl)
			.timeout(Http.REQUEST_TIMEOUT)
			.header("Content-Type", "text/xml; charset=\"utf-8\"")
			.header("SOAPAction", "\"" + serviceType + "#" + action + "\"")
			.POST(HttpRequest.BodyPublishers.ofString(envelope(serviceType, action, args), UTF_8))
			.build();
		Http.Response response = Http.send(request);
		return switch (response.statusCode()) {
			case 200 -> outArgs(action, response.body());
			case 500 -> throw fault(action, response.body());
			default -> throw new IOException(action + " -> HTTP " + response.statusCode());
		};
	}

	static String envelope(String serviceType, String action, Map<String, String> args) {
		StringBuilder sb = new StringBuilder(256)
			.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
			.append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"")
			.append(" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
			.append("<u:").append(action).append(" xmlns:u=\"").append(Xml.escape(serviceType)).append("\">");
		args.forEach((name, value) -> sb.append('<').append(name).append('>').append(Xml.escape(value)).append("</").append(name).append('>'));
		return sb.append("</u:").append(action).append("></s:Body></s:Envelope>").toString();
	}

	private static Map<String, String> outArgs(String action, String xml) throws IOException {
		Element response;
		try {
			response = Xml.first(Xml.parse(xml), action + "Response")
				.orElseThrow(() -> new IOException(action + ": response has no " + action + "Response element"));
		} catch (IllegalArgumentException e) {
			throw new IOException(action + ": unreadable response (" + e.getMessage() + ")", e);
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (Element child : Xml.children(response)) { out.put(child.getLocalName(), child.getTextContent()); }
		return Collections.unmodifiableMap(out);
	}

	private static IOException fault(String action, String xml) {
		try {
			Document doc = Xml.parse(xml);
			Optional<String> code = Xml.text(doc, "errorCode");
			if (code.isPresent()) { return new SoapFault(action, Integer.parseInt(code.get()), Xml.text(doc, "errorDescription").orElse("")); }
			return new IOException(action + " failed: " + Xml.text(doc, "faultstring").orElse("HTTP 500"));
		} catch (IllegalArgumentException e) {
			return new IOException(action + " failed: unreadable HTTP 500 reply", e);
		}
	}
}
