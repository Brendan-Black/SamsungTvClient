#!/bin/sh
# Runs the tv command, rebuilding when the sources have changed:  ./tv.sh info | ./tv.sh key home | ./tv.sh browse <url>
set -eu
root=$(cd "$(dirname "$0")" && pwd)
"$root/gradlew" -q -p "$root" installDist >&2
exec "$root/build/install/tv/bin/tv" "$@"
