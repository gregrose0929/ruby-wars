"""Tiny level.dat editor: set byte/int fields under Data.

    python tools/leveldat.py <world>/level.dat allowCommands=1b GameType=1 LevelName="Ruby Wars Training"

A dedicated server writes worlds without cheats enabled; this flips them on so
the save works as a singleplayer world with /rubywars commands.
"""
import gzip
import struct
import sys


def read_payload(buf, pos, tag):
    if tag == 1:
        return struct.unpack_from(">b", buf, pos)[0], pos + 1
    if tag == 2:
        return struct.unpack_from(">h", buf, pos)[0], pos + 2
    if tag == 3:
        return struct.unpack_from(">i", buf, pos)[0], pos + 4
    if tag == 4:
        return struct.unpack_from(">q", buf, pos)[0], pos + 8
    if tag == 5:
        return struct.unpack_from(">f", buf, pos)[0], pos + 4
    if tag == 6:
        return struct.unpack_from(">d", buf, pos)[0], pos + 8
    if tag == 7:
        n = struct.unpack_from(">i", buf, pos)[0]
        return buf[pos + 4:pos + 4 + n], pos + 4 + n
    if tag == 8:
        n = struct.unpack_from(">H", buf, pos)[0]
        return buf[pos + 2:pos + 2 + n].decode("utf-8"), pos + 2 + n
    if tag == 9:
        inner, n = struct.unpack_from(">bi", buf, pos)
        pos += 5
        items = []
        for _ in range(n):
            v, pos = read_payload(buf, pos, inner)
            items.append(v)
        return (inner, items), pos
    if tag == 10:
        entries = []
        while True:
            t = buf[pos]
            pos += 1
            if t == 0:
                return entries, pos
            n = struct.unpack_from(">H", buf, pos)[0]
            name = buf[pos + 2:pos + 2 + n].decode("utf-8")
            pos += 2 + n
            v, pos = read_payload(buf, pos, t)
            entries.append([t, name, v])
    if tag == 11:
        n = struct.unpack_from(">i", buf, pos)[0]
        return list(struct.unpack_from(">%di" % n, buf, pos + 4)), pos + 4 + 4 * n
    if tag == 12:
        n = struct.unpack_from(">i", buf, pos)[0]
        return list(struct.unpack_from(">%dq" % n, buf, pos + 4)), pos + 4 + 8 * n
    raise ValueError("bad tag %d" % tag)


def write_payload(out, tag, v):
    fmt = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}
    if tag in fmt:
        out += struct.pack(fmt[tag], v)
    elif tag == 7:
        out += struct.pack(">i", len(v)) + v
    elif tag == 8:
        b = v.encode("utf-8")
        out += struct.pack(">H", len(b)) + b
    elif tag == 9:
        inner, items = v
        out += struct.pack(">bi", inner, len(items))
        for item in items:
            write_payload(out, inner, item)
    elif tag == 10:
        for t, name, val in v:
            b = name.encode("utf-8")
            out += struct.pack(">bH", t, len(b)) + b
            write_payload(out, t, val)
        out += b"\0"
    elif tag == 11:
        out += struct.pack(">i%di" % len(v), len(v), *v)
    elif tag == 12:
        out += struct.pack(">i%dq" % len(v), len(v), *v)


def main():
    path = sys.argv[1]
    buf = gzip.decompress(open(path, "rb").read())
    assert buf[0] == 10
    n = struct.unpack_from(">H", buf, 1)[0]
    root_name = buf[3:3 + n]
    root, _ = read_payload(buf, 3 + n, 10)
    data = next(v for t, name, v in root if name == "Data")
    for arg in sys.argv[2:]:
        key, value = arg.split("=", 1)
        if value.endswith("b"):
            tag, val = 1, int(value[:-1])
        elif value.lstrip("-").isdigit():
            tag, val = 3, int(value)
        else:
            tag, val = 8, value.strip('"')
        for entry in data:
            if entry[1] == key:
                entry[0], entry[2] = tag, val
                break
        else:
            data.append([tag, key, val])
        print("set", key, "=", val)
    out = bytearray(b"\x0a" + struct.pack(">H", len(root_name)) + root_name)
    write_payload(out, 10, root)
    open(path, "wb").write(gzip.compress(bytes(out)))


if __name__ == "__main__":
    main()
