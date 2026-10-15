#!/usr/bin/env python3
"""Rewrite the DT_NEEDED / DT_SONAME / DT_RUNPATH strings of an ELF64 object in place

为什么要有这一步: AGP 会静默丢掉带点的 native lib 名 (`libtalloc.so.2` 那种进不了 APK), 所以随包的
proot 那一套必须先归一命名 (照 `docs/host-build.md` 里 node / bash / rg 那三件的老办法)。归一落在
两处, 缺一不可:

1. **文件名**改成 `liblw*.so` (规则在打包那一侧)
2. **动态段里的字符串**改成同一批名字 —— NEEDED 是"要打开的那个文件名", 只改文件名不改 NEEDED 就
   会满世界找 `libtalloc.so.2`; RUNPATH 里写死的 Termux 路径同理, 换成 `$ORIGIN` 让加载器在**这个
   对象自己所在的目录**里找

patchelf 是 Linux 工具, 这台 Windows 构建机上跑不了, 而这一套改动只是"换几个定长的字符串", 所以就地
重写: 新字符串**不长于**旧的, 短出来的部分补 0 —— `.dynstr` 里的条目靠偏移引用, 终止符之后的字节
没人看, 这是 in-place 改名安全的原因 (patchelf 换短名字时做的也是这件事)

用法:
  python tools/lwlinux-elf.py <文件> [--needed 旧名=新名]... [--soname 新名] [--runpath 新值]
"""

import argparse
import struct
import sys

PT_LOAD = 1
PT_DYNAMIC = 2

DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10
DT_SONAME = 14
DT_RPATH = 15
DT_RUNPATH = 29


def fail(message):
    sys.exit(f"lwlinux-elf: {message}")


class Elf64:
    """够用就好的 ELF64 小端读取器: 程序头 + 动态段 + 字符串表"""

    def __init__(self, path):
        self.path = path
        with open(path, "rb") as handle:
            self.data = bytearray(handle.read())
        if self.data[:4] != b"\x7fELF" or self.data[4] != 2 or self.data[5] != 1:
            fail(f"{path} is not a 64-bit little-endian ELF")
        phoff = struct.unpack_from("<Q", self.data, 0x20)[0]
        phentsize = struct.unpack_from("<H", self.data, 0x36)[0]
        phnum = struct.unpack_from("<H", self.data, 0x38)[0]
        self.loads = []
        self.dynamic = None
        for index in range(phnum):
            offset = phoff + index * phentsize
            p_type, _, p_offset, p_vaddr, _, p_filesz, p_memsz, _ = struct.unpack_from(
                "<IIQQQQQQ", self.data, offset
            )
            if p_type == PT_LOAD:
                self.loads.append((p_vaddr, p_offset, p_filesz))
            elif p_type == PT_DYNAMIC:
                self.dynamic = (p_offset, p_filesz)
        if self.dynamic is None:
            fail(f"{path} has no PT_DYNAMIC (a static binary needs no renaming)")

    def vaddr_to_offset(self, address):
        for vaddr, offset, filesz in self.loads:
            if vaddr <= address < vaddr + filesz:
                return offset + (address - vaddr)
        fail(f"0x{address:x} is not in any PT_LOAD segment")

    def dynamic_entries(self):
        """(tag, val) 清单, 外加每一项在文件里的位置"""
        offset, size = self.dynamic
        entries = []
        for index in range(size // 16):
            where = offset + index * 16
            tag, val = struct.unpack_from("<QQ", self.data, where)
            if tag == DT_NULL:
                break
            entries.append((tag, val, where))
        return entries

    def string_at(self, offset):
        end = self.data.index(0, offset)
        return self.data[offset:end].decode("utf-8")

    def replace_string(self, offset, old, new):
        """就地换一个字符串: 新的必须不更长, 短的部分补 0"""
        if len(new) > len(old):
            fail(f"cannot replace {old!r} with the longer {new!r} in {self.path}")
        self.data[offset : offset + len(old)] = new.encode("utf-8") + b"\0" * (len(old) - len(new))
        return old != new

    def patch(self, needed, soname, runpath):
        entries = self.dynamic_entries()
        strtab = next((val for tag, val, _ in entries if tag == DT_STRTAB), None)
        if strtab is None:
            fail(f"{self.path} has no DT_STRTAB")
        strtab_offset = self.vaddr_to_offset(strtab)
        changed = 0
        for tag, val, _ in entries:
            if tag == DT_NEEDED and val != 0:
                where = strtab_offset + val
                old = self.string_at(where)
                if old in needed:
                    if self.replace_string(where, old, needed[old]):
                        print(f"  NEEDED {old} -> {needed[old]}")
                        changed += 1
            elif tag == DT_SONAME and soname is not None:
                where = strtab_offset + val
                old = self.string_at(where)
                if self.replace_string(where, old, soname):
                    print(f"  SONAME {old} -> {soname}")
                    changed += 1
            elif tag in (DT_RUNPATH, DT_RPATH) and runpath is not None:
                where = strtab_offset + val
                old = self.string_at(where)
                if self.replace_string(where, old, runpath):
                    print(f"  {'RUNPATH' if tag == DT_RUNPATH else 'RPATH'} {old} -> {runpath}")
                    changed += 1
        return changed

    def save(self):
        with open(self.path, "wb") as handle:
            handle.write(self.data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", help="the ELF64 object to rewrite")
    parser.add_argument(
        "--needed",
        action="append",
        default=[],
        metavar="OLD=NEW",
        help="rename one dependency (repeatable)",
    )
    parser.add_argument("--soname", help="replace the SONAME with this")
    parser.add_argument("--runpath", help="replace DT_RUNPATH (and DT_RPATH) with this")
    args = parser.parse_args()

    needed = {}
    for pair in args.needed:
        if "=" not in pair:
            fail(f"--needed wants OLD=NEW, not {pair!r}")
        old, new = pair.split("=", 1)
        needed[old] = new

    elf = Elf64(args.file)
    print(f"{args.file}:")
    changed = elf.patch(needed, args.soname, args.runpath)
    if changed:
        elf.save()
    print(f"  {changed} change(s)" + (" (not saved: nothing to do)" if not changed else ""))


if __name__ == "__main__":
    main()
