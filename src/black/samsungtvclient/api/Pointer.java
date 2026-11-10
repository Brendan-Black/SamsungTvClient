package black.samsungtvclient.api;

import java.io.IOException;

/** The pointer the TV's browser shows. */
public interface Pointer {
	void move(int dx, int dy) throws IOException;

	void click() throws IOException;
}
