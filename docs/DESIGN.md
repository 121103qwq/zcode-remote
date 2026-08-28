# Design

## Product boundary

ZLink Remote is a secure native container for the official ZCode Remote web app. The desktop remains the runtime; the APK never opens projects or executes commands by itself.

The official web page already owns protocol compatibility, permission prompts, task state, and reconnect behavior. Keeping those responsibilities in the official page avoids a fragile reimplementation of an unpublished protocol.

## Components

| Component | Responsibility |
|---|---|
| `MainActivity` | Explicit paste/share import, scan launch, validation preview, recent connections |
| `ScannerActivity` | CameraX preview and local ZXing QR decoding |
| `RemoteActivity` | One hardened WebView, native progress/errors, file chooser, back/reload behavior |
| `RemoteUrlPolicy` | One shared allowlist for every incoming URL and top-level navigation |
| `SessionStore` | Six encrypted recent credentials; no conversation cache |
| `CredentialCipher` | Android Keystore AES-GCM key lifecycle |

## Invariants and proof obligations

| Invariant | Observable proof |
|---|---|
| An attacker cannot smuggle an arbitrary origin into the WebView | Unit tests reject lookalike hosts, user-info, ports, schemes, fragments and incomplete credentials |
| Signed query bytes are not changed | Unit test verifies the accepted URL is returned byte-for-byte after outer whitespace removal |
| Saved preferences do not contain a plaintext bearer URL | `SessionStore` writes only AES-GCM ciphertext; device test should inspect app preferences |
| A web page cannot invoke native app methods | There is no JS bridge or injected message listener |
| TLS failure cannot be bypassed | `onReceivedSslError` always calls `cancel()` and displays an error |
| A renderer crash does not crash the whole app | `onRenderProcessGone` destroys the dead WebView and exposes a reload path |
| Camera denial does not block the product | Scanner closes with an explanation; paste remains available |

## Deliberate omissions in 0.1.0

- No private protocol reimplementation
- No DOM mutation or custom floating composer
- No background service, fake page visibility, reply scraping, or notification bridge
- No multiple simultaneous WebViews
- No credential export or “copy saved URL” button
- No fixed promise about link lifetime

These are omissions, not hidden fallbacks. A future change must preserve the URL, origin, credential-storage, and TLS invariants above.
