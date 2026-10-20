#!/usr/bin/env python3
"""Small public-resource probe. Never send credentials or log response content."""
import argparse
import http.client
import json
import re
import time
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--label", default="route")
    parser.add_argument("--samples", type=int, default=3)
    args = parser.parse_args()
    url = urlsplit(args.origin)
    if (url.scheme not in ("http", "https") or not url.hostname or url.username or url.password
            or url.path not in ("", "/") or url.query or url.fragment):
        parser.error("origin must be HTTP(S), without credentials, path or query")
    if not 1 <= args.samples <= 10 or not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", args.label):
        parser.error("samples must be 1..10; label must be a short identifier")
    connect = http.client.HTTPSConnection if url.scheme == "https" else http.client.HTTPConnection
    for category, path in (("shell", "/"), ("script", "/app.js"), ("style", "/style.css"), ("health", "/healthz")):
        connection = connect(url.hostname, url.port, timeout=15)
        for sample in range(args.samples):
            started = time.monotonic()
            result = {"route": args.label, "category": category, "sample": sample + 1}
            try:
                connection.request("GET", path, headers={"Accept-Encoding": "gzip"})
                response = connection.getresponse()
                result["ttfb_ms"] = round((time.monotonic() - started) * 1000, 2)
                result["bytes"] = len(response.read())
                result["total_ms"] = round((time.monotonic() - started) * 1000, 2)
                result["status"] = response.status
                result["cache"] = response.getheader("Cache-Control")
                result["edge_cache"] = response.getheader("CF-Cache-Status")
            except (OSError, http.client.HTTPException):
                # Exception messages may contain URLs/headers: only fixed category.
                result["error"] = "transport_failed"
                connection.close()
                connection = connect(url.hostname, url.port, timeout=15)
            print(json.dumps(result, ensure_ascii=False))
        connection.close()


if __name__ == "__main__":
    main()
