#!/usr/bin/env python3
"""Deterministic PNG icons without external image dependencies."""
import pathlib
import struct
import zlib

root = pathlib.Path(__file__).resolve().parent.parent / "web"

def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

for size in (192, 512):
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)
        for x in range(size):
            a, b = x / size, y / size
            bubble = .22 <= a <= .78 and .27 <= b <= .67
            tail = .27 <= a <= .44 and .67 < b <= .78 and b < .95 - a
            line = (.35 <= a <= .65 and .39 <= b <= .43) or (.35 <= a <= .56 and .50 <= b <= .54)
            pixels.extend((229, 242, 232) if (bubble or tail) and not line else (49, 91, 88))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(pixels, 9)) + chunk(b"IEND", b"")
    (root / f"icon-{size}.png").write_bytes(png)
