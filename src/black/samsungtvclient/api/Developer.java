package black.samsungtvclient.api;

import java.io.IOException;
import java.util.Map;

/** Installing your own apps (on Tizen: Developer Mode on, with this PC as Host PC IP). */
public interface Developer {
	/** Opens the developer connection; close it when done. */
	Session open() throws IOException;

	/** One developer connection to the TV. */
	interface Session extends AutoCloseable {
		/** The TV's unique id, which a distributor certificate names. */
		Duid duid() throws IOException;

		/** What the TV says about its platform (e.g. {@code platform_version}), as name-value pairs. */
		Map<String, String> capabilities() throws IOException;

		/** Installs a signed package (on Tizen, a {@code .wgt}) and returns the app id. */
		AppId install(byte[] signedPackage) throws IOException;

		void open(AppId appId) throws IOException;

		void uninstall(PackageId packageId) throws IOException;

		@Override
		void close() throws IOException;
	}
}
