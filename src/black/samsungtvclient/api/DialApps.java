package black.samsungtvclient.api;

import java.io.IOException;
import java.util.Locale;

/** DIAL: apps such as YouTube and Netflix, started with something to play. */
public interface DialApps {
	/** The app's state. */
	Status status(DialAppName app) throws IOException;

	/** Starts the app. */
	void launch(DialAppName app) throws IOException;

	/** Starts the app with {@code payload}, e.g. {@code v=<video id>} for YouTube. */
	void launch(DialAppName app, String payload) throws IOException;

	void stop(DialAppName app) throws IOException;

	/** The states DIAL defines, plus {@link #UNKNOWN} for anything else a TV says. */
	enum AppState {
		RUNNING, STOPPED, HIDDEN, INSTALLABLE, UNKNOWN;

		/** From a DIAL {@code <state>}; installable apps say {@code installable=<url>}. */
		public static AppState parse(String state) {
			String s = state.strip().toLowerCase(Locale.ROOT);
			return switch (s.startsWith("installable") ? "installable" : s) {
				case "running" -> RUNNING;
				case "stopped" -> STOPPED;
				case "hidden" -> HIDDEN;
				case "installable" -> INSTALLABLE;
				default -> UNKNOWN;
			};
		}
	}

	/** An app's DIAL state. */
	record Status(String name, AppState state) {
		public boolean running() {
			return state == AppState.RUNNING;
		}
	}
}
