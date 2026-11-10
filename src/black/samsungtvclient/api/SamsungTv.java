package black.samsungtvclient.api;

import black.samsungtvclient.net.Host;
import java.io.IOException;
import java.util.Set;

/**
 * One Samsung TV, whatever its {@link Generation}, as the things it can be asked to do. Each capability is its own
 * interface; a generation's implementation overrides the ones its protocol has, and the rest throw
 * {@link UnsupportedCapabilityException}. {@link #capabilities()} says which are there without trying them.
 *
 * <p>Getting a capability may connect (and pair, the first time), hence the {@link IOException}. Connections stay
 * open until {@link #close()}.
 *
 * <p>A TV can be reached other ways than its local API, too: Samsung's SmartThings cloud API, for example, covers
 * TVs from 2017 on through an account. That would be another implementation of this interface, not a generation.
 */
public interface SamsungTv extends AutoCloseable {
	/** The TV's address. */
	Host host();

	Generation generation();

	/** What this TV, through this implementation, can do. */
	Set<Capability> capabilities();

	default boolean supports(Capability capability) {
		return capabilities().contains(capability);
	}

	default About about() throws IOException {
		throw unsupported(Capability.ABOUT);
	}

	default Pairing pairing() throws IOException {
		throw unsupported(Capability.PAIRING);
	}

	default RemoteControl remote() throws IOException {
		throw unsupported(Capability.REMOTE_KEYS);
	}

	default TextInput textInput() throws IOException {
		throw unsupported(Capability.TEXT);
	}

	default Pointer pointer() throws IOException {
		throw unsupported(Capability.POINTER);
	}

	default Events events() throws IOException {
		throw unsupported(Capability.EVENTS);
	}

	default Apps apps() throws IOException {
		throw unsupported(Capability.APPS);
	}

	default DialApps dial() throws IOException {
		throw unsupported(Capability.DIAL);
	}

	default MediaPlayer media() throws IOException {
		throw unsupported(Capability.MEDIA);
	}

	default Volume volume() throws IOException {
		throw unsupported(Capability.VOLUME);
	}

	default Developer developer() throws IOException {
		throw unsupported(Capability.DEVELOPER);
	}

	/** Closes whatever connections are open. */
	@Override
	void close();

	private UnsupportedCapabilityException unsupported(Capability capability) {
		return new UnsupportedCapabilityException(generation(), capability);
	}
}
