#!/usr/bin/env bash
set -euo pipefail
# Complete drafts are a recovery journal; version tags are never overwritten.
: "${TAG:?}" "${VERSION:?}" "${RELEASE_SHA:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}"
: "${HONGSHU_BUILD_DIR:?}" "${RUNNER_TEMP:?}" "${RELEASE_IMAGE:?}" "${RELEASE_OWNER:?}" "${IS_PRERELEASE:?}" "${GH_TOKEN:?}"
image=${RELEASE_IMAGE,,}
package=${image##*/}
release="$HONGSHU_BUILD_DIR/release"
state="$RUNNER_TEMP/publish-state"
mkdir -p "$state"

# GitHub Packages exposes the immutable manifest digest and its tags through the
# GitHub API. This works for private GHCR packages too, while registry inspection
# cannot reliably distinguish a missing private tag from an authorization error.
inspect() {
  local reference=$1 filter output
  if [[ "$reference" == *@sha256:* ]]; then
    local digest=${reference##*@}
    filter=".[] | select(.name == \"$digest\") | .name"
  else
    local tag=${reference##*:}
    filter=".[] | select((.metadata.container.tags // []) | index(\"$tag\")) | .name"
  fi
  if ! output=$(gh api --paginate "/users/$RELEASE_OWNER/packages/container/$package/versions" --jq "$filter" 2>"$state/inspect-error"); then
    echo 'GitHub Packages inspection unavailable; refusing to infer tag state' >&2
    cat "$state/inspect-error" >&2
    return 1
  fi
  if [[ -n "$output" ]]; then
    printf '%s\n' "$output" | head -n 1
  else
    printf 'absent\n'
  fi
}

if gh release view "$TAG" --json isDraft --jq '.isDraft' > "$state/draft" 2>"$state/release-error"; then
  test "$(cat "$state/draft")" = true || { echo 'Published release is immutable' >&2; exit 1; }
  release="$state/recovered"
  mkdir -p "$release"
  gh release download "$TAG" --dir "$release"
  (cd "$release" && sha256sum --check --strict SHA256SUMS)
  jq -e --arg sha "$RELEASE_SHA" --arg image "$image" --arg version "$VERSION" \
    '.sha==$sha and .image==$image and .version==$version and (.digest|test("^sha256:[0-9a-f]{64}$"))' "$release/IMAGE.json" >/dev/null
  digest=$(jq -r '.digest' "$release/IMAGE.json")
  test "$(inspect "$image@$digest")" = "$digest"
else
  grep -Eqi 'release not found|HTTP 404' "$state/release-error" || { echo 'Release lookup unavailable; refusing publication' >&2; exit 1; }
  for tag in "$VERSION" "$TAG"; do
    test "$(inspect "$image:$tag")" = absent || { echo 'Existing tag without a recoverable draft; manual reconciliation required' >&2; exit 1; }
  done
  for asset in "hongshu-${TAG}-android.apk" RELEASE_NOTES.md; do
    test -s "$release/$asset" || { echo 'Incomplete release assets' >&2; exit 1; }
  done
  staging="$image:staging-${RELEASE_SHA}-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
  docker buildx build --push -t "$staging" --metadata-file "$state/image.json" .
  digest=$(jq -er '."containerimage.digest"' "$state/image.json")
  [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]
  jq -n --arg sha "$RELEASE_SHA" --arg image "$image" --arg version "$VERSION" --arg digest "$digest" \
    '{sha:$sha,image:$image,version:$version,digest:$digest}' > "$release/IMAGE.json"
  (cd "$release" && find . -maxdepth 1 -type f ! -name SHA256SUMS -printf '%f\0' | sort -z | xargs -0 sha256sum > SHA256SUMS && sha256sum --check --strict SHA256SUMS)
  args=("$TAG" "$release"/* --verify-tag --draft --title "Hongshu $TAG" --notes-file "$release/RELEASE_NOTES.md")
  if [[ "$IS_PRERELEASE" == true ]]; then args+=(--prerelease); fi
  gh release create "${args[@]}"
fi
for tag in "$VERSION" "$TAG"; do
  existing=$(inspect "$image:$tag")
  if [[ "$existing" == absent ]]; then
    docker buildx imagetools create --prefer-index=false -t "$image:$tag" "$image@$digest"
  elif [[ "$existing" != "$digest" ]]; then
    echo 'Version tag has another digest; refusing to overwrite' >&2
    exit 1
  fi
  test "$(inspect "$image:$tag")" = "$digest"
done
gh release edit "$TAG" --draft=false
if [[ "$IS_PRERELEASE" == false ]]; then
  docker buildx imagetools create --prefer-index=false -t "$image:latest" "$image@$digest"
fi
