#!/usr/bin/env bash
# Trigger public APK publishing after distribution secret provisioning.
# CI rebuild requested after ANDROID_DISTRIBUTION_TOKEN configuration (2026-10-22).
set -euo pipefail
: "${GH_TOKEN:?MRADIO_DISTRIBUTION_TOKEN secret must be configured}"
: "${APP:?APP required}"
: "${VERSION:?VERSION required}"
: "${VERSION_CODE:?VERSION_CODE required}"
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || exit 1
TARGET="N2K02/n2k02.github.io"
SLUG=$(echo "$APP" | tr '[:upper:]' '[:lower:]')
APK="$APP-$VERSION.apk"
test -s "$APK"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]$ ]] || exit 1
CHANNEL=stable
TAG="$SLUG-v$VERSION"
MANIFEST="$SLUG/updates/stable.json"
if [ "$GITHUB_EVENT_NAME" = "pull_request" ]; then
  : "${PR_NUMBER:?PR_NUMBER required}"
  CHANNEL=experimental
  TAG="$SLUG-experimental-pr-$PR_NUMBER-run-$GITHUB_RUN_NUMBER"
  MANIFEST="$SLUG/updates/experimental.json"
fi
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")
if gh release view "$TAG" --repo "$TARGET" >/dev/null 2>&1; then
  # A prior attempt may have uploaded the immutable APK before failing to publish its manifest.
  # Resume only if the published APK is byte-for-byte identical.
  TMP_DIR=$(mktemp -d)
  trap 'rm -rf "$TMP_DIR"' EXIT
  if ! gh release download "$TAG" --repo "$TARGET" --pattern "$APK" --dir "$TMP_DIR"; then
    echo "::error::Existing release is missing the expected APK; refusing to overwrite it"
    exit 1
  fi
  if ! cmp -s "$APK" "$TMP_DIR/$APK"; then
    echo "::error::Existing release contains a different signed APK; refusing to overwrite it"
    exit 1
  fi
  echo "Existing release APK matches; resuming manifest publication"
else
  gh release create "$TAG" "$APK" --repo "$TARGET" --target main --title "$APP $VERSION ($CHANNEL)" --notes-file release-notes.md --latest=false
fi
URL="https://github.com/$TARGET/releases/download/$TAG/$APK"
NOTES=$( { grep '^- ' release-notes.md || true; } | head -10 | sed 's/^- //' | jq -R -s 'split("\n") | map(select(length > 0))')
jq -n --arg channel "$CHANNEL" --arg tag "$TAG" --arg version "$VERSION" --arg apkName "$APK" --arg apkUrl "$URL" --arg pageUrl "https://github.com/$TARGET/releases/tag/$TAG" --arg sha256 "$SHA" --argjson versionCode "$VERSION_CODE" --argjson apkSize "$SIZE" --argjson notes "$NOTES" '{channel:$channel,tag:$tag,versionName:$version,versionCode:$versionCode,apkName:$apkName,apkUrl:$apkUrl,apkSize:$apkSize,sha256:$sha256,pageUrl:$pageUrl,notes:$notes}' > manifest.json
CURRENT_JSON=$(gh api "repos/$TARGET/contents/$MANIFEST" --jq .content 2>/dev/null | tr -d '\n' | base64 -d 2>/dev/null || true)
if [ -n "$CURRENT_JSON" ]; then
  OLD=$(jq -r '.versionCode // 0' <<<"$CURRENT_JSON")
  if [[ "$OLD" =~ ^[0-9]+$ ]] && (( OLD >= VERSION_CODE )); then
    echo "Skipping manifest update: equal or newer build exists"
    exit 0
  fi
fi
ENC=$(base64 -w0 manifest.json)
for attempt in 1 2 3 4 5; do
  CURRENT_SHA=$(gh api "repos/$TARGET/contents/$MANIFEST" --jq .sha 2>/dev/null || true)
  ARGS=(--method PUT "repos/$TARGET/contents/$MANIFEST" -f "message=Publish $APP $VERSION ($CHANNEL)" -f "content=$ENC" -f "branch=main")
  [ -z "$CURRENT_SHA" ] || ARGS+=(-f "sha=$CURRENT_SHA")
  if gh api "${ARGS[@]}" >/dev/null; then
    break
  fi
  if [ "$attempt" -eq 5 ]; then
    echo "Failed to publish manifest after five attempts" >&2
    exit 1
  fi
  sleep "$((attempt * 2))"
done
