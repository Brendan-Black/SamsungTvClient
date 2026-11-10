package black.samsungtvclient.util;

/**
 * A marker for a type that exists to hold and validate one shaped string: holding one is proof it has that shape.
 * "Find implementations" lists every shaped value in the library.
 */
public interface TypedString {
	/** The validated string, as it goes on the wire. */
	String value();
}
