# Security policy

## Remote links are credentials

A ZCode Remote URL contains authorization material. Anyone who obtains a still-valid link may be able to operate the associated ZCode desktop window. Do not paste a real link into an issue, pull request, screenshot, test fixture, crash report, or chat message.

If a link may have leaked, refresh the QR code or press **Stop** in the ZCode desktop Remote dialog. Closing this Android app does not revoke the link.

## Design rules

- Only exact `https://zcode.z.ai/remote/v3` and `/remote/v4` entry URLs are accepted.
- Cleartext traffic and mixed content are disabled.
- TLS errors are cancelled; there is no “continue anyway” path.
- The WebView has no JavaScript-to-native bridge and no injected scripts.
- File access, content access, popups, geolocation, microphone, and webpage camera requests are denied.
- Stored URLs are encrypted with an Android Keystore AES-GCM key and excluded from backup.
- If Keystore encryption fails, the URL is kept only in process memory; it is never downgraded to plaintext storage.
- Production code must not log URLs, queries, cookies, page content, or WebView console output.

## Reporting a vulnerability

Please avoid public issues when a report contains a real Remote URL or other credential. Reproduce with fake values such as `sid=test&hash=test&t=1700000000`, redact logs, and contact the maintainer privately through the repository owner profile.
