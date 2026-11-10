package black.samsungtvclient.detect;

import black.samsungtvclient.api.Generation;
import black.samsungtvclient.net.Host;
import java.util.Optional;

/** Works out which API {@link Generation} the Samsung TV at an address speaks. */
public interface GenerationDetector {
	/** The TV's generation, or empty if nothing there answers like a Samsung TV (or it's off). */
	Optional<Generation> detect(Host host);
}
