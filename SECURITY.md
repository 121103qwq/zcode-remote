# Security policy

## Remote links are credentials

A ZCode Remote URL contains authorization material. Anyone who obtains a still-valid link may be able to operate the associated ZCode desktop window. Do not paste a real link into an issue, pull request, screenshot, test fixture, crash report, or chat message.

If a link may have leaked, refresh the QR code or press **Stop** in the ZCode desktop Remote dialog. Closing this Android app does not revoke the link.

## Design rules

- Only exact `https://zcode.z.ai/remote/v3` and `/remote/v4` entry URLs are accepted.
- Cleartext traffic and mixed content are disabled.
- TLS errors are cancelled; there is no “continue anyway” path.
- Main-frame GET, POST, history, commit, and redirect transitions are checked against the exact
  `https://zcode.z.ai` origin; blocked POST targets receive no network request.
- The WebView has no JavaScript-to-native bridge. A fixed read-only expression polls only the
  trusted v4 page's `data-testid` controls for `running`, `idle`, or `unknown`; it does not return
  message text, task results, form values, cookies, storage, or the credential URL.
- File access, content access, popups, geolocation, microphone, and webpage camera requests are denied.
- File uploads use an app-owned `ACTION_OPEN_DOCUMENT` request and reject non-`content://`,
  unreadable, wrong-MIME, or excessive picker results.
- QR image import uses Android Photo Picker (with AndroidX fallback), accepts only the returned
  `content://` image, bounds decoded pixels, and performs ZXing recognition locally.
- Stored URLs are encrypted with an Android Keystore AES-GCM key and excluded from backup.
- If Keystore encryption fails, the URL is kept only in process memory; it is never downgraded to plaintext storage.
- Screenshots are user-enabled by design. A screenshot, screen recording, cast, or recent-task
  thumbnail may contain a QR code, bearer URL, or Remote page content; users must treat those images
  as credentials and avoid sharing them. Credential input and WebView state saving remain disabled.
  The WebView uses Android's server-directed default HTTP cache so official static assets can be
  reused. Credential input and WebView saved state remain disabled. **Clear local data** deletes
  WebView cache, storage, cookies, encrypted sessions, and recent connections.
- Automatic retry is limited to network and renderer failures. Expired links, TLS failures, unsafe
  navigation, and Safe Browsing hits never retry.
- Completion notifications contain only a generic status and the locally saved device name. The app
  does not place conversation text, task results, or Remote URL parameters in notifications.
- Production code must not log URLs, queries, cookies, page content, or WebView console output.

## Reporting a vulnerability

Please avoid public issues when a report contains a real Remote URL or other credential. Reproduce with fake values such as `sid=test&hash=test&t=1700000000`, redact logs, and contact the maintainer privately through the repository owner profile.
