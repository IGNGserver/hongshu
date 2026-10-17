#!/usr/bin/env python3
"""Generate every derived web icon from the checked-in 512px master.

Outputs: icon-192.png, apple-touch-icon.png (180px), icon-maskable-512.png (artwork
scaled into the 80% safe zone on the master's background) and icon.svg, which embeds a
128px copy because an SVG loaded as <img> or favicon cannot fetch other files.
"""
from __future__ import annotations

import base64
import pathlib
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent / "web"
MASTER = ROOT / "icon-512.png"


def read_png(path: pathlib.Path) -> tuple[int, int, bytes]:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"not a PNG: {path}")
    offset = 8
    width = height = bit_depth = color_type = interlace = None
    compressed = bytearray()
    while offset < len(data):
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        kind = data[offset + 4 : offset + 8]
        payload = data[offset + 8 : offset + 8 + length]
        offset += 12 + length
        if kind == b"IHDR":
            width, height, bit_depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            break
    if (width, height, bit_depth, color_type, interlace) != (512, 512, 8, 2, 0):
        raise ValueError("icon-512.png must be a non-interlaced 8-bit RGB PNG")

    raw = zlib.decompress(compressed)
    stride = width * 3
    rows: list[bytes] = []
    previous = bytearray(stride)
    cursor = 0

    def paeth(a: int, b: int, c: int) -> int:
        estimate = a + b - c
        pa, pb, pc = abs(estimate - a), abs(estimate - b), abs(estimate - c)
        if pa <= pb and pa <= pc:
            return a
        if pb <= pc:
            return b
        return c

    for _ in range(height):
        filter_type = raw[cursor]
        cursor += 1
        encoded = raw[cursor : cursor + stride]
        cursor += stride
        row = bytearray(stride)
        for i, value in enumerate(encoded):
            left = row[i - 3] if i >= 3 else 0
            up = previous[i]
            upper_left = previous[i - 3] if i >= 3 else 0
            if filter_type == 0:
                decoded = value
            elif filter_type == 1:
                decoded = value + left
            elif filter_type == 2:
                decoded = value + up
            elif filter_type == 3:
                decoded = value + ((left + up) // 2)
            elif filter_type == 4:
                decoded = value + paeth(left, up, upper_left)
            else:
                raise ValueError(f"unsupported PNG filter: {filter_type}")
            row[i] = decoded & 255
        rows.append(bytes(row))
        previous = row
    return width, height, b"".join(rows)


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


def resize_rgb(pixels: bytes, source_size: int, target_size: int) -> bytes:
    result = bytearray()
    for y in range(target_size):
        y0 = y * source_size / target_size
        y1 = (y + 1) * source_size / target_size
        sy0, sy1 = int(y0), min(source_size, int(y1 + 0.999999))
        for x in range(target_size):
            x0 = x * source_size / target_size
            x1 = (x + 1) * source_size / target_size
            sx0, sx1 = int(x0), min(source_size, int(x1 + 0.999999))
            channels = [0.0, 0.0, 0.0]
            weight_sum = 0.0
            for sy in range(sy0, sy1):
                wy = min(y1, sy + 1) - max(y0, sy)
                for sx in range(sx0, sx1):
                    weight = wy * (min(x1, sx + 1) - max(x0, sx))
                    pixel = (sy * source_size + sx) * 3
                    for channel in range(3):
                        channels[channel] += pixels[pixel + channel] * weight
                    weight_sum += weight
            result.extend(round(value / weight_sum) for value in channels)
    return bytes(result)


def write_png(path: pathlib.Path, size: int, pixels: bytes) -> None:
    rows = b"".join(b"\x00" + pixels[y * size * 3 : (y + 1) * size * 3] for y in range(size))
    payload = (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(rows, 9))
        + png_chunk(b"IEND", b"")
    )
    path.write_bytes(payload)


def png_bytes(size: int, pixels: bytes) -> bytes:
    rows = b"".join(b"\x00" + pixels[y * size * 3 : (y + 1) * size * 3] for y in range(size))
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(rows, 9))
        + png_chunk(b"IEND", b"")
    )


def pad_into_safe_zone(pixels: bytes, size: int, scale: float, feather: int = 24) -> bytes:
    """Scale the artwork down and centre it on the master's average border colour.

    The artwork's outer `feather` pixels fade into that colour, so the master's slightly
    uneven background does not leave a visible square seam.
    """
    border = [(y * size + x) * 3 for y in range(size) for x in range(size) if min(x, y, size - 1 - x, size - 1 - y) < 8]
    background = [round(sum(pixels[i + c] for i in border) / len(border)) for c in range(3)]
    inner = round(size * scale)
    art = resize_rgb(pixels, size, inner)
    canvas = bytearray(bytes(background) * (size * size))
    offset = (size - inner) // 2
    for y in range(inner):
        for x in range(inner):
            alpha = min(1.0, (min(x, y, inner - 1 - x, inner - 1 - y) + 0.5) / feather)
            source = (y * inner + x) * 3
            target = ((y + offset) * size + x + offset) * 3
            for c in range(3):
                canvas[target + c] = round(art[source + c] * alpha + background[c] * (1 - alpha))
    return bytes(canvas)


width, height, pixels = read_png(MASTER)
if width != height:
    raise ValueError("icon master must be square")
write_png(ROOT / "icon-192.png", 192, resize_rgb(pixels, width, 192))
write_png(ROOT / "apple-touch-icon.png", 180, resize_rgb(pixels, width, 180))
# Maskable icons may be cropped to a circle of 80% diameter; keep the artwork inside it.
write_png(ROOT / "icon-maskable-512.png", width, pad_into_safe_zone(pixels, width, 0.8))
embedded = base64.b64encode(png_bytes(128, resize_rgb(pixels, width, 128))).decode("ascii")
(ROOT / "icon.svg").write_text(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" role="img" aria-labelledby="title">\n'
    '  <title id="title">鸿枢</title>\n'
    "  <!-- Generated by scripts/generate-icons.py. Self-contained: an SVG loaded as <img> or favicon cannot fetch other files. -->\n"
    f'  <image width="128" height="128" href="data:image/png;base64,{embedded}"/>\n'
    "</svg>\n",
    encoding="utf-8",
)
print("generated icon-192.png, apple-touch-icon.png, icon-maskable-512.png and icon.svg from web/icon-512.png")
