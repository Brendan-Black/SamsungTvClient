package black.samsungtvclient.tizen;

import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.api.AppId;
import black.samsungtvclient.api.PackageId;
import black.samsungtvclient.sdb.Sdb;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs, opens and removes signed web apps over the developer connection, with the TV's own commands, as Tizen
 * Studio does on TVs: the package goes to the tools folder the TV names, then {@code 0 vd_appinstall}.
 *
 * <p>SDB takes a command as one string, with nothing to bind arguments to. Every value that goes into one is a typed id
 * or a checked path, and neither can hold a space or a shell character.
 */
public final class AppInstaller {
	/** The capability that names the tools folder, as the TV reports it. */
	static final String TOOL_PATH = "sdk_toolpath";

	private static final Pattern FINISHED = Pattern.compile("spend time");
	private static final Pattern OPENED = Pattern.compile("launch|spend time|fail", Pattern.CASE_INSENSITIVE);
	private static final Pattern SAFE_PATH = Pattern.compile("/[A-Za-z0-9._/-]*");
	private static final Pattern APPLICATION = Pattern.compile("<tizen:application\\b[^>]*>");
	private static final Duration INSTALL_WAIT = Duration.ofMinutes(2);

	/** A web app's ids, from its config.xml: the package and the app ({@code package.Name}). */
	public record AppIds(PackageId packageId, AppId appId) {
		public AppIds {
			if (!appId.value().startsWith(packageId.value() + ".")) {
				throw new IllegalArgumentException("app id " + appId + " doesn't start with its package " + packageId);
			}
		}
	}

	private AppInstaller() {}

	public static AppIds ids(byte[] wgt) throws IOException {
		byte[] config = WidgetSigner.unzip(wgt).get("config.xml");
		if (config == null) { throw new IOException("not a Tizen web app: no config.xml"); }
		return ids(new String(config, UTF_8));
	}

	static AppIds ids(String configXml) throws IOException {
		Matcher tag = APPLICATION.matcher(configXml);
		if (!tag.find()) { throw new IOException("config.xml has no <tizen:application id=... package=...>"); }
		Optional<String> id = attribute(tag.group(), "id");
		Optional<String> pkg = attribute(tag.group(), "package");
		if (id.isEmpty() || pkg.isEmpty()) { throw new IOException("<tizen:application> needs both id and package"); }
		try {
			return new AppIds(new PackageId(pkg.get()), new AppId(id.get()));
		} catch (IllegalArgumentException e) {
			throw new IOException("config.xml: the package must be 10 letters or digits and the app id start with it, as in "
				+ "package=\"AbCdE12345\" id=\"AbCdE12345.Cards\"", e);
		}
	}

	private static String toolPath(Map<String, String> capabilities) throws IOException {
		String path = capabilities.getOrDefault(TOOL_PATH, "/home/owner/share/tmp/sdk_tools").replaceAll("/+$", "");
		if (!SAFE_PATH.matcher(path).matches()) { throw new IOException("the TV named an unusable tools folder: " + path); }
		return path;
	}

	/** Copies the package over and installs it; returns what the TV said. */
	public static String install(Sdb sdb, Map<String, String> capabilities, byte[] wgt) throws IOException {
		AppIds ids = ids(wgt);
		String remote = toolPath(capabilities) + "/" + ids.packageId() + ".wgt";
		sdb.push(remote, wgt);
		String out = sdb.shell("0 vd_appinstall " + ids.packageId() + " " + remote, FINISHED, INSTALL_WAIT).strip();
		if (!out.contains("install completed")) {
			String hint = out.contains("different certificate") || out.contains("signature")
				? "\nIf an older copy was signed by another author certificate, remove it first: tv uninstall " + ids.packageId()
				: "";
			throw new IOException("the TV didn't install it. It said:\n" + (out.isEmpty() ? "(nothing)" : out) + hint);
		}
		return out;
	}

	/** Opens an installed app by its app id. */
	public static void open(Sdb sdb, AppId appId) throws IOException {
		sdb.shell("0 was_execute " + appId, OPENED, Duration.ofSeconds(20));
	}

	public static String uninstall(Sdb sdb, PackageId packageId) throws IOException {
		String out = sdb.shell("0 vd_appuninstall " + packageId, FINISHED, INSTALL_WAIT).strip();
		if (!out.contains("uninstall completed")) { throw new IOException("the TV didn't remove " + packageId + ". It said:\n" + out); }
		return out;
	}

	private static Optional<String> attribute(String tag, String name) {
		Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
		return m.find() ? Optional.of(m.group(1)) : Optional.empty();
	}
}
