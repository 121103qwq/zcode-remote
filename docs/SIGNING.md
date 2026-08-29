# APK signing

ZLink Remote uses one long-lived signing identity for every stable APK. Android
accepts an in-place update only when the application ID, signing certificate,
and version progression are compatible.

## Identity contract

- Application ID: `io.github.xgy.zcoderemote`
- Signing job: `sign-stable` in `.github/workflows/android.yml`
- Protected GitHub Environment: `release`
- Keystore and password: GitHub Environment secrets only
- Key alias: `zlink-release`
- Expected certificate SHA-256: `11c0155f4dd8a5dfa243852a2a2f9ac4a2be287a75d36c815279f28d2bb9d338`

Do not commit a keystore, private key, password, or base64-encoded keystore.
Do not paste them into an issue, pull request, Actions log, or chat.

## One-time migration from v0.2.0 debug

The v0.2.0 debug APK was signed by a temporary GitHub-hosted runner key. That
private key no longer exists, so a stable-signed build cannot update it in
place. Uninstall that debug build once, then install the first stable APK.
Uninstalling clears the app's locally stored recent connections.

After that one-time reinstall, future stable APKs can update normally as long
as they use the same private key and a higher `versionCode`.

## Back up the signing identity

The fixed RSA-4096 signing identity was created on 2026-08-29. Its encrypted
PKCS#12 key bundle and password are stored as separate private recovery files.
They are not part of this repository.

Keep two encrypted offline backups in separate locations. Losing this private
key means existing installations cannot receive a normal update. Anyone who
obtains the private key and passwords can publish a forged update, so recovery
copies must not live in the repository or ordinary cloud folders.

The recovery bundle already contains a one-line Base64 encoding. To recreate it
from the PKCS#12 file when necessary:

```bash
openssl base64 -A -in zlink-release.p12 -out zlink-release.p12.b64
```

Export the public certificate and calculate its SHA-256 fingerprint:

```bash
keytool -exportcert \
  -alias zlink-release \
  -keystore zlink-release.p12 \
  -storetype PKCS12 \
  -rfc \
  -file zlink-release-cert.pem

openssl x509 \
  -in zlink-release-cert.pem \
  -noout \
  -fingerprint \
  -sha256
```

The fingerprint is public. Compare it exactly with the identity contract above
before enabling releases.

## Configure the GitHub `release` Environment

In **Repository settings → Environments**, create an Environment named
`release`. Restrict deployment branches to `main` and add required reviewers
where the GitHub plan supports them. A manual workflow dispatch also enters
this Environment; approve only a reviewed, trusted ref.

Add these Environment secrets:

| Name | Value |
| --- | --- |
| `ZLINK_SIGNING_KEYSTORE_B64` | Complete one-line contents of `ANDROID_SIGNING_KEYSTORE_BASE64.txt` from the recovery bundle |
| `ZLINK_SIGNING_PASSWORD` | Complete contents of the separately stored password file |

The signing job fails closed if either secret is missing or if the signed APK's
certificate does not match the pinned fingerprint.

## CI trust boundary

Pull requests run tests, lint, and both CI/stable builds without signing
secrets. The debug artifact is signed by an ephemeral runner key and is only
for disposable testing. The stable artifact from this job is unsigned and
cannot be installed.

Only a trusted push to `main` or a manually approved workflow dispatch starts
the `sign-stable` job. That job downloads the already-built unsigned APK into
a fresh runner, never checks out repository source, writes the PKCS#12 keystore under
`RUNNER_TEMP` with restrictive permissions, signs with `apksigner`, verifies
the pinned certificate and application ID, uploads the result, and removes the
temporary JKS.

## Verify a downloaded stable APK

Use the same Android SDK build-tools version or newer:

```bash
apksigner verify --verbose --print-certs ZLink-Remote-v0.2.1-stable.apk
sha256sum -c SHA256SUMS.txt
```

Confirm that the reported certificate SHA-256 matches the pinned release
fingerprint before distributing the APK.
