package black.samsungtvclient.upnp;

import black.samsungtvclient.model.PositionInfo;
import black.samsungtvclient.model.TransportState;
import black.samsungtvclient.net.SoapClient;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** The AVTransport service: load a URI and control playback. Always drives instance 0. */
final class AvTransport {
	static final String NAME = "AVTransport";

	private final Device.Service service;

	AvTransport(Device.Service service) {
		this.service = service;
	}

	/** Loads {@code uri} with DIDL-Lite {@code metadata} (see {@link Didl}); follow with {@link #play()}. */
	void setUri(String uri, String metadata) throws IOException {
		call("SetAVTransportURI", "CurrentURI", uri, "CurrentURIMetaData", metadata);
	}

	void play() throws IOException {
		call("Play", "Speed", "1");
	}

	void pause() throws IOException {
		call("Pause");
	}

	void stop() throws IOException {
		call("Stop");
	}

	/** Seeks to {@code relTime} ({@code HH:MM:SS}) within the current track. */
	void seek(String relTime) throws IOException {
		call("Seek", "Unit", "REL_TIME", "Target", relTime);
	}

	TransportState getTransportInfo() throws IOException {
		return TransportState.parse(call("GetTransportInfo").getOrDefault("CurrentTransportState", ""));
	}

	PositionInfo getPositionInfo() throws IOException {
		Map<String, String> r = call("GetPositionInfo");
		return new PositionInfo(r.getOrDefault("TrackURI", ""), r.getOrDefault("TrackDuration", ""), r.getOrDefault("RelTime", ""));
	}

	private Map<String, String> call(String action, String... nameValues) throws IOException {
		Map<String, String> args = new LinkedHashMap<>();
		args.put("InstanceID", "0");
		for (int i = 0; i < nameValues.length; i += 2) { args.put(nameValues[i], nameValues[i + 1]); }
		return SoapClient.invoke(service.controlUrl(), service.serviceType(), action, args);
	}
}
