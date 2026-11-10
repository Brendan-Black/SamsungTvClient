package black.samsungtvclient.api;

/** Asked a TV for something its generation, or this library's support for it, doesn't offer. */
public final class UnsupportedCapabilityException extends UnsupportedOperationException {
	private static final long serialVersionUID = 1L;

	private final Generation generation;
	private final Capability capability;

	public UnsupportedCapabilityException(Generation generation, Capability capability) {
		super(message(generation, capability));
		this.generation = generation;
		this.capability = capability;
	}

	public Generation generation() {
		return generation;
	}

	public Capability capability() {
		return capability;
	}

	private static String message(Generation g, Capability c) {
		return g.offers().contains(c)
			? "this library doesn't implement " + c.description() + " for " + g.label() + " TVs yet"
			: g.label() + " TVs don't offer " + c.description();
	}
}
