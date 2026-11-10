package black.samsungtvclient.api;

import black.samsungtvclient.net.MacAddress;
import black.samsungtvclient.util.Json;
import black.samsungtvclient.util.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a Samsung TV says about itself at {@code http://<tv>:8001/api/v2/}. Text a TV leaves out reads {@link #UNKNOWN},
 * except the three fields callers act on, which are null and have an Optional accessor.
 *
 * @param id            the TV's unique id ({@code uuid:...})
 * @param name          its name on the network, e.g. "[TV] Living Room"
 * @param modelName     the marketing model, e.g. UN75NU8000
 * @param model         the internal model, or null
 * @param os            e.g. Tizen
 * @param wifiMac       the MAC address Wake-on-LAN goes to, or null
 * @param powerState    "on" or "standby" on TVs that report it (2019 and later), else null
 * @param tokenAuth     whether remote control needs pairing (port 8002 and a token) rather than plain port 8001
 * @param developerMode whether developer mode is on (needed to install apps from a PC)
 * @param features      the {@code isSupport} flags, e.g. {@code remote_available=true}
 */
public record TvInfo(String id, String name, String modelName, @Nullable String model, String os, String ip, @Nullable String wifiMac,
	String networkType, String resolution, @Nullable String powerState, boolean tokenAuth, boolean developerMode,
	Map<String, String> features) {

	public static final String UNKNOWN = "unknown";

	/** @throws IllegalArgumentException if {@code json} isn't the API's device object */
	public static TvInfo parse(String json) {
		if (!(Json.parse(json) instanceof Map<?, ?> root) || !(root.get("device") instanceof Map<?, ?> d)) {
			throw new IllegalArgumentException("not a Samsung TV's /api/v2/ reply");
		}
		Map<String, String> features = new LinkedHashMap<>();
		// isSupport is a JSON object encoded as a string, with "true"/"false" strings for values.
		if (root.get("isSupport") instanceof String s && !s.isBlank()) {
			try {
				if (Json.parse(s) instanceof Map<?, ?> m) { m.forEach((k, v) -> features.put(String.valueOf(k), String.valueOf(v))); }
			} catch (IllegalArgumentException ignored) {
				// a TV with a malformed flag list still has a usable device object
			}
		}
		return new TvInfo(text(d, "id"), text(d, "name"), text(d, "modelName"), Json.string(d, "model").orElse(null), text(d, "OS"), text(d, "ip"),
			Json.string(d, "wifiMac").orElse(null), text(d, "networkType"), text(d, "resolution"), Json.string(d, "PowerState").orElse(null),
			"true".equals(text(d, "TokenAuthSupport")), "1".equals(text(d, "developerMode")), Map.copyOf(features));
	}

	/** Whether the TV says it supports {@code feature} (an {@code isSupport} flag). */
	public boolean supports(String feature) {
		return "true".equals(features.get(feature));
	}

	public Optional<String> knownModel() {
		return Optional.ofNullable(model);
	}

	public Optional<String> knownPowerState() {
		return Optional.ofNullable(powerState);
	}

	/** The MAC address, if the TV gave a usable one (some send a blank or a placeholder). */
	public Optional<MacAddress> knownMac() {
		try {
			return Optional.ofNullable(wifiMac).map(MacAddress::new);
		} catch (IllegalArgumentException e) {
			return Optional.empty(); // the same to a caller as not having one: there's nothing to wake it with
		}
	}

	private static String text(Map<?, ?> device, String key) {
		return Json.string(device, key).orElse(UNKNOWN);
	}
}
