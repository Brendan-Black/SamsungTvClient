package black.samsungtvclient.api;

import java.io.IOException;

/**
 * This client's standing with the TV. How a pairing is made differs by generation: an "Allow?" prompt (Orsay,
 * Tizen), a PIN on screen (2014-2015), with a token kept afterwards (Tizen since 2017).
 */
public interface Pairing {
	/** Whether this client has a pairing it can use without asking the TV again. */
	boolean paired() throws IOException;

	/** Pairs now, if needed: the TV may ask on screen, and this waits for the answer. */
	void pair() throws IOException;

	/** Forgets this client's side of the pairing (the TV keeps its own list). */
	void unpair() throws IOException;
}
