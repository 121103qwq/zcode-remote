# Design

## Product boundary

ZLink Remote is a secure native container for the official ZCode Remote web app. The desktop remains the runtime; the APK never opens projects or executes commands by itself.

The official web page already owns protocol compatibility, permission prompts, task state, and reconnect behavior. Keeping those responsibilities in the official page avoids a fragile reimplementation of an unpublished protocol.

## Components

| Component | Responsibility |
|---|---|
| `MainActivity` | Compact dark connection bar, settings, explicit paste/share import, camera/image QR routing, validation preview, flat recent rows |
| `ScannerActivity` | CameraX preview and local ZXing QR decoding |
| `QrImageDecoder` | Bounded local decoding for one or more QR codes selected through Android Photo Picker |
| `RemoteActivity` | One immersive full-screen hardened WebView with no native overlay, error fallback, file chooser, system back gesture |
| `RemoteUrlPolicy` | One shared allowlist for every incoming URL and top-level navigation |
| `SessionStore` | Six encrypted recent credentials; no conversation cache |
| `StartupSessionPolicy` | Pure startup routing: designated session first, otherwise most recent; launcher-only gating |
| `CredentialCipher` | Android Keystore AES-GCM key lifecycle |

## Invariants and proof obligations

| Invariant | Observable proof |
|---|---|
| An attacker cannot smuggle an arbitrary origin into the WebView | Unit tests reject lookalike hosts, user-info, ports, schemes, fragments and incomplete credentials |
| A trusted page cannot escape by form POST or redirect | Main-frame request interception plus page-start, history and commit checks stop and hide an untrusted transition |
| Signed query bytes are not changed | Unit test verifies the accepted URL is returned byte-for-byte after outer whitespace removal |
| Saved preferences do not contain a plaintext bearer URL | `SessionStore` writes only AES-GCM ciphertext; device test should inspect app preferences |
| Startup routing cannot override an external share or trap the back gesture | Unit tests require a fresh `ACTION_MAIN` launcher start; restored, shared, and deep-link intents never auto-open |
| Android saved state does not serialize the bearer URL | Credential input and WebView state saving are disabled; screenshots and task previews are intentionally user-controlled |
| The bearer URL is not retained as an HTTP cache key after use | The WebView clears disk cache across load, finish, background and destruction paths; device tests search `app_webview` using fake credentials |
| A web page cannot invoke native app methods | There is no JS bridge or injected message listener |
| TLS failure cannot be bypassed | `onReceivedSslError` always calls `cancel()` and displays an error |
| A renderer crash does not crash the whole app | `onRenderProcessGone` destroys the dead WebView and exposes a reload path |
| Camera denial does not block the product | The same QR action offers Android Photo Picker; paste also remains available |
| A selected image cannot become an arbitrary file read | Only user-selected `content://` image URIs are accepted, decoded with bounded sampling, and never resolved to filesystem paths |

The WebView deliberately reconnects from the encrypted entry URL after an Activity/process
recreation instead of serializing browser history, because WebView saved state can contain the
credential URL. Volatile in-memory sessions survive configuration recreation while the process is
alive; after process death the app visibly asks for a new scan.

## Deliberate omissions in 0.2.1

- No private protocol reimplementation
- No DOM mutation or custom floating composer
- No background service, fake page visibility, reply scraping, or notification bridge
- No multiple simultaneous WebViews
- No credential export or “copy saved URL” button
- No fixed promise about link lifetime

These are omissions, not hidden fallbacks. A future change must preserve the URL, origin, credential-storage, and TLS invariants above.
