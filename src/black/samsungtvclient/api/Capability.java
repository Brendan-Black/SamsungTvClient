package black.samsungtvclient.api;

/** Something a TV can be asked to do over the network; which ones a TV has depends on its {@link Generation}. */
public enum Capability {
	ABOUT("device information"),
	PAIRING("pairing"),
	REMOTE_KEYS("remote keys"),
	TEXT("text into the on-screen keyboard"),
	POINTER("the browser's pointer"),
	EVENTS("events from the TV"),
	APPS("apps: list, launch, status, close"),
	DIAL("DIAL apps (YouTube, Netflix)"),
	MEDIA("playing a URL (UPnP)"),
	VOLUME("volume and mute"),
	DEVELOPER("installing your own apps");

	private final String description;

	Capability(String description) {
		this.description = description;
	}

	public String description() {
		return description;
	}
}
