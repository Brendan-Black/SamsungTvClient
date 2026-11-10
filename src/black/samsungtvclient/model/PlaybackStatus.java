package black.samsungtvclient.model;

/** A snapshot of what the TV is doing: transport state, position, and audio. */
public record PlaybackStatus(TransportState state, PositionInfo position, int volume, boolean muted) {}
