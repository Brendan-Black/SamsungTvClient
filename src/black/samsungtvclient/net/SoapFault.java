package black.samsungtvclient.net;

import java.io.IOException;

/**
 * A UPnP error the device returned as a SOAP fault (HTTP 500 carrying a {@code UPnPError}).
 * Unlike a plain {@link IOException}, this means the TV is reachable and refused the request.
 */
public final class SoapFault extends IOException {
	public static final int TRANSITION_NOT_AVAILABLE = 701;

	private static final long serialVersionUID = 1L;

	private final String action;
	private final int code;

	/** @param description what the device said; blank to use the standard meaning of {@code code} */
	public SoapFault(String action, int code, String description) {
		super(action + " failed: UPnP error " + code + " (" + explain(code, description) + ")");
		this.action = action;
		this.code = code;
	}

	public String action() {
		return action;
	}

	public int code() {
		return code;
	}

	private static String explain(int code, String description) {
		if (!description.isBlank()) { return description; }
		return switch (code) {
			case 401 -> "Invalid Action";
			case 402 -> "Invalid Args";
			case 501 -> "Action Failed";
			case TRANSITION_NOT_AVAILABLE -> "Transition not available";
			case 702 -> "No contents";
			case 710 -> "Seek mode not supported";
			case 711 -> "Illegal seek target";
			case 714 -> "Illegal MIME-type";
			case 716 -> "Resource not found";
			case 718 -> "Invalid InstanceID";
			default -> "no description";
		};
	}
}
