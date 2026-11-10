package black.samsungtvclient.model;

import black.samsungtvclient.net.Host;
import java.net.URI;

/** A MediaRenderer found on the network. {@code model} reads {@code unknown} if it doesn't give one. */
public record RendererInfo(String name, String model, Host ip, URI descriptionUrl) {}
