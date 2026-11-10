package black.samsungtvclient.api;

import java.io.IOException;

/** Text into the TV's on-screen keyboard (it only lands while the keyboard is open). */
public interface TextInput {
	void text(String text) throws IOException;
}
