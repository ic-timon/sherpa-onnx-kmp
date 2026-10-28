#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Lists the dynamic symbols an ELF binary exports.

The release checks need to know what a shared library actually exports, and the
tools for that are host-specific: `readelf` and `objdump` are not on macOS, and
macOS `nm` cannot read ELF at all. `strings` is not a substitute -- a name in a
string table says nothing about whether the symbol is defined and global.

This parses the section headers itself, which works identically on every host and
does not need the target architecture's toolchain.

Usage:
    check-elf-exports.py <library> [symbol ...]
    check-elf-exports.py --undefined <library> [symbol ...]

With no symbols it prints every defined global symbol, one per line. With
symbols it exits non-zero and names the ones that are not exported.

`--undefined` inverts the query to the undefined (imported) symbols instead,
which is how the release checks assert that an Android ABI linked against the
right TLS model.
"""

import struct
import sys

SHT_DYNSYM = 11
SHF_ALLOC = 0x2
STB_LOCAL = 0
STB_GLOBAL = 1
STB_WEAK = 2
SHN_UNDEF = 0


def read_cstr(blob, offset):
    end = blob.find(b"\0", offset)
    if end < 0:
        return blob[offset:].decode("utf-8", "replace")
    return blob[offset:end].decode("utf-8", "replace")


def parse(path, undefined=False):
    with open(path, "rb") as handle:
        blob = handle.read()
    if blob[:4] != b"\x7fELF":
        raise SystemExit(f"not an ELF file: {path}")

    is_64 = blob[4] == 2
    little = blob[5] == 1
    endian = "<" if little else ">"

    if is_64:
        shoff, = struct.unpack_from(endian + "Q", blob, 0x28)
        shentsize, shnum, shstrndx = struct.unpack_from(endian + "HHH", blob, 0x3A)
        sym_fmt = endian + "IBBHQQ"
        sym_size = 24
    else:
        shoff, = struct.unpack_from(endian + "I", blob, 0x20)
        shentsize, shnum, shstrndx = struct.unpack_from(endian + "HHH", blob, 0x2E)
        sym_fmt = endian + "IIIBBH"
        sym_size = 16

    sections = []
    for index in range(shnum):
        base = shoff + index * shentsize
        if is_64:
            name, kind, flags, _addr, offset, size, link, _info, _align, entsize = \
                struct.unpack_from(endian + "IIQQQQIIQQ", blob, base)
        else:
            name, kind, flags, _addr, offset, size, link, _info, _align, entsize = \
                struct.unpack_from(endian + "IIIIIIIIII", blob, base)
        sections.append({
            "name_offset": name,
            "kind": kind,
            "flags": flags,
            "offset": offset,
            "size": size,
            "link": link,
            "entsize": entsize or sym_size,
        })

    symbols = []
    for section in sections:
        if section["kind"] != SHT_DYNSYM:
            continue
        strings = sections[section["link"]]
        strtab = blob[strings["offset"]:strings["offset"] + strings["size"]]
        entry_size = section["entsize"] or sym_size
        body = blob[section["offset"]:section["offset"] + section["size"]]
        for offset in range(0, len(body), entry_size):
            if offset + sym_size > len(body):
                break
            if is_64:
                st_name, st_info, _other, st_shndx, _value, _size = \
                    struct.unpack_from(sym_fmt, body, offset)
            else:
                st_name, _value, _size, st_info, _other, st_shndx = \
                    struct.unpack_from(sym_fmt, body, offset)
            binding = st_info >> 4
            visibility = _other & 0x3
            if undefined:
                # Imports are global/weak references to another object.
                if binding not in (STB_GLOBAL, STB_WEAK) or st_shndx != SHN_UNDEF:
                    continue
            else:
                # STB_GLOBAL or STB_WEAK, defined, default visibility.
                if binding not in (STB_GLOBAL, STB_WEAK) or st_shndx == SHN_UNDEF:
                    continue
                if visibility != 0:
                    continue
            name = read_cstr(strtab, st_name)
            if name:
                symbols.append(name)
    return symbols


def main(argv):
    args = argv[1:]
    undefined = False
    if args and args[0] == "--undefined":
        undefined = True
        args = args[1:]
    if len(args) < 1:
        raise SystemExit(
            "usage: check-elf-exports.py [--undefined] <library> [symbol ...]")
    path = args[0]
    wanted = args[1:]
    exported = parse(path, undefined=undefined)

    what = "undefined" if undefined else "exported"
    if not wanted:
        for name in sorted(set(exported)):
            print(name)
        return 0

    present = set(exported)
    missing = [name for name in wanted if name not in present]
    if missing:
        for name in missing:
            print(f"missing {what} symbol: {name}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
