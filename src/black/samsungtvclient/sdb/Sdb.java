package black.samsungtvclient.sdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.net.Host;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * SDB, the TV's developer connection (Samsung's fork of Android's ADB), as far as installing apps needs it.
 *
 * <p>It answers only in Developer Mode, and only the Host PC IP set there. Packets are ADB's: a 24-byte little-endian
 * header (command, two arguments, data length, a byte-sum checksum, command XOR all ones) and the data. Streams open
 * with OPEN; every WRTE is answered with OKAY before the next one may follow. sdbd drops the connection at the first
 * malformed packet.
 */
public final class Sdb implements AutoCloseable {
	public static final int PORT = 26101;
	static final int MAX_DATA = 4096; // what this side offers; each packet keeps within both sides' maximum
	private static final int VERSION = 0x01000000;
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

	private static final int CNXN = command("CNXN");
	private static final int OPEN = command("OPEN");
	private static final int OKAY = command("OKAY");
	private static final int WRTE = command("WRTE");
	private static final int CLSE = command("CLSE");

	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private final String banner;
	private final int maxData;
	private int nextId = 1;
	private final Map<Integer, Integer> okay = new HashMap<>(); // our stream id -> theirs, per OKAY not yet consumed
	private final Map<Integer, ByteArrayOutputStream> inbox = new HashMap<>();
	private final Set<Integer> closed = new HashSet<>();

