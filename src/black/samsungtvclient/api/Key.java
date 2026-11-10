package black.samsungtvclient.api;

import java.util.Locale;
import java.util.Map;

/**
 * Remote-control keys, by their short names. The TV takes many
 * more {@code KEY_...} codes than these; {@link #code} passes any of them through unchanged.
 */
public enum Key {
	POWER("KEY_POWER", "power (on from standby, or off)"),
	HOME("KEY_HOME", "Smart Hub home"),
	SOURCE("KEY_SOURCE", "input source list"),
	MENU("KEY_MENU", "settings menu"),
	GUIDE("KEY_GUIDE", "programme guide"),
	INFO("KEY_INFO", "info banner"),
	TOOLS("KEY_TOOLS", "tools / options"),
	UP("KEY_UP", "arrow up"),
	DOWN("KEY_DOWN", "arrow down"),
	LEFT("KEY_LEFT", "arrow left"),
	RIGHT("KEY_RIGHT", "arrow right"),
	ENTER("KEY_ENTER", "OK / select"),
	RETURN("KEY_RETURN", "back"),
	EXIT("KEY_EXIT", "exit the app"),
	VOLUP("KEY_VOLUP", "volume up"),
	VOLDOWN("KEY_VOLDOWN", "volume down"),
	MUTE("KEY_MUTE", "mute on/off"),
	CHUP("KEY_CHUP", "channel up"),
	CHDOWN("KEY_CHDOWN", "channel down"),
	PRECH("KEY_PRECH", "previous channel"),
	CH_LIST("KEY_CH_LIST", "channel list"),
	PLAY("KEY_PLAY", "play"),
	PAUSE("KEY_PAUSE", "pause"),
	STOP("KEY_STOP", "stop"),
	FF("KEY_FF", "fast forward"),
	REWIND("KEY_REWIND", "rewind"),
	RED("KEY_RED", "red"),
	GREEN("KEY_GREEN", "green"),
	YELLOW("KEY_YELLOW", "yellow"),
	BLUE("KEY_CYAN", "blue"),
	HDMI("KEY_HDMI", "next HDMI input"),
	TV("KEY_TV", "live TV"),
	CAPTION("KEY_CAPTION", "subtitles"),
	PICTURE_SIZE("KEY_PICTURE_SIZE", "picture size"),
	SLEEP("KEY_SLEEP", "sleep timer");

	private static final Map<String, Key> ALIASES = Map.ofEntries(
		Map.entry("OK", ENTER), Map.entry("SELECT", ENTER), Map.entry("BACK", RETURN),
		Map.entry("VOL+", VOLUP), Map.entry("VOL-", VOLDOWN), Map.entry("CH+", CHUP), Map.entry("CH-", CHDOWN),
		Map.entry("FASTFORWARD", FF), Map.entry("REW", REWIND), Map.entry("CYAN", BLUE), Map.entry("SUBTITLES", CAPTION));

	private final KeyCode code;
	private final String description;

	Key(String code, String description) {
		this.code = new KeyCode(code);
		this.description = description;
	}

	public KeyCode code() {
		return code;
	}

	public String description() {
		return description;
	}

	/**
	 * The {@code KEY_...} code for a short name ("home", "ok", "vol+", "hdmi2"), a digit, or any raw
	 * {@code KEY_...} code, which is passed through as the TV may know keys this list doesn't.
	 *
	 * @throws IllegalArgumentException if {@code name} is none of those
	 */
	public static KeyCode code(String name) {
		String n = name.strip().toUpperCase(Locale.ROOT).replace('-', '_');
		if (n.matches("KEY_[A-Z0-9_]+")) { return new KeyCode(n); }
		String raw = name.strip().toUpperCase(Locale.ROOT);
		if (ALIASES.containsKey(raw)) { return ALIASES.get(raw).code; }
		if (n.matches("[0-9]|HDMI[1-4]")) { return new KeyCode("KEY_" + n); }
		for (Key k : values()) { if (k.name().equals(n)) { return k.code; } }
		throw new IllegalArgumentException("unknown key: " + name + " (`tv keys` lists them; raw KEY_... codes work too)");
	}
}
