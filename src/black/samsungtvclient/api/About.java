package black.samsungtvclient.api;

import java.io.IOException;

/** What the TV says about itself. */
public interface About {
	TvInfo info() throws IOException;
}
