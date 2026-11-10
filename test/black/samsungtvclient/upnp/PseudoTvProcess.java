package black.samsungtvclient.upnp;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A running {@code test/PseudoTv.java}, on a port it picked itself; ready as soon as it's constructed. */
final class PseudoTvProcess implements AutoCloseable {
	private static final Pattern READY = Pattern.compile("PseudoTv on (http://\\S+)");

	private final Process process;
	private final URI description;

	/** @param options what comes after the port on PseudoTv's command line */
	PseudoTvProcess(String... options) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "test/PseudoTv.java", "0"));
		command.addAll(List.of(options));
		process = new ProcessBuilder(command).redirectErrorStream(true).start();
		CompletableFuture<URI> ready = new CompletableFuture<>();
		Thread.ofVirtual().start(() -> readOutput(ready));
		try {
			description = ready.get(60, TimeUnit.SECONDS);
		} catch (ExecutionException | TimeoutException e) {
			process.destroyForcibly();
			throw new IOException("PseudoTv didn't start", e);
		}
	}

	URI description() {
		return description;
	}

	@Override
	public void close() {
		process.destroy();
		process.onExit().join();
	}

	/** Watches for the line saying it's up, and keeps draining so PseudoTv never blocks on a full pipe. */
	private void readOutput(CompletableFuture<URI> ready) {
		List<String> lines = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
			for (String line; (line = in.readLine()) != null;) {
				lines.add(line);
				Matcher m = READY.matcher(line);
				if (m.find()) { ready.complete(URI.create(m.group(1))); }
			}
		} catch (IOException e) {
			ready.completeExceptionally(e);
		}
		ready.completeExceptionally(new IOException("PseudoTv exited: " + lines));
	}
}
