package black.samsungtvclient.sdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for a TV's sdbd, in process, answering like the UN75NU8000 did (2026-10-25): its banner and capabilities,
 * {@code 0 getduid}, sync pushes, and Samsung's install, open and uninstall commands. Like sdbd, it hangs up on a
 * packet whose checksum or magic is wrong.
 */
public final class PseudoSdb implements AutoCloseable {
	public static final String DUID = "TESTDUID00000";
	static final String TOOLS = "/home/owner/share/tmp/sdk_tools";

	private final Map<String, byte[]> files = new ConcurrentHashMap<>();
	private final List<String> commands = Collections.synchronizedList(new ArrayList<>());

	/** Set when a malformed packet arrived. */
	volatile String badPacket;

	/** What vd_appinstall says when it fails; null to succeed. */
	volatile String installFailure;

	private final List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
	private final ServerSocket server;
	private final Thread thread;

	public PseudoSdb() throws IOException {
		server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
		thread = Thread.ofVirtual().start(this::serve);
	}

	/** The files pushed, by path. */
	Map<String, byte[]> files() {
		return Map.copyOf(files);
	}

	/** The shell commands run, in order. */
	public List<String> commands() {
		return List.copyOf(commands);
	}

	public int port() {
		return server.getLocalPort();
	}

	@Override
	public void close() throws IOException {
		server.close();
		thread.interrupt();
		if (!unexpected.isEmpty()) { throw new IllegalStateException("no TV would have been sent: " + unexpected); }
	}

	private void serve() {
		while (!server.isClosed()) {
			try (Socket s = server.accept()) {
				new Connection(s.getInputStream(), s.getOutputStream()).run();
			} catch (IOException e) {
				// the client hung up, or we did over a bad packet; wait for the next
			}
		}
	}

	private final class Connection {
		private final InputStream in;
		private final OutputStream out;
		private int nextId = 100;
		private final Map<Integer, Sync> syncs = new HashMap<>(); // our stream id -> push in progress

		Connection(InputStream in, OutputStream out) {
			this.in = in;
			this.out = out;
		}

		void run() throws IOException {
			while (true) {
				ByteBuffer h = ByteBuffer.wrap(in.readNBytes(24)).order(ByteOrder.LITTLE_ENDIAN);
				if (h.limit() < 24) { return; }
				int command = h.getInt(), arg0 = h.getInt(), arg1 = h.getInt(), length = h.getInt(), sum = h.getInt(), magic = h.getInt();
				byte[] data = in.readNBytes(length);
				int actual = 0;
				for (byte b : data) { actual += b & 0xff; }
				if (magic != ~command || sum != actual) {
					badPacket = "checksum " + sum + " vs " + actual + ", magic " + magic;
					return; // sdbd drops the connection
				}
				String name = new String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array(), UTF_8);
				switch (name) {
					case "CNXN" -> send("CNXN", 0x01000000, 4096, "device::FAKE::0\0".getBytes(UTF_8));
					case "OPEN" -> open(arg0, new String(data, UTF_8).replace("\0", ""));
					case "WRTE" -> {
						send("OKAY", arg1, arg0, new byte[0]);
						Sync sync = syncs.get(arg1);
						if (sync != null) { sync.receive(data, arg0, arg1); }
					}
					default -> {} // OKAY and CLSE from the client
				}
			}
		}

		private void open(int theirs, String service) throws IOException {
			int ours = nextId++;
			send("OKAY", ours, theirs, new byte[0]);
			if (service.equals("sync:")) {
				syncs.put(ours, new Sync());
				return;
			}
			String reply;
			if (service.equals("capability:")) {
				reply = "secure_protocol:enabled\nfilesync_support:push\nsdk_toolpath:" + TOOLS + "\nprofile_name:tv\n"
					+ "platform_version:4.0\nsdbd_version:2.2.31\n";
			} else if (service.startsWith("shell:")) {
				reply = shell(service.substring(6));
			} else {
				unexpected.add(service);
				send("CLSE", 0, theirs, new byte[0]);
				return;
			}
			send("WRTE", ours, theirs, reply.getBytes(UTF_8));
			send("CLSE", ours, theirs, new byte[0]);
		}

		private String shell(String command) {
			commands.add(command);
			String[] w = command.split(" ");
			if (command.equals("0 getduid")) { return DUID + "\n"; }
			if (w.length == 4 && w[1].equals("vd_appinstall")) {
				String failure = installFailure != null ? installFailure : files.containsKey(w[3]) ? null : "118";
				return "install " + w[2] + "\npackage_path " + w[3] + "\napp_id[" + w[2] + "] install start\napp_id[" + w[2] + "] "
					+ (failure == null ? "install completed" : "install failed[" + failure + "]") + "\nspend time for wascmd is [5]ms\n";
			}
			if (w.length == 3 && w[1].equals("was_execute")) { return "launch app " + w[2] + "\n"; }
			if (w.length == 3 && w[1].equals("vd_appuninstall")) { return "app_id[" + w[2] + "] uninstall completed\nspend time for wascmd is [5]ms\n"; }
			unexpected.add(command);
			return "";
		}

		/** ADB's sync protocol, as far as SEND (DATA...) DONE and QUIT. */
		private final class Sync {
			private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			private String path;
			private final ByteArrayOutputStream file = new ByteArrayOutputStream();

			void receive(byte[] data, int theirs, int ours) throws IOException {
				buffer.writeBytes(data);
				byte[] b = buffer.toByteArray();
				int at = 0;
				while (b.length - at >= 8) {
					String id = new String(b, at, 4, UTF_8);
					int n = ByteBuffer.wrap(b, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
					int body = id.equals("DONE") || id.equals("QUIT") ? 0 : n;
					if (b.length - at - 8 < body) { break; }
					switch (id) {
						case "SEND" -> path = new String(b, at + 8, n, UTF_8).replaceFirst(",\\d+$", "");
						case "DATA" -> file.write(b, at + 8, n);
						case "DONE" -> {
							files.put(path, file.toByteArray());
							send("WRTE", ours, theirs, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put("OKAY".getBytes(UTF_8)).putInt(0).array());
						}
						case "QUIT" -> send("CLSE", ours, theirs, new byte[0]);
						default -> throw new IOException("unknown sync request " + id);
					}
					at += 8 + body;
				}
				byte[] rest = Arrays.copyOfRange(b, at, b.length);
				buffer.reset();
				buffer.writeBytes(rest);
			}
		}

		private void send(String command, int arg0, int arg1, byte[] data) throws IOException {
			out.write(Sdb.header(Sdb.command(command), arg0, arg1, data));
			out.write(data);
			out.flush();
		}
	}
}
