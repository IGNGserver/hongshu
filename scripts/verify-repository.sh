#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

required_files=(AGENTS.md README.md CONTRIBUTING.md SECURITY.md LICENSE VERSION .gitignore .gitattributes .editorconfig compose.external.yml docs/DATABASE.md)
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

if ! grep -Fq 'PORT:-18473}:8080' compose.yml \
    || ! grep -Fxq 'PORT=18473' .env.example \
    || ! grep -Fq '${PORT:-18473}' scripts/init-deployment.sh; then
  echo 'Docker host port default must be 18473 in Compose, .env.example, and the initializer' >&2
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

python3 - <<'PY'
import json
import pathlib
import re

root = pathlib.Path.cwd()
for workflow in (root / '.github/workflows').glob('*.yml'):
    for line_number, line in enumerate(workflow.read_text().splitlines(), 1):
        match = re.search(r'\buses:\s*([^\s#]+)', line)
        if match and not re.search(r'@[0-9a-f]{40}$', match.group(1)):
            raise SystemExit(f'unpinned GitHub Action: {workflow}:{line_number}: {match.group(1)}')
for line_number, line in enumerate((root / 'Dockerfile').read_text().splitlines(), 1):
    if line.startswith('FROM ') and '@sha256:' not in line:
        raise SystemExit(f'unpinned Docker base image at Dockerfile:{line_number}')
for compose in ('compose.yml', 'compose.external-backup.yml', '.github/workflows/ci.yml', '.github/workflows/release.yml'):
    for line_number, line in enumerate((root / compose).read_text().splitlines(), 1):
        if re.search(r'\bimage:\s*mysql:', line) and '@sha256:' not in line:
            raise SystemExit(f'unpinned MySQL image at {compose}:{line_number}')
package = json.loads((root / 'web/package.json').read_text())
lock = json.loads((root / 'web/package-lock.json').read_text())
playwright = package['devDependencies']['playwright']
locked = lock['packages']['node_modules/playwright']['version']
if playwright != locked:
    raise SystemExit(f'Playwright manifest/lock mismatch: {playwright} != {locked}')
for workflow in (root / '.github/workflows').glob('*.yml'):
    if re.search(r'npm install[^\n]*playwright', workflow.read_text()):
        raise SystemExit(f'unlocked Playwright installation in {workflow}')
PY

# Keep helper scripts syntactically valid even though most need external services to run.
python3 - <<'PYCOMPILE'
import pathlib, sys
for path in sorted(pathlib.Path('scripts').glob('*.py')):
    source = path.read_text()
    compile(source, str(path), 'exec')
print('scripts compile')
PYCOMPILE

echo "repository verification passed: version $version"
python3 scripts/test-operations.py