	private Sdb(Socket socket) throws IOException {
		this.socket = socket;
		this.in = socket.getInputStream();
		this.out = socket.getOutputStream();
		send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(UTF_8));
		Packet p = read();
		if (p.command != CNXN) { throw new IOException("the TV answered " + name(p.command) + " instead of connecting"); }
		maxData = Math.min(p.arg1, MAX_DATA);
		banner = new String(p.data, UTF_8).replace("\0", "").strip();
	}

	public static Sdb connect(Host host) throws IOException {
		return connect(host, PORT);
	}

	public static Sdb connect(Host host, int port) throws IOException {
		Socket s = new Socket();
		try {
			s.connect(new InetSocketAddress(host.value(), port), (int) CONNECT_TIMEOUT.toMillis());
			s.setSoTimeout((int) READ_TIMEOUT.toMillis());
			return new Sdb(s);
		} catch (IOException e) {
			s.close();
			throw new IOException("no developer connection to " + host + ":" + port + " (" + e.getMessage() + "). On the TV: "
				+ "Apps, 1 2 3 4 5, Developer Mode on with this PC's address as Host PC IP, then hold Power to restart it", e);
		}
	}

	/** What the TV says it is, e.g. {@code device::UN75NU8000::0}. */
	public String banner() {
		return banner;
	}

	/** The TV's {@code capability:} list: platform_version, sdk_toolpath, and so on. */
	public Map<String, String> capabilities() throws IOException {
		String text = readUntil(open("capability:"), _ -> false, Duration.ofSeconds(5));
		Map<String, String> caps = new LinkedHashMap<>();
		for (String line : text.split("\n")) {
			int colon = line.indexOf(':');
			if (colon > 0) { caps.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip()); }
		}
		return Collections.unmodifiableMap(caps);
	}

	/**
	 * Runs a shell command (on a TV, only Samsung's own: {@code 0 getduid}, {@code 0 vd_appinstall ...}) and returns its
	 * output, once {@code until} appears in it, the TV closes the stream, or {@code wait} runs out.
	 */
	public String shell(String command, Pattern until, Duration wait) throws IOException {
		return readUntil(open("shell:" + command), t -> until.matcher(t).find(), wait);
	}

	/** Runs a shell command and returns its output, once the TV closes the stream or {@code wait} runs out. */
	public String shell(String command, Duration wait) throws IOException {
		return readUntil(open("shell:" + command), _ -> false, wait);
	}

	/** Copies {@code data} to {@code path} on the TV (ADB's sync SEND), and checks that the TV took it. */
	public void push(String path, byte[] data) throws IOException {
		int[] stream = open("sync:");
		byte[] spec = (path + "," + 0100644).getBytes(UTF_8);
		write(stream, syncRequest("SEND", spec.length, spec));
		int step = maxData - 8;
		for (int i = 0; i < data.length; i += step) {
			int n = Math.min(step, data.length - i);
			byte[] part = new byte[n];
			System.arraycopy(data, i, part, 0, n);
			write(stream, syncRequest("DATA", n, part));
		}
		write(stream, syncRequest("DONE", (int) (System.currentTimeMillis() / 1000), new byte[0]));
		byte[] answer = readBytesUntil(stream, b -> b.length >= 8, Duration.ofSeconds(60));
		if (answer.length < 4 || !new String(answer, 0, 4, UTF_8).equals("OKAY")) {
			String why = answer.length > 8 ? new String(answer, 8, answer.length - 8, UTF_8) : "no answer";
			throw new IOException("the TV didn't take " + path + ": " + why);
		}
		send(WRTE, stream[0], stream[1], syncRequest("QUIT", 0, new byte[0]));
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}

	// ---- streams -------------------------------------------------------------------------------------

	/** Opens a service; returns {our id, their id}. */
	private int[] open(String service) throws IOException {
		int local = nextId++;
		send(OPEN, local, 0, (service + "\0").getBytes(UTF_8));
		while (!okay.containsKey(local)) {
			if (closed.contains(local)) { throw new IOException("the TV refused " + service); }
			pump();
		}
		return new int[] {local, okay.remove(local)};
	}

	private void write(int[] stream, byte[] data) throws IOException {
		send(WRTE, stream[0], stream[1], data);
		while (!okay.containsKey(stream[0])) {
			if (closed.contains(stream[0])) { throw new IOException("the TV closed the stream"); }
			pump();
		}
		okay.remove(stream[0]);
	}

	private String readUntil(int[] stream, Predicate<String> done, Duration wait) throws IOException {
		return new String(readBytesUntil(stream, b -> done.test(new String(b, UTF_8)), wait), UTF_8);
	}

	private byte[] readBytesUntil(int[] stream, Predicate<byte[]> done, Duration wait) throws IOException {
		int local = stream[0];
		long deadline = System.nanoTime() + wait.toNanos();
		try {
			while (!closed.contains(local) && !done.test(received(local))) {
				long left = (deadline - System.nanoTime()) / 1_000_000;
				if (left <= 0) { break; }
				socket.setSoTimeout((int) Math.max(100, left));
				try {
					pump();
				} catch (SocketTimeoutException e) {
					break;
				}
			}
		} finally {
			socket.setSoTimeout((int) READ_TIMEOUT.toMillis());
		}
		ByteArrayOutputStream b = inbox.remove(local);
		return b == null ? new byte[0] : b.toByteArray();
	}

	private byte[] received(int local) {
		ByteArrayOutputStream b = inbox.get(local);
		return b == null ? new byte[0] : b.toByteArray();
	}

	/** Reads one packet and files it under its stream. */
	private void pump() throws IOException {
		Packet p = read();
		if (p.command == OKAY) {
			okay.put(p.arg1, p.arg0);
		} else if (p.command == WRTE) {
			inbox.computeIfAbsent(p.arg1, _ -> new ByteArrayOutputStream()).writeBytes(p.data);
			send(OKAY, p.arg1, p.arg0, new byte[0]);
		} else if (p.command == CLSE) { closed.add(p.arg1); }
	}

	// ---- packets -------------------------------------------------------------------------------------

	private record Packet(int command, int arg0, int arg1, byte[] data) {}

	private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
		out.write(header(command, arg0, arg1, data));
		out.write(data);
		out.flush();
	}

	static byte[] header(int command, int arg0, int arg1, byte[] data) {
		int sum = 0;
		for (byte b : data) { sum += b & 0xff; }
		return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
			.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command).array();
	}

	private Packet read() throws IOException {
		ByteBuffer h = ByteBuffer.wrap(in.readNBytes(24)).order(ByteOrder.LITTLE_ENDIAN);
		if (h.limit() < 24) { throw new EOFException("the TV closed the developer connection"); }
		int command = h.getInt();
		int arg0 = h.getInt();
		int arg1 = h.getInt();
		int length = h.getInt();
		if (length < 0 || length > 1 << 20) { throw new IOException("bad packet from the TV (" + length + " bytes)"); }
		byte[] data = in.readNBytes(length);
		if (data.length < length) { throw new EOFException("the TV closed the developer connection"); }
		return new Packet(command, arg0, arg1, data);
	}

	private static byte[] syncRequest(String id, int arg, byte[] data) {
		return ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN)
			.put(id.getBytes(UTF_8)).putInt(arg).put(data).array();
	}

	static int command(String name) {
		return ByteBuffer.wrap(name.getBytes(UTF_8)).order(ByteOrder.LITTLE_ENDIAN).getInt();
	}

	private static String name(int command) {
		return new String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array(), UTF_8);
	}
}
