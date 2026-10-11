#!/usr/bin/env bash
set -euo pipefail
# Complete drafts are a recovery journal; version tags are never overwritten.
: "${TAG:?}" "${VERSION:?}" "${GITHUB_SHA:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}"
: "${HONGSHU_BUILD_DIR:?}" "${RUNNER_TEMP:?}" "${RELEASE_IMAGE:?}" "${IS_PRERELEASE:?}"
image=${RELEASE_IMAGE,,}
release="$HONGSHU_BUILD_DIR/release"
state="$RUNNER_TEMP/publish-state"
mkdir -p "$state"
inspect() {
  local reference=$1 output
  if output=$(docker buildx imagetools inspect "$reference" --format '{{json .Manifest}}' 2>"$state/inspect-error"); then
    jq -er '.digest' <<< "$output"
  elif grep -Eqi 'manifest unknown|no such manifest|unexpected status.*404|manifest.*not found' "$state/inspect-error"; then
    printf 'absent\n'
  else
    echo 'Registry inspection unavailable; refusing to infer absence' >&2
    return 1
  fi
}
if gh release view "$TAG" --json isDraft --jq '.isDraft' > "$state/draft" 2>"$state/release-error"; then
  test "$(cat "$state/draft")" = true || { echo 'Published release is immutable' >&2; exit 1; }
  release="$state/recovered"
  mkdir -p "$release"
  gh release download "$TAG" --dir "$release"
  (cd "$release" && sha256sum --check --strict SHA256SUMS)
  jq -e --arg sha "$GITHUB_SHA" --arg image "$image" --arg version "$VERSION" \
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
  staging="$image:staging-${GITHUB_SHA}-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
  docker buildx build --push -t "$staging" --metadata-file "$state/image.json" .
  digest=$(jq -er '.["containerimage.digest"]' "$state/image.json")
  [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]
  jq -n --arg sha "$GITHUB_SHA" --arg image "$image" --arg version "$VERSION" --arg digest "$digest" \
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
