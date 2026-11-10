# Security

## Reporting a problem

Please use [GitHub's private vulnerability reporting](https://github.com/Brendan-Black/SamsungTvClient/security/advisories/new)
rather than a public issue. Say what an attacker could do and how to reproduce it.

## What this project keeps, and where

| What | Where | Why it matters |
|---|---|---|
| Pairing tokens | `~/.samsungtv/tvs.properties` | Anyone holding one can drive that TV |
| TV certificate fingerprints and MAC addresses | the same file | Identify your TV |
| Author and distributor keys, and their keystore `password` | `~/.samsungtv/samsung/` | Sign apps for your TV. Losing `author.p12` means you can't update apps you installed |

These files are written atomically and readable only by you, on file systems that have permissions. Tokens, access tokens and
private keys have a redacted `toString()`, so they can't reach a log by accident. The password you sign in to Samsung with goes
only to Samsung, in your browser.

## How it trusts a TV

A Samsung TV's certificate is self-signed and names no host, so ordinary TLS validation can't apply. The client pins the
certificate's SHA-256 fingerprint the first time it pairs, as SSH does, and refuses a different one afterwards. **That first
connection is trust-on-first-use**: pair on a network you trust.

Anything on your LAN can answer discovery. A reply is only believed when its `LOCATION` is on the host that sent it, and device
descriptions are parsed with DTDs and external entities disabled. Use `--ip` if you'd rather not rely on discovery.

2016 TVs (`tizen-open`) and the REST, UPnP and DIAL interfaces of every TV are unauthenticated and unencrypted. That's the TV's
protocol, and this client can't change it.

## Tizen's public distributor certificate

For TVs on Tizen 4.0 and older, `tv install` signs with the public distributor certificate that ships with Tizen Studio. Its
password is published and it's meant for development. It says nothing about who wrote an app; that's the author certificate's job.
