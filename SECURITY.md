# Security

## Reporting a vulnerability

Report security issues through GitHub private vulnerability reporting:

https://github.com/WanManDu/grokbot-alarm/security/advisories/new

Do not open a public GitHub issue for a security report.

## Supported versions

Protocol v2, shipped in app 0.5.0 and the matching CLI, is the supported protocol. Protocol v1 messages are unsigned and are ignored. Update the app and re-run the installer. Re-pairing is the migration.

## What this project does not do

The relay (ntfy) is not a trusted party. See the Privacy and security section of the README for what it can still observe. Branch protection, secret scanning, Dependabot alerts, the private-reporting toggle, and the release keystore are owner settings and are not turned on by a pull request.
