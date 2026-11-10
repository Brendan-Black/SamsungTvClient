package black.samsungtvclient.api;

import black.samsungtvclient.util.HttpUrl;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** The TV's apps. */
public interface Apps {
	/** The apps installed. (Built-in ones such as the browser may not be listed but launch all the same.) */
	List<App> installed() throws IOException;

	/**
	 * Starts an app.
	 *
	 * @throws IOException if the TV says it couldn't (no such app, or the wrong launch type for it)
	 */
	void launch(AppId appId, LaunchType type) throws IOException;

	/**
	 * Starts an app and passes it {@code metaTag}, a URL or deep link it understands.
	 *
	 * @throws IOException if the TV says it couldn't (no such app, or the wrong launch type for it)
	 */
	void launch(AppId appId, LaunchType type, String metaTag) throws IOException;

	/** Opens {@code url} in the TV's web browser. */
	void browse(HttpUrl url) throws IOException;

	/** The app's state, or empty if the TV has no app with this id. */
	Optional<AppStatus> status(AppId appId) throws IOException;

	void close(AppId appId) throws IOException;
}
