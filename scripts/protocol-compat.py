#!/usr/bin/env python3
"""N/N-1 wire compatibility probe for the Hongshu hub.

Runs one scripted client against two hub binaries: the previous release and the current
build. For every request shape the shipped clients still send, both hubs must accept it
and answer the same way. For every response field a client requires, the previous hub
must already provide it, because a client that assumes a newer field breaks on an
un-upgraded hub.

Requires a reachable MySQL with CREATE/DROP rights; every run uses a throwaway schema.
Nothing here is a substitute for real device testing: it exercises the HTTP contract
only, with no APK, no Push provider and no AI endpoint.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

PASSWORD = "synthetic-compat-password"

# Requests still emitted by released clients. Every one must keep working on both hubs.
LEGACY_REQUESTS = [
    ("POST", "/api/login", {"password": PASSWORD, "name": "compat device", "kind": "android"}, True),
    ("GET", "/api/me", None, False),
    ("PATCH", "/api/devices/{device}", {"upload": True}, False),
    ("PUT", "/api/sims", {"phone": "+123456789", "label": "Compat", "subscription_id": 1}, False),
    ("POST", "/api/messages", {"messages": [
        {"receiver": "+123456789", "sender": "10086", "body": "compat first", "timestamp": 1000},
        {"receiver": "+123456789", "sender": "10086", "body": "compat second", "timestamp": 2000},
    ]}, False),
    ("GET", "/api/sync?after=0&limit=100", None, False),
    # Legacy clients page with offset; the keyset cursor is additive.
    ("GET", "/api/conversations?offset=0&limit=50", None, False),
    ("POST", "/api/messages/read", {"sender": "10086"}, False),
    # Legacy global read carries no durable target; the hub must keep the old meaning.
    ("POST", "/api/messages/read", {"all": True}, False),
    ("GET", "/api/read-state?limit=100", None, False),
    ("GET", "/healthz", None, True),
]

# Fields the shipped Web/Android clients require unconditionally. A previous hub that
# lacks one of these means the client cannot talk to it at all.
REQUIRED_FIELDS = {
    "/api/login": ["token", "device"],
    "/api/me": ["device", "cursor", "epoch"],
    "/api/sync": ["messages", "cursor", "more", "epoch"],
    # Legacy offset paging returns conversations without the keyset extras; the extras
    # are what the previous release may lack, so they are not required here.
    "/api/conversations": ["conversations"],
    "/api/read-state": ["items", "epoch", "revision", "head"],
    "/healthz": ["status"],
}

# Additive capabilities and endpoints a client must not assume exist.
ADDITIVE_PATHS = ["/api/ops"]
ADDITIVE_FEATURES = ["outgoing_sms", "metadata_revision", "recent_snapshot", "call_log", "conversation_cursor"]


class Probe:
    def __init__(self, name, binary, migrations, database, port, workdir, expect_keyset):
        self.name = name
        self.binary = binary
        self.migrations = migrations
        self.database = database
        self.port = port
        self.workdir = workdir
        self.expect_keyset = expect_keyset
        self.ops_status = None
        self.token = ""
        self.device = ""
        self.process = None
        self.failures = []
        self.observations = {}

    @property
    def origin(self):
        return f"http://127.0.0.1:{self.port}"

    def fail(self, message):
        self.failures.append(f"[{self.name}] {message}")

    def start(self, dsn):
        env = dict(os.environ)
        env.update({
            "MYSQL_DSN": dsn,
            "HONGSHU_PASSWORD": PASSWORD,
            "PUBLIC_URL": self.origin,
            "MIGRATIONS_DIR": self.migrations,
            "WEB_DIR": os.environ.get("HONGSHU_COMPAT_WEB_DIR", "web"),
            "LISTEN_ADDR": f"127.0.0.1:{self.port}",
            "HONGSHU_MIGRATE": "auto",
        })
        env.pop("HONGSHU_DATABASE_URL", None)
        self.process = subprocess.Popen(
            [self.binary], cwd=self.workdir, env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
        )
        for _ in range(120):
            if self.process.poll() is not None:
                stderr = self.process.stderr.read().decode("utf-8", "replace")
                self.fail(f"hub exited during startup: {stderr.strip()[:400]}")
                return False
            try:
                with urllib.request.urlopen(self.origin + "/healthz", timeout=1):
                    return True
            except (urllib.error.URLError, OSError):
                time.sleep(0.25)
        self.fail("hub did not become ready")
        return False

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill()

    def call(self, method, path, body=None, anonymous=False):
        if "{device}" in path:
            path = path.replace("{device}", self.device)
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.origin + path, data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        # Shipped clients always send Origin; the hub uses it for the exact-origin check.
        request.add_header("Origin", self.origin)
        if not anonymous:
            if not self.token:
                self.fail(f"{method} {path} needs a token but login did not produce one")
                return 0, {}
            request.add_header("Authorization", "Bearer " + self.token)
            request.add_header("Cookie", "hongshu=" + self.token)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                raw = response.read()
                return response.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                return error.code, json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                return error.code, {}
        except (urllib.error.URLError, OSError) as error:
            detail = ""
            if self.process and self.process.poll() is not None:
                detail = " (hub exited: " + self.process.stderr.read().decode("utf-8", "replace").strip()[:400] + ")"
            self.fail(f"{method} {path} transport error: {error}{detail}")
            return 0, {}

    def run_legacy_matrix(self):
        for method, path, body, anonymous in LEGACY_REQUESTS:
            status, payload = self.call(method, path, body, anonymous)
            if status != 200:
                self.fail(f"legacy request {method} {path} -> {status} {payload}")
                continue
            if path == "/api/login":
                self.token = payload.get("token", "")
                self.device = (payload.get("device") or {}).get("id", "")
            bare = path.split("?")[0]
            for field in REQUIRED_FIELDS.get(bare, []):
                if field not in payload:
                    self.fail(f"{bare} response is missing required field {field!r}")
            self.observations[bare] = payload

    def check_semantics(self):
        sync = self.observations.get("/api/sync") or {}
        messages = sync.get("messages") or []
        if len(messages) != 2:
            self.fail(f"/api/sync returned {len(messages)} messages, expected the 2 uploaded")
        if sync.get("cursor") != 2:
            self.fail(f"/api/sync cursor = {sync.get('cursor')!r}, expected 2")
        conversations = self.observations.get("/api/conversations") or {}
        if len(conversations.get("conversations") or []) != 1:
            self.fail("/api/conversations did not collapse the two messages into one sender")
        # The legacy global read must still advance the shared watermark.
        status, payload = self.call("POST", "/api/messages/read", {"all": True})
        if status != 200:
            self.fail(f"repeat legacy global read -> {status}")
        elif payload.get("unread_count") not in (0, None):
            self.fail(f"legacy global read left unread={payload.get('unread_count')}")

    def check_keyset_additions(self):
        # The current hub must serve the legacy offset request *and* the head-fenced
        # keyset fields the new clients rely on. The previous release predates keyset
        # paging, so for it these are recorded as expected skew rather than failures.
        status, payload = self.call("GET", "/api/conversations?limit=50")
        if status != 200:
            self.fail(f"keyset conversations -> {status} {payload}")
            return
        present = [field for field in ("conversations", "next_cursor", "more", "head", "epoch") if field in payload]
        missing = [field for field in ("conversations", "next_cursor", "more", "head", "epoch") if field not in payload]
        if self.expect_keyset:
            if missing:
                self.fail(f"keyset conversations is missing {missing}")
        elif missing:
            print(f"note: {self.name} predates keyset paging (missing {missing}); clients must fall back to offset")
        status, payload = self.call("GET", "/api/conversations?offset=0&limit=50&cursor=cursor")
        if self.expect_keyset:
            if status != 400:
                self.fail(f"offset plus cursor should be rejected, got {status}")
        elif status == 200:
            print(f"note: {self.name} accepts offset together with cursor; new clients never send both")
        _ = present

    def check_additive_surface(self):
        status, _ = self.call("GET", "/api/ops")
        self.ops_status = status
        if status not in (200, 404):
            self.fail(f"additive /api/ops answered with unexpected status {status}")
        me = self.observations.get("/api/me") or {}
        features = me.get("features")
        if features is not None:
            # features is a list of capability names; a client must treat an absent
            # capability as unsupported rather than assuming it.
            if not isinstance(features, list):
                self.fail(f"/api/me features should be a list, got {type(features).__name__}")
            else:
                unknown = [name for name in features if name not in ADDITIVE_FEATURES]
                if unknown:
                    self.fail(f"hub announces features this probe does not know: {unknown}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--old", required=True, help="previous release hub binary")
    parser.add_argument("--new", required=True, help="current build hub binary")
    parser.add_argument("--old-src", required=True, help="checkout of the previous release")
    parser.add_argument("--new-src", required=True, help="checkout of the current branch")
    parser.add_argument("--mysql-bin", required=True)
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", default="33306")
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--keep", action="store_true", help="leave the scratch databases behind")
    args = parser.parse_args()

    scratch = tempfile.mkdtemp(prefix="hongshu-compat-")
    suffix = f"{int(time.time())}_{os.getpid()}"
    probes = [
        Probe("alpha N-1", args.old, os.path.join(args.old_src, "db/migrations"),
              f"hongshu_compat_old_{suffix}", 33401, os.path.join(args.old_src, "server"), expect_keyset=False),
        Probe("current", args.new, os.path.join(args.new_src, "db/migrations"),
              f"hongshu_compat_new_{suffix}", 33402, os.path.join(args.new_src, "server"), expect_keyset=True),
    ]

    def mysql(*statements, database=None):
        command = [args.mysql_bin, "--no-defaults", "-h", args.mysql_host, "-P", str(args.mysql_port),
                   "-u", args.mysql_user, "-B"]
        if database:
            command.append(database)
        else:
            command.append("-N")
        command += ["-e", ";".join(statements)]
        return subprocess.run(command, check=True, capture_output=True, text=True).stdout.strip()

    failures = []
    try:
        for probe in probes:
            mysql(f"DROP DATABASE IF EXISTS {probe.database}",
                  f"CREATE DATABASE {probe.database} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
            dsn = f"{args.mysql_user}@tcp({args.mysql_host}:{args.mysql_port})/{probe.database}?timeout=5s"
            if probe.start(dsn):
                probe.run_legacy_matrix()
                probe.check_semantics()
                probe.check_keyset_additions()
                probe.check_additive_surface()
            failures.extend(probe.failures)
            probe.stop()

        old, new = probes
        # A field the current hub reports but the previous one never did must be treated
        # as additive: the clients can only rely on what N-1 already returned.
        for path in sorted(set(old.observations) & set(new.observations)):
            new_only = set(new.observations[path]) - set(old.observations[path])
            if new_only:
                print(f"note: {path} adds {sorted(new_only)} over the previous release")
        old_features = (old.observations.get("/api/me") or {}).get("features")
        if old_features is None:
            print("note: the previous release does not announce features; clients must treat them as absent")
        if old.ops_status not in (404, None):
            print(f"note: previous release answered /api/ops with {old.ops_status}")
        elif old.ops_status == 404:
            print("note: /api/ops is additive; the previous release does not serve it")
    finally:
        for probe in probes:
            probe.stop()
            if not args.keep:
                try:
                    mysql(f"DROP DATABASE IF EXISTS {probe.database}")
                except subprocess.CalledProcessError:
                    pass
        shutil.rmtree(scratch, ignore_errors=True)

    if failures:
        for message in failures:
            print("FAIL", message, file=sys.stderr)
        return 1
    print("protocol compatibility probe passed: legacy request shapes and required fields hold on both hubs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
