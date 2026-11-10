package black.samsungtvclient.api;

/** How far this library supports a {@link Generation}. */
public enum Support {
	/** Implemented and tried on a real TV. */
	VERIFIED,
	/** Implemented against the protocol as documented and as the tests' fakes speak it, not yet tried on a real TV. */
	UNTESTED,
	/** Described here, so it can be recognised and named, but not implemented. */
	NOT_IMPLEMENTED
}
