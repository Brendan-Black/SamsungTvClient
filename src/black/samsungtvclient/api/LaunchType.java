package black.samsungtvclient.api;

/** How the TV starts an app; which one works depends on the app ({@link App#launchType()}). */
public enum LaunchType {
	DEEP_LINK, NATIVE_LAUNCH
}
