package black.samsungtvclient.api;

import black.samsungtvclient.util.Nullable;
import java.util.Optional;

/**
 * One app's state. {@code visible} means it's on screen; {@code running} that it's loaded, maybe in the background.
 *
 * @param version null if the TV doesn't say
 */
public record AppStatus(AppId id, String name, boolean running, boolean visible, @Nullable String version) {
	public Optional<String> knownVersion() {
		return Optional.ofNullable(version);
	}
}
