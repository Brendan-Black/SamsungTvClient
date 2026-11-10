package black.samsungtvclient.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Wake-on-LAN: the "magic packet" that turns a sleeping device on. The TV only listens for it with
 * Settings > General > Network > Expert Settings > Power On with Mobile switched on.
 */
public final class WakeOnLan {
	private WakeOnLan() {}

	/** 6 bytes of 0xFF, then the MAC 16 times. */
	public static byte[] packet(MacAddress mac) {
		byte[] address = mac.bytes();
		byte[] packet = new byte[6 + 16 * 6];
		for (int i = 0; i < 6; i++) { packet[i] = (byte) 0xFF; }
		for (int i = 0; i < 16; i++) { System.arraycopy(address, 0, packet, 6 + i * 6, 6); }
		return packet;
	}

	/**
	 * Broadcasts the packet on every network this PC is on (to ports 9 and 7, a few times: it's UDP).
	 *
	 * @throws IOException if no network would carry it at all
	 */
	public static void wake(MacAddress mac) throws IOException {
		byte[] packet = packet(mac);
		Set<InetAddress> targets = new LinkedHashSet<>();
		targets.add(InetAddress.getByName("255.255.255.255"));
		for (NetworkInterface ni : NetworkInterface.networkInterfaces().toList()) {
			if (!ni.isUp() || ni.isLoopback()) { continue; }
			for (InterfaceAddress a : ni.getInterfaceAddresses()) { if (a.getBroadcast() != null) { targets.add(a.getBroadcast()); } }
		}
		boolean sent = false;
		IOException lastRefusal = null;
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.setBroadcast(true);
			for (int round = 0; round < 3; round++) {
				for (InetAddress target : targets) {
					for (int port : new int[] {9, 7}) {
						try {
							socket.send(new DatagramPacket(packet, packet.length, target, port));
							sent = true;
						} catch (IOException e) {
							lastRefusal = e; // that network refused a broadcast; the others may carry it
						}
					}
				}
			}
		}
		if (!sent) { throw new IOException("no network on this PC would broadcast the Wake-on-LAN packet", lastRefusal); }
	}
}
