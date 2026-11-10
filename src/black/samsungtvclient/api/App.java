package black.samsungtvclient.api;

/** An app installed on the TV. On Tizen, {@code type} 2 is a web app (launched by deep link) and 4 a native one. */
public record App(AppId id, String name, int type) {
	private static final int WEB_APP = 2;

	public LaunchType launchType() {
		return type == WEB_APP ? LaunchType.DEEP_LINK : LaunchType.NATIVE_LAUNCH;
	}
}
