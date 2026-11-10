package black.samsungtvclient.upnp;

import black.samsungtvclient.model.RendererInfo;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.net.Http;
import black.samsungtvclient.util.Xml;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** A UPnP device description (e.g. the TV's {@code /dmr}), with its services' control URLs. */
final class Device {
	/** One {@code <service>} entry; its URL is already resolved to absolute form. */
	record Service(String serviceType, URI controlUrl) {}

	private final URI descriptionUrl;
	private final Optional<String> friendlyName;
	private final String modelName;
	private final Optional<String> udn;
	private final List<Service> services;

	private Device(URI descriptionUrl, Optional<String> friendlyName, String modelName, Optional<String> udn, List<Service> services) {
		this.descriptionUrl = descriptionUrl;
		this.friendlyName = friendlyName;
		this.modelName = modelName;
		this.udn = udn;
		this.services = List.copyOf(services);
	}

	/** @throws IOException also for a URL HTTP can't fetch, since what's discovered is whatever the LAN says */
	static Device fetch(URI descriptionUrl) throws IOException {
		try {
			return parse(descriptionUrl, Http.get(descriptionUrl));
		} catch (IllegalArgumentException e) {
			throw new IOException("can't fetch a device description from " + descriptionUrl, e);
		}
	}

	static Device parse(URI descriptionUrl, String xml) throws IOException {
		Document doc;
		try {
			doc = Xml.parse(xml);
		} catch (IllegalArgumentException e) {
			throw new IOException("unreadable device description at " + descriptionUrl, e);
		}
		Element device = Xml.first(doc, "device").orElseThrow(() -> new IOException("no <device> in description at " + descriptionUrl));

		List<Service> services = new ArrayList<>();
		try {
			// Relative URLs resolve against <URLBase> when present (UPnP 1.0), else the description URL.
			URI base = Xml.text(doc, "URLBase").filter(u -> !u.isEmpty()).map(URI::create).orElse(descriptionUrl);
			for (Element s : Xml.all(doc, "service")) {
				Optional<String> type = Xml.text(s, "serviceType");
				Optional<String> control = Xml.text(s, "controlURL");
				if (type.isPresent() && control.isPresent()) { services.add(new Service(type.get(), base.resolve(control.get()))); }
			}
		} catch (IllegalArgumentException e) {
			throw new IOException("bad URL in device description at " + descriptionUrl + ": " + e.getMessage(), e);
		}
		return new Device(descriptionUrl, Xml.text(device, "friendlyName").filter(n -> !n.isEmpty()),
			Xml.text(device, "modelName").orElse("unknown"), Xml.text(device, "UDN"), services);
	}

	/** The service of the given kind (e.g. {@code "AVTransport"}), whatever version the device offers. */
	Optional<Service> service(String name) {
		String prefix = "urn:schemas-upnp-org:service:" + name + ":";
		return services.stream().filter(s -> s.serviceType().startsWith(prefix)).findFirst();
	}

	Service requireService(String name) throws IOException {
		return service(name).orElseThrow(() -> new IOException(displayName() + " has no " + name + " service (is it a MediaRenderer?)"));
	}

	/** Whether this device has both services {@link Renderer} needs. */
	boolean isMediaRenderer() {
		return service(AvTransport.NAME).isPresent() && service(RenderingControl.NAME).isPresent();
	}

	RendererInfo info() {
		return new RendererInfo(displayName(), modelName, new Host(descriptionUrl.getHost()), descriptionUrl);
	}

	String displayName() {
		return friendlyName.orElse(descriptionUrl.getHost());
	}

	URI descriptionUrl() {
		return descriptionUrl;
	}

	/** The device's unique id ({@code uuid:...}), if the description has one. */
	Optional<String> udn() {
		return udn;
	}
}
