#!/usr/bin/env python3
"""Report class files newer than Java 8 (major > 52) in the given class
directories and jars. MobiVM's compiler dies with the bare message
"Unsupported class file major version 61" without naming the file; this
names it before the AOT build spends a minute finding out.

    python3 classver.py <dir-or-jar> [<dir-or-jar> ...]

Exit 1 if any offender is found. Missing paths are reported and skipped.
"""
import os
import struct
import sys
import zipfile

LIMIT = 52  # Java 8


def major_of(data):
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        return None
    return struct.unpack(">H", data[6:8])[0]


def scan_dir(path):
    bad = []
    for root, _dirs, files in os.walk(path):
        for f in files:
            if not f.endswith(".class"):
                continue
            p = os.path.join(root, f)
            with open(p, "rb") as fh:
                m = major_of(fh.read(8))
            if m is not None and m > LIMIT:
                bad.append((os.path.relpath(p, path), m))
    return bad


def scan_jar(path):
    bad = []
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if not name.endswith(".class") or name.startswith("META-INF/versions/"):
                continue
            with z.open(name) as fh:
                m = major_of(fh.read(8))
            if m is not None and m > LIMIT:
                bad.append((name, m))
    return bad


def main(argv):
    offenders = 0
    for arg in argv:
        if not os.path.exists(arg):
            print("MISSING  %s" % arg)
            continue
        bad = scan_dir(arg) if os.path.isdir(arg) else scan_jar(arg)
        if bad:
            offenders += 1
            print("TOO NEW  %s: %d class(es) above major %d, e.g." % (arg, len(bad), LIMIT))
            for name, m in bad[:5]:
                print("           %s (major %d)" % (name, m))
    if offenders:
        print("CLASSVER: %d container(s) carry post-Java-8 bytecode" % offenders)
        return 1
    print("CLASSVER OK: every class file is major <= %d" % LIMIT)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
