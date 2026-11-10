package black.samsungtvclient.net;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;

/** SSDP discovery: multicast an M-SEARCH and collect the LOCATION URLs of devices that answer. */
public final class Ssdp {
	public static final String MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";

	private static final InetSocketAddress GROUP = new InetSocketAddress("239.255.255.250", 1900);

	private Ssdp() {}

	/**
	 * Searches every multicast-capable IPv4 interface at once (Windows otherwise sends only on the
	 * default-route adapter, which may be a VPN or virtual switch rather than the Wi-Fi).
	 *
	 * @return distinct description URLs, in the order they answered
	 */
	public static List<URI> search(String searchTarget, Duration window) throws IOException {
		long mx = Math.clamp(window.toSeconds(), 1, 5);
		byte[] request = ("M-SEARCH * HTTP/1.1\r\n"
			+ "HOST: 239.255.255.250:1900\r\n"
			+ "MAN: \"ssdp:discover\"\r\n"
			+ "MX: " + mx + "\r\n"
			+ "ST: " + searchTarget + "\r\n"
			+ "\r\n").getBytes(US_ASCII);

		Set<URI> found = Collections.synchronizedSet(new LinkedHashSet<>());
		long deadline = System.nanoTime() + window.toNanos();
		List<MulticastSocket> sockets = openSockets();
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (MulticastSocket socket : sockets) { pool.execute(() -> listen(socket, request, deadline, found)); }
		} finally {
			sockets.forEach(MulticastSocket::close);
		}
		synchronized (found) {
			return List.copyOf(found);
		}
	}

	private static void listen(MulticastSocket socket, byte[] request, long deadline, Set<URI> found) {
		try {
			DatagramPacket out = new DatagramPacket(request, request.length, GROUP);
			socket.send(out);
			socket.send(out); // UDP is lossy; a second copy is cheap insurance
			byte[] buf = new byte[2048];
			while (true) {
				long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
				if (remainingMs <= 0) { return; }
				socket.setSoTimeout((int) remainingMs);
				DatagramPacket in = new DatagramPacket(buf, buf.length);
				try {
					socket.receive(in);
				} catch (SocketTimeoutException e) {
					return;
				}
				// Anyone can answer an M-SEARCH; only trust a LOCATION on the host that sent the reply.
				location(new String(in.getData(), 0, in.getLength(), US_ASCII))
					.filter(l -> in.getAddress().getHostAddress().equals(l.getHost()))
					.ifPresent(found::add);
			}
		} catch (IOException e) {
			// This interface can't multicast (or went away); the others may still answer.
		}
	}

	private static List<MulticastSocket> openSockets() throws IOException {
		List<MulticastSocket> sockets = new ArrayList<>();
		for (NetworkInterface ni : NetworkInterface.networkInterfaces().toList()) {
			try {
				if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) { continue; }
				for (InetAddress addr : ni.inetAddresses().toList()) {
					if (!(addr instanceof Inet4Address)) { continue; }
					MulticastSocket socket = new MulticastSocket(new InetSocketAddress(addr, 0));
					socket.setNetworkInterface(ni);
					socket.setTimeToLive(4);
					sockets.add(socket);
				}
			} catch (IOException e) {
				// skip interfaces we can't bind to
			}
		}
		if (sockets.isEmpty()) { sockets.add(new MulticastSocket(0)); }
		return sockets;
	}

	static Optional<URI> location(String response) {
		for (String line : response.split("\r?\n")) {
			int colon = line.indexOf(':');
			if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("LOCATION")) {
				try {
					return Optional.of(URI.create(line.substring(colon + 1).trim()));
				} catch (IllegalArgumentException e) {
					return Optional.empty(); // a garbled answer is the same as no answer
				}
			}
		}
		return Optional.empty();
	}
}
