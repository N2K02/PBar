# Release signing

The release workflow decrypts `keys.tar.enc.b64` only inside the temporary GitHub Actions runner.

Before a release can be signed, add the `SIGNING_PASSPHRASE` repository secret under **Settings → Secrets and variables → Actions**. The value must match the encrypted signing material. Never commit the passphrase or decrypted keys.

The workflow signs the APK and verifies the resulting signature before publishing the GitHub Release.
