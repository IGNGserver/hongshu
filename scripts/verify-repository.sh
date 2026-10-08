#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

required_files=(AGENTS.md README.md CONTRIBUTING.md SECURITY.md LICENSE VERSION .gitignore .gitattributes .editorconfig)
for file in "${required_files[@]}"; do
  test -f "$file" || { echo "missing required file: $file" >&2; exit 1; }
done

required_dirs=(android web server db/migrations docs .github/workflows)
for dir in "${required_dirs[@]}"; do
  test -d "$dir" || { echo "missing required directory: $dir" >&2; exit 1; }
done

version=$(tr -d '[:space:]' < VERSION)
if [[ ! "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|rc)\.[1-9][0-9]*)?$ ]]; then
  echo "invalid SemVer in VERSION: $version" >&2
  exit 1
fi

if git ls-files -z | grep -E -z '(^|/)(\.env|.*\.(pem|key|p12|jks|keystore))$' >/dev/null; then
  echo "tracked credential-like file detected" >&2
  exit 1
fi

if grep -RIlE 'gh[pousr]_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----' \
    --exclude-dir=.git --exclude='*.png' --exclude='*.jpg' . >/dev/null 2>&1; then
  echo "credential-like material detected in repository text" >&2
  exit 1
fi

echo "repository verification passed: version $version"
