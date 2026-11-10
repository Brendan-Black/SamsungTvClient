package black.samsungtvclient.upnp;

import black.samsungtvclient.net.SoapClient;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** The RenderingControl service: volume and mute on the Master channel of instance 0. */
final class RenderingControl {
	static final String NAME = "RenderingControl";

	private final Device.Service service;

	RenderingControl(Device.Service service) {
		this.service = service;
	}

	int getVolume() throws IOException {
		String v = call("GetVolume").getOrDefault("CurrentVolume", "");
		try {
			return Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			throw new IOException("GetVolume: unexpected CurrentVolume \"" + v + "\"", e);
		}
	}

	/** @throws IllegalArgumentException if {@code level} is outside 0–100 */
	void setVolume(int level) throws IOException {
		if (level < 0 || level > 100) { throw new IllegalArgumentException("volume must be 0-100, got " + level); }
		call("SetVolume", "DesiredVolume", String.valueOf(level));
	}

	boolean getMute() throws IOException {
		String v = call("GetMute").getOrDefault("CurrentMute", "").trim();
		return v.equals("1") || v.equalsIgnoreCase("true");
	}

	void setMute(boolean on) throws IOException {
		call("SetMute", "DesiredMute", on ? "1" : "0");
	}

	private Map<String, String> call(String action, String... nameValues) throws IOException {
		Map<String, String> args = new LinkedHashMap<>();
		args.put("InstanceID", "0");
		args.put("Channel", "Master");
		for (int i = 0; i < nameValues.length; i += 2) { args.put(nameValues[i], nameValues[i + 1]); }
		return SoapClient.invoke(service.controlUrl(), service.serviceType(), action, args);
	}
}
