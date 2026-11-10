package black.samsungtvclient.api;

/** The TV's API {@link Generation} is one this library recognises but doesn't implement. */
public final class UnsupportedGenerationException extends UnsupportedOperationException {
	private static final long serialVersionUID = 1L;

	private final Generation generation;

	public UnsupportedGenerationException(Generation generation) {
		super("the TV speaks the " + generation.title() + " API of " + generation.years() + ", which this library doesn't implement yet ("
			+ generation.protocol() + ")");
		this.generation = generation;
	}

	public Generation generation() {
		return generation;
	}
}
