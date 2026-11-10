package black.samsungtvclient;

import black.samsungtvclient.model.ModelTest;
import black.samsungtvclient.net.NetTest;
import black.samsungtvclient.samsung.SamsungTest;
import black.samsungtvclient.sdb.SdbTest;
import black.samsungtvclient.tizen.CertificateServiceTest;
import black.samsungtvclient.tizen.TizenTest;
import black.samsungtvclient.upnp.EndToEndTest;
import black.samsungtvclient.upnp.UpnpTest;
import black.samsungtvclient.util.JsonTest;

/** Runs every test group; exits non-zero if any check failed. Run from the project directory. */
public final class AllTests {
	private AllTests() {}

	public static void main(String[] args) {
		Check.group("types", TypesTest::run);
		Check.group("model", ModelTest::run);
		Check.group("json", JsonTest::run);
		Check.group("net", NetTest::run);
		Check.group("upnp", UpnpTest::run);
		Check.group("cli", CliTest::run);
		Check.group("api (generations, connectors, detection)", ApiTest::run);
		Check.group("samsung (PseudoSamsung: REST, DIAL, remote channel)", SamsungTest::run);
		Check.group("sdb (PseudoSdb: developer connection, installs)", SdbTest::run);
		Check.group("tizen (certificate requests, signing, store)", TizenTest::run);
		Check.group("certificate service (canned replies, refusals)", CertificateServiceTest::run);
		Check.group("media (PseudoTv over UPnP)", EndToEndTest::run);
		System.exit(Check.summary());
	}
}
