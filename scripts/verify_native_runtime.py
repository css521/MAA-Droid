#!/usr/bin/env python3
"""Verify packaged Android ELF dependencies and the ORT imports of both engines.

Usage:
    python3 scripts/verify_native_runtime.py app.apk --readelf /path/to/ndk/llvm-readelf

Runtime filenames and versions are discovered from DT_NEEDED, .gnu.version_r,
and .dynsym/.gnu.version. A single shared provider and separate providers are
both supported. No APK or source library is modified; extracted ELF files live
only in a temporary directory. Exit codes: 0 = valid, 1 = contract failure,
2 = invalid input/tool. This checks static linkage, not on-device execution.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass, field
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


CONSUMERS = ("libMaaCore.so", "libonnxruntime4j_jni.so")

# Public Android NDK libraries, supplied by the platform rather than the APK.
# In particular libc++_shared.so is NOT a system library and must be packaged.
# Private/vendor libraries must not be silently accepted as system dependencies.
ANDROID_SYSTEM_LIBRARIES = frozenset({
    "libaaudio.so", "libamidi.so", "libandroid.so", "libbinder_ndk.so",
    "libc.so", "libcamera2ndk.so", "libdl.so", "libEGL.so",
    "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libjnigraphics.so",
    "liblog.so", "libm.so", "libmediandk.so", "libnativewindow.so",
    "libneuralnetworks.so", "libOpenMAXAL.so", "libOpenSLES.so",
    "libstdc++.so", "libsync.so", "libvulkan.so", "libz.so",
})


class VerificationError(Exception):
    """The input or readelf output cannot be safely inspected."""


@dataclass(frozen=True)
class Symbol:
    index: int
    name: str
    version: str | None
    defined: bool
    binding: str
    visibility: str


@dataclass(frozen=True)
class VersionNeed:
    library: str
    name: str


@dataclass(frozen=True)
class VersionHash:
    section: str
    offset: int
    name: str


@dataclass
class ElfInfo:
    needed: list[str] = field(default_factory=list)
    soname: str | None = None
    symbols: list[Symbol] = field(default_factory=list)
    symbol_versions: dict[int, int] = field(default_factory=dict)
    version_needs: dict[int, VersionNeed] = field(default_factory=dict)
    version_definitions: dict[int, str] = field(default_factory=dict)
    base_names: list[str] = field(default_factory=list)
    version_hashes: list[VersionHash] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


@dataclass
class Report:
    libraries_per_abi: dict[str, int] = field(default_factory=dict)
    resolved_imports: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


def parse_readelf(output: str) -> ElfInfo:
    """Parse the GNU-style output of NDK llvm-readelf, preserving version indices.

    A version name alone is insufficient: two dependencies can use the same
    name. .gnu.version's per-symbol index identifies the actual version need.
    """
    info = ElfInfo()
    mode = None
    section_offset = None
    needed_library = None
    expected = {}
    counts = Counter()
    dynamic_seen = False
    for line in output.splitlines():
        if line.startswith("Dynamic section"):
            dynamic_seen = True
        dynamic = re.search(r"\((NEEDED|SONAME)\).*?\[([^\]]+)\]", line)
        if dynamic:
            kind, value = dynamic.groups()
            if kind == "NEEDED":
                info.needed.append(value)
            else:
                if info.soname is not None:
                    raise VerificationError("multiple DT_SONAME entries")
                info.soname = value

        table = re.match(r"Symbol table '\.dynsym' contains (\d+) entries:", line)
        if table:
            expected["dynsym"] = int(table[1])
            mode = "dynsym"
            continue
        header = re.match(
            r"Version (symbols|needs|definition) section .* contains (\d+) entries:", line
        )
        if header:
            mode = header[1]
            expected[mode] = int(header[2])
            section_offset = None
            needed_library = None
            continue
        offset = re.search(r"\bOffset:\s*0x([0-9a-fA-F]+)", line)
        if offset and mode in ("symbols", "needs", "definition"):
            section_offset = int(offset[1], 16)

        if mode == "dynsym":
            symbol = re.match(
                r"\s*(\d+):\s+[0-9a-fA-F]+\s+\S+\s+\S+\s+"
                r"(\S+)\s+(\S+)\s+(\S+)(?:\s+(.*))?$", line
            )
            if symbol:
                counts["dynsym"] += 1
                index, binding, visibility, ndx, raw_name = symbol.groups()
                name = (raw_name or "").strip().split()
                if not name:
                    continue
                name, separator, version = name[0].partition("@")
                info.symbols.append(Symbol(
                    int(index), name, version.lstrip("@") if separator else None,
                    ndx != "UND", binding, visibility,
                ))
        elif mode == "symbols":
            row = re.match(r"\s*([0-9a-fA-F]+):\s+(.+)", line)
            if row:
                first = int(row[1], 16)
                versions = re.findall(r"([0-9a-fA-F]+)h?\s*\([^)]*\)", row[2])
                for index, version in enumerate(versions, first):
                    if index in info.symbol_versions:
                        raise VerificationError("duplicate .gnu.version symbol index")
                    info.symbol_versions[index] = int(version, 16) & 0x7FFF
                counts["symbols"] += len(versions)
        elif mode == "needs":
            library = re.match(
                r"\s*0x[0-9a-fA-F]+:\s+Version:\s+\d+\s+File:\s+(\S+)\s+Cnt:\s+(\d+)",
                line,
            )
            if library:
                needed_library = library[1]
                counts["needs"] += 1
                expected["need_aux"] = expected.get("need_aux", 0) + int(library[2])
            need = re.match(
                r"\s*0x([0-9a-fA-F]+):\s+Name:\s+(\S+)\s+Flags:.*?Version:\s+(\d+)",
                line,
            )
            if need:
                if needed_library is None or section_offset is None:
                    raise VerificationError("version need missing its library/section offset")
                index = int(need[3]) & 0x7FFF
                if index in info.version_needs:
                    raise VerificationError("duplicate version need index")
                info.version_needs[index] = VersionNeed(needed_library, need[2])
                info.version_hashes.append(VersionHash(
                    ".gnu.version_r", section_offset + int(need[1], 16), need[2]
                ))
                counts["need_aux"] += 1
        elif mode == "definition":
            definition = re.match(
                r"\s*0x([0-9a-fA-F]+):\s+Rev:\s+\d+\s+Flags:\s+(.*?)\s+"
                r"Index:\s+(\d+)\s+Cnt:\s+\d+\s+Name:\s+(\S+)", line
            )
            if definition:
                if section_offset is None:
                    raise VerificationError("version definition missing its section offset")
                offset, flags, index, name = definition.groups()
                if int(index) in info.version_definitions:
                    raise VerificationError("duplicate version definition index")
                info.version_definitions[int(index)] = name
                if "BASE" in flags.split():
                    info.base_names.append(name)
                info.version_hashes.append(VersionHash(
                    ".gnu.version_d", section_offset + int(offset, 16) + 8, name
                ))
                counts["definition"] += 1

    if not dynamic_seen or "dynsym" not in expected:
        raise VerificationError("missing ELF dynamic section or .dynsym output")
    for section, count in expected.items():
        if counts[section] != count:
            raise VerificationError(
                f"incomplete {section} output: parsed {counts[section]}, expected {count}"
            )
    return info


def elf_hash(name: str) -> int:
    """System V ELF hash used by Verdef.vd_hash and Vernaux.vna_hash."""
    value = 0
    for byte in name.encode("utf-8"):
        value = (value << 4) + byte
        high = value & 0xF0000000
        value ^= high >> 24
        value &= ~high
    return value


def check_version_hashes(info: ElfInfo, data: bytes) -> list[str]:
    # readelf -V displays names, but not these hashes. A string-only rename can
    # therefore look correct in its output while still breaking the loader.
    if len(data) < 16 or data[:4] != b"\x7fELF" or data[5] not in (1, 2):
        raise VerificationError("invalid ELF header")
    byteorder = "little" if data[5] == 1 else "big"
    errors = []
    for reference in info.version_hashes:
        if reference.offset < 0 or reference.offset + 4 > len(data):
            raise VerificationError(f"{reference.section} hash offset outside ELF")
        actual = int.from_bytes(data[reference.offset:reference.offset + 4], byteorder)
        expected = elf_hash(reference.name)
        if actual != expected:
            errors.append(
                f"{reference.section}: hash for {reference.name} is 0x{actual:08x}, "
                f"expected 0x{expected:08x}"
            )
    return errors


def inspect_elf(path: Path, readelf: Path) -> ElfInfo:
    try:
        result = subprocess.run(
            [str(readelf), "--wide", "--dynamic", "--dyn-syms", "--version-info", str(path)],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            env={**os.environ, "LC_ALL": "C"}, timeout=30, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise VerificationError(f"cannot run llvm-readelf: {error}") from error
    if result.returncode or result.stderr.strip():
        detail = result.stderr.strip() or result.stdout.strip()
        raise VerificationError(f"llvm-readelf exited {result.returncode}: {detail[:1500]}")
    info = parse_readelf(result.stdout)
    info.errors.extend(check_version_hashes(info, path.read_bytes()))
    return info


def validate_abi(abi: str, libraries: dict[str, ElfInfo]) -> tuple[list[str], list[str]]:
    errors = []
    resolved = []
    sonames = defaultdict(list)
    for filename, info in sorted(libraries.items()):
        label = f"{abi}/{filename}"
        errors.extend(f"{label}: {error}" for error in info.errors)
        if info.soname:
            sonames[info.soname].append(filename)
            if info.base_names and info.base_names != [info.soname]:
                errors.append(
                    f"{label}: version definition BASE {info.base_names} "
                    f"does not match SONAME {info.soname}"
                )
        for dependency in info.needed:
            if dependency not in libraries and dependency not in ANDROID_SYSTEM_LIBRARIES:
                errors.append(f"{label}: unresolved DT_NEEDED {dependency} in this ABI")
        for need in set(info.version_needs.values()):
            if need.library not in info.needed:
                errors.append(
                    f"{label}: version need {need.library}:{need.name} is absent from DT_NEEDED"
                )
            provider = libraries.get(need.library)
            if provider and need.name not in provider.version_definitions.values():
                errors.append(
                    f"{label}: {need.library} does not define required version {need.name}"
                )
    for soname, filenames in sorted(sonames.items()):
        if len(filenames) > 1:
            errors.append(f"{abi}: duplicate SONAME {soname} in {', '.join(filenames)}")

    for filename in CONSUMERS:
        consumer = libraries.get(filename)
        label = f"{abi}/{filename}"
        if consumer is None:
            errors.append(f"{label}: required consumer is missing")
            continue
        imports = [s for s in consumer.symbols if not s.defined and s.name.startswith("Ort")]
        if not imports:
            errors.append(f"{label}: no undefined Ort* symbols; cannot verify runtime linkage")
        verified = defaultdict(list)
        for symbol in imports:
            symbol_label = f"{symbol.name}@{symbol.version or '<unversioned>'}"
            index = consumer.symbol_versions.get(symbol.index)
            need = consumer.version_needs.get(index)
            if symbol.version is None or need is None or need.name != symbol.version:
                errors.append(
                    f"{label}: {symbol_label} has no matching indexed .gnu.version_r requirement"
                )
                continue
            if need.library not in consumer.needed:
                # Already diagnosed above; never guess another provider by symbol name.
                continue
            provider = libraries.get(need.library)
            if provider is None:
                errors.append(f"{label}: provider {need.library} for {symbol_label} is not packaged")
                continue
            if provider.soname != need.library:
                errors.append(
                    f"{label}: provider {need.library} has SONAME {provider.soname!r}"
                )
            if provider.base_names != [need.library]:
                errors.append(
                    f"{label}: provider {need.library} has invalid version definition BASE "
                    f"{provider.base_names}"
                )
            exports = [s for s in provider.symbols if s.defined and s.name == symbol.name]
            matches = [
                s for s in exports
                if s.version == symbol.version
                and s.binding in ("GLOBAL", "WEAK", "UNIQUE")
                and s.visibility in ("DEFAULT", "PROTECTED")
                and provider.version_definitions.get(provider.symbol_versions.get(s.index))
                == symbol.version
            ]
            if not matches:
                available = ", ".join(sorted({s.version or "<unversioned>" for s in exports}))
                errors.append(
                    f"{label}: {symbol_label} is not exported with that version by "
                    f"{need.library} (symbol versions: {available or '<missing>'})"
                )
            else:
                verified[need.library].append(symbol_label)
        for provider, symbols in sorted(verified.items()):
            resolved.append(f"{label} -> {provider}: {', '.join(sorted(set(symbols)))}")
    return errors, resolved


def verify_apk(apk: Path, readelf: Path) -> Report:
    report = Report()
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="maa-elf-verify-") as tmp:
        entries = archive.infolist()
        duplicates = sorted(name for name, count in Counter(e.filename for e in entries).items() if count > 1)
        if duplicates:
            report.errors.extend(f"duplicate ZIP entry: {name}" for name in duplicates)
            return report
        native_entries = defaultdict(list)
        for entry in entries:
            if entry.is_dir() or not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            match = re.fullmatch(r"lib/([^/]+)/([^/]+\.so)", entry.filename)
            if match is None or match[1] in (".", ".."):
                report.errors.append(f"invalid native ZIP entry: {entry.filename}")
                continue
            native_entries[match[1]].append((match[2], entry))
        if not native_entries:
            report.errors.append("APK has no lib/<abi>/*.so entries")
        for abi, abi_entries in sorted(native_entries.items()):
            report.libraries_per_abi[abi] = len(abi_entries)
            libraries = {}
            for index, (filename, entry) in enumerate(sorted(abi_entries)):
                # Never use an untrusted ZIP path as an extraction destination.
                path = Path(tmp) / f"{index}.so"
                with archive.open(entry) as source, path.open("wb") as destination:
                    shutil.copyfileobj(source, destination)
                try:
                    libraries[filename] = inspect_elf(path, readelf)
                except VerificationError as error:
                    report.errors.append(f"{abi}/{filename}: {error}")
                finally:
                    path.unlink()
            errors, resolved = validate_abi(abi, libraries)
            report.errors.extend(errors)
            report.resolved_imports.extend(resolved)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("apk", type=Path, help="APK to verify (read only)")
    parser.add_argument("--readelf", "--llvm-readelf", required=True, type=Path, help="NDK llvm-readelf executable")
    args = parser.parse_args(argv)
    # Do not resolve the symlink: argv[0] selects GNU output for llvm-readelf,
    # whereas invoking its llvm-readobj target produces a different format.
    readelf = args.readelf.expanduser().absolute()
    if not readelf.is_file() or not os.access(readelf, os.X_OK):
        print(f"[ERROR] llvm-readelf is not executable: {readelf}", file=sys.stderr)
        return 2
    try:
        report = verify_apk(args.apk.expanduser(), readelf)
    except (OSError, zipfile.BadZipFile, RuntimeError, VerificationError) as error:
        print(f"[ERROR] {error}", file=sys.stderr)
        return 2
    for abi, count in report.libraries_per_abi.items():
        print(f"[INFO] {abi}: inspected {count} packaged libraries")
    for resolution in report.resolved_imports:
        print(f"[INFO] {resolution}")
    if report.errors:
        for error in dict.fromkeys(report.errors):
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print("[PASS] Native dependencies and versioned Ort* imports resolve in every packaged ABI")
    return 0


if __name__ == "__main__":
    sys.exit(main())
