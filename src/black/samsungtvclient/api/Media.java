package black.samsungtvclient.api;

import black.samsungtvclient.util.HttpUrl;
import black.samsungtvclient.util.Nullable;
import java.util.Optional;

/**
 * Something for the TV to play. Start from {@link #of}; what isn't given is worked out from the URL.
 *
 * @param url       where the TV fetches it
 * @param title     shown by the TV; null to use the URL's file name
 * @param mime      null to go by the URL's extension
 * @param subtitles a subtitle file the TV can fetch; null for none
 */
public record Media(HttpUrl url, @Nullable String title, @Nullable String mime, @Nullable HttpUrl subtitles) {
	public Media {
		title = title == null || title.isBlank() ? null : title.strip();
		mime = mime == null || mime.isBlank() ? null : mime.strip();
	}

	public static Media of(HttpUrl url) {
		return new Media(url, null, null, null);
	}

	public Media titled(String newTitle) {
		return new Media(url, newTitle, mime, subtitles);
	}

	public Media typed(String newMime) {
		return new Media(url, title, newMime, subtitles);
	}

	public Media subtitled(HttpUrl newSubtitles) {
		return new Media(url, title, mime, newSubtitles);
	}

	public Optional<String> knownTitle() {
		return Optional.ofNullable(title);
	}

	public Optional<String> knownMime() {
		return Optional.ofNullable(mime);
	}

	public Optional<HttpUrl> knownSubtitles() {
		return Optional.ofNullable(subtitles);
	}
}
