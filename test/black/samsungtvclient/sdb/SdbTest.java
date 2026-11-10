package black.samsungtvclient.sdb;

import static black.samsungtvclient.Check.eq;
import static black.samsungtvclient.Check.fails;
import static black.samsungtvclient.Check.ok;
import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.api.AppId;
import black.samsungtvclient.api.PackageId;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.tizen.AppInstaller;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The developer connection and app installs, against {@link PseudoSdb}. */
public final class SdbTest {
	private static final Host LOCAL = new Host("127.0.0.1");

	private SdbTest() {}

	public static void run() throws Exception {
		eq(0x4e584e43, Sdb.command("CNXN"), "command words are little-endian ASCII");
		byte[] h = Sdb.header(Sdb.command("OPEN"), 1, 0, new byte[] {(byte) 200, 100});
		eq(300, (h[16] & 0xff) | (h[17] & 0xff) << 8, "the checksum is a byte sum (of unsigned bytes), not a CRC");

		try (PseudoSdb tv = new PseudoSdb(); Sdb sdb = Sdb.connect(LOCAL, tv.port())) {
			eq("device::FAKE::0", sdb.banner(), "the TV's banner");
			Map<String, String> caps = sdb.capabilities();
			eq("4.0", caps.get("platform_version"), "capabilities");
			eq(PseudoSdb.DUID, sdb.shell("0 getduid", Duration.ofSeconds(5)).strip(), "a shell command's output");

			byte[] big = new byte[20_000]; // several DATA packets
			for (int i = 0; i < big.length; i++) { big[i] = (byte) i; }
			sdb.push("/tmp/x.bin", big);
			ok(Arrays.equals(big, tv.files().get("/tmp/x.bin")), "push sends the whole file, in pieces within the TV's maximum");

			byte[] wgt = wgt();
			AppInstaller.install(sdb, caps, wgt);
			ok(Arrays.equals(wgt, tv.files().get(PseudoSdb.TOOLS + "/TvClient01.wgt")), "the package goes to the tools folder the TV names");
			ok(tv.commands().contains("0 vd_appinstall TvClient01 " + PseudoSdb.TOOLS + "/TvClient01.wgt"), "installed with vd_appinstall");
			AppInstaller.open(sdb, new AppId("TvClient01.Cards"));
			ok(tv.commands().contains("0 was_execute TvClient01.Cards"), "opened with was_execute");

			tv.installFailure = "118012";
			IOException refused = fails(IOException.class, () -> AppInstaller.install(sdb, caps, wgt), "the TV refuses the package");
			ok(refused.getMessage().contains("118012"), "...and the error has what it said");
			tv.installFailure = null;
			AppInstaller.uninstall(sdb, new PackageId("TvClient01"));
			ok(tv.commands().contains("0 vd_appuninstall TvClient01"), "uninstall");

			int before = tv.commands().size();
			IOException folder = fails(IOException.class,
				() -> AppInstaller.install(sdb, Map.of("sdk_toolpath", "/tmp/x;reboot"), wgt), "a tools folder that would be a second command");
			ok(folder.getMessage().contains("unusable tools folder"), "the TV's own value is checked too: " + folder.getMessage());
			eq(before, tv.commands().size(), "...and nothing was run");
			eq(null, tv.badPacket, "every packet had a good checksum");
		}
		fails(IOException.class, () -> Sdb.connect(LOCAL, 1).close(), "no TV listening");
	}

	private static byte[] wgt() throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(b)) {
			z.putNextEntry(new ZipEntry("config.xml"));
			z.write(("<widget><tizen:application id=\"TvClient01.Cards\" package=\"TvClient01\"/></widget>").getBytes(UTF_8));
			z.closeEntry();
		}
		return b.toByteArray();
	}
}
