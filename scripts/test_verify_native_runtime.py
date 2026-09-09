"""Portable tests; no Gradle, NDK, network, or checked-in binary fixtures needed.

    python3 -B -m unittest discover -s scripts -p test_verify_native_runtime.py -v
"""

from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
from dataclasses import replace
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

if __package__:
    from . import verify_native_runtime as verifier
else:
    import verify_native_runtime as verifier


MAA, JNI = verifier.CONSUMERS
RUNTIME = "libonnxruntime.so"
OTHER_RUNTIME = "libengine_inference.so"
API = "OrtGetApiBase"
EXTRA_API = "OrtSessionOptionsAppendExecutionProvider_CPU"


def elf_fixture(soname, needed=(), imports=(), exports=()):
    """Build small readelf transcripts, with separate symbol/need/def indices.

    imports: (symbol, version, provider); exports: (symbol, version).
    The accompanying bytes only model hash fields, not an executable ELF.
    """
    definition_names = list(dict.fromkeys(version for _, version in exports))
    definitions = {name: index for index, name in enumerate(definition_names, 2)}
    needs = list(dict.fromkeys((provider, version) for _, version, provider in imports))
    need_indices = {need: index for index, need in enumerate(needs, len(definitions) + 3)}
    rows = [("", None, 0, "LOCAL", "UND")]
    rows.extend((name, version, need_indices[(provider, version)], "GLOBAL", "UND")
                for name, version, provider in imports)
    rows.extend((name, version, definitions[version], "GLOBAL", "12")
                for name, version in exports)
    lines = ["Dynamic section at offset 0x100 contains 20 entries:"]
    lines.extend(f"  0x0000000000000001 (NEEDED) Shared library: [{name}]" for name in needed)
    if soname:
        lines.append(f"  0x000000000000000e (SONAME) Library soname: [{soname}]")
    lines.append(f"Symbol table '.dynsym' contains {len(rows)} entries:")
    lines.append("   Num: Value Size Type Bind Vis Ndx Name")
    for index, (name, version, _, binding, ndx) in enumerate(rows):
        suffix = (("@" if ndx == "UND" else "@@") + version) if version else ""
        lines.append(f"  {index}: 0000000000000000 0 FUNC {binding} DEFAULT {ndx} {name}{suffix}")
    lines.extend([
        f"Version symbols section '.gnu.version' contains {len(rows)} entries:",
        " Addr: 0000000000000200 Offset: 0x000200 Link: 3 (.dynsym)",
    ])
    for first in range(0, len(rows), 4):
        cells = [f"{row[2]:x} ({row[1] or '*local*'})" for row in rows[first:first + 4]]
        lines.append(f"  {first:03x}: " + "   ".join(cells))

    data = bytearray(0x2000)
    data[:6] = b"\x7fELF\x02\x01"
    if exports:
        lines.extend([
            f"Version definition section '.gnu.version_d' contains {len(definitions) + 1} entries:",
            " Addr: 0000000000000400 Offset: 0x000400 Link: 8 (.dynstr)",
        ])
        for record, (name, index) in enumerate([(soname, 1), *definitions.items()]):
            offset = record * 28
            flags = "BASE" if index == 1 else "none"
            lines.append(f"  0x{offset:04x}: Rev: 1 Flags: {flags} Index: {index} Cnt: 1 Name: {name}")
            data[0x400 + offset + 8:0x400 + offset + 12] = verifier.elf_hash(name).to_bytes(4, "little")
    if imports:
        providers = list(dict.fromkeys(provider for provider, _ in needs))
        lines.extend([
            f"Version needs section '.gnu.version_r' contains {len(providers)} entries:",
            " Addr: 0000000000000800 Offset: 0x000800 Link: 8 (.dynstr)",
        ])
        aux_offset = 16 * len(providers)
        for record, provider in enumerate(providers):
            versions = [version for library, version in needs if library == provider]
            lines.append(f"  0x{record * 16:04x}: Version: 1 File: {provider} Cnt: {len(versions)}")
            for version in versions:
                index = need_indices[(provider, version)]
                lines.append(f"  0x{aux_offset:04x}: Name: {version} Flags: none Version: {index}")
                data[0x800 + aux_offset:0x800 + aux_offset + 4] = verifier.elf_hash(version).to_bytes(4, "little")
                aux_offset += 16
    return "\n".join(lines), bytes(data)


def elf(soname, needed=(), imports=(), exports=()):
    output, data = elf_fixture(soname, needed, imports, exports)
    info = verifier.parse_readelf(output)
    info.errors.extend(verifier.check_version_hashes(info, data))
    return info


def shared_layout(version="VERS_1.19.2"):
    return {
        MAA: elf(MAA, [RUNTIME, "liblog.so"], [(API, version, RUNTIME)]),
        JNI: elf(JNI, [RUNTIME, "libm.so"], [(API, version, RUNTIME), (EXTRA_API, version, RUNTIME)]),
        RUNTIME: elf(RUNTIME, ["libc.so", "libdl.so"], exports=[(API, version), (EXTRA_API, version)]),
    }


class ReadelfParsingTests(unittest.TestCase):
    def test_parses_dynamic_symbols_versions_and_hash_offsets(self):
        output, data = elf_fixture(
            "libconsumer.so", [RUNTIME, "libc.so"], [(API, "ORT_FUTURE", RUNTIME)],
            [("PublicEntry", "PLUGIN_ABI")],
        )
        info = verifier.parse_readelf(output)
        self.assertEqual([RUNTIME, "libc.so"], info.needed)
        self.assertEqual("libconsumer.so", info.soname)
        symbol = info.symbols[0]
        self.assertEqual(API, symbol.name)
        self.assertFalse(symbol.defined)
        self.assertEqual("ORT_FUTURE", symbol.version)
        need = info.version_needs[info.symbol_versions[symbol.index]]
        self.assertEqual(verifier.VersionNeed(RUNTIME, "ORT_FUTURE"), need)
        self.assertTrue(info.symbols[1].defined)
        self.assertEqual("PLUGIN_ABI", info.symbols[1].version)
        self.assertEqual(["libconsumer.so"], info.base_names)
        self.assertEqual([], verifier.check_version_hashes(info, data))

    def test_hexadecimal_and_hidden_symbol_version_indices(self):
        output, _ = elf_fixture(MAA, [RUNTIME], [(API, "ORT_NEXT", RUNTIME)])
        output = output.replace("3 (ORT_NEXT)", "ah(ORT_NEXT)").replace("Version: 3", "Version: 10")
        info = verifier.parse_readelf(output)
        self.assertEqual(10, info.symbol_versions[1])
        self.assertEqual("ORT_NEXT", info.version_needs[10].name)

    def test_gnu_readelf_symbol_index_suffix(self):
        output, _ = elf_fixture(MAA, [RUNTIME], [(API, "ORT_NEXT", RUNTIME)])
        output = output.replace(f"UND {API}@ORT_NEXT", f"UND {API}@ORT_NEXT (3)")
        self.assertEqual("ORT_NEXT", verifier.parse_readelf(output).symbols[0].version)

    def test_multiple_versions_for_same_dependency(self):
        info = elf(MAA, [RUNTIME], [(API, "ORT_A", RUNTIME), (EXTRA_API, "ORT_B", RUNTIME)])
        self.assertEqual({"ORT_A", "ORT_B"}, {need.name for need in info.version_needs.values()})

    def test_rejects_missing_or_truncated_output(self):
        output, _ = elf_fixture(MAA, [RUNTIME], [(API, "ORT_NEXT", RUNTIME)])
        for broken in ("not readelf", output.replace("Name: ORT_NEXT", "Unparsed: ORT_NEXT")):
            with self.subTest(output=broken), self.assertRaises(verifier.VerificationError):
                verifier.parse_readelf(broken)

    def test_hash_known_vectors(self):
        self.assertEqual(0, verifier.elf_hash(""))
        self.assertEqual(0x00050D63, verifier.elf_hash("LIBC"))
        self.assertEqual(0x006EC32F, verifier.elf_hash("hello"))

    def test_detects_stale_definition_base_and_requirement_hashes(self):
        output, data = elf_fixture(RUNTIME, ["libhelper.so"], [("Helper", "HELPER_ABI", "libhelper.so")], [(API, "ORT_NEXT")])
        info = verifier.parse_readelf(output)
        for reference in info.version_hashes:
            with self.subTest(section=reference.section, name=reference.name):
                corrupted = bytearray(data)
                corrupted[reference.offset] ^= 1
                errors = verifier.check_version_hashes(info, bytes(corrupted))
                self.assertEqual(1, len(errors))
                self.assertIn(reference.name, errors[0])

    def test_big_endian_hashes_and_bad_offsets(self):
        info = verifier.ElfInfo(version_hashes=[verifier.VersionHash(".gnu.version_d", 16, "LIBC")])
        data = b"\x7fELF\x02\x02" + b"\x00" * 10 + b"\x00\x05\x0d\x63"
        self.assertEqual([], verifier.check_version_hashes(info, data))
        with self.assertRaises(verifier.VerificationError):
            verifier.check_version_hashes(info, data[:16])


class NativeLinkageTests(unittest.TestCase):
    def errors(self, layout):
        return "\n".join(verifier.validate_abi("arm64-v8a", layout)[0])

    def test_one_shared_runtime_accepts_arbitrary_matching_version(self):
        for version in ("VERS_1.19.2", "ORT_FUTURE_ABI"):
            with self.subTest(version=version):
                errors, resolved = verifier.validate_abi("arm64-v8a", shared_layout(version))
                self.assertEqual([], errors)
                self.assertEqual(2, len(resolved))
                self.assertTrue(all(RUNTIME in line and version in line for line in resolved))

    def test_independently_named_providers_can_have_different_versions(self):
        layout = shared_layout("JNI_ABI")
        layout[MAA] = elf(MAA, [OTHER_RUNTIME], [(API, "MAA_ABI", OTHER_RUNTIME)])
        layout[OTHER_RUNTIME] = elf(OTHER_RUNTIME, exports=[(API, "MAA_ABI")])
        self.assertEqual("", self.errors(layout))

    def test_old_735_jni_runtime_mismatch_is_reported(self):
        layout = shared_layout("VERS_1.19.2")
        layout[JNI] = elf(JNI, [RUNTIME], [(API, "VERS_1.20.0", RUNTIME)])
        errors = self.errors(layout)
        self.assertIn(f"{JNI}: {API}@VERS_1.20.0", errors)
        self.assertIn("symbol versions: VERS_1.19.2", errors)

    def test_checks_all_ort_imports_not_just_get_api_base(self):
        layout = shared_layout()
        layout[RUNTIME].symbols = [s for s in layout[RUNTIME].symbols if s.name != EXTRA_API]
        self.assertIn(EXTRA_API, self.errors(layout))

    def test_version_name_on_wrong_export_does_not_satisfy_import(self):
        layout = shared_layout("ORT_NEXT")
        layout[RUNTIME] = elf(RUNTIME, exports=[("OtherFunction", "ORT_NEXT"), (API, "ORT_OLD")])
        self.assertIn(f"{API}@ORT_NEXT is not exported", self.errors(layout))

    def test_symbol_index_selects_provider_even_when_version_names_equal(self):
        layout = shared_layout("ORT_ABI")
        layout[MAA] = elf(MAA, [RUNTIME, OTHER_RUNTIME], [
            ("HelperFunction", "ORT_ABI", RUNTIME), (API, "ORT_ABI", OTHER_RUNTIME)
        ])
        layout[OTHER_RUNTIME] = elf(OTHER_RUNTIME, exports=[("OtherFunction", "ORT_ABI")])
        self.assertIn(f"by {OTHER_RUNTIME} (symbol versions: <missing>)", self.errors(layout))

    def test_stale_version_need_filename_fails_after_needed_rename(self):
        layout = shared_layout()
        layout[MAA].needed = [OTHER_RUNTIME]
        layout[OTHER_RUNTIME] = elf(OTHER_RUNTIME, exports=[(API, "VERS_1.19.2")])
        self.assertIn("is absent from DT_NEEDED", self.errors(layout))

    def test_runtime_soname_and_definition_base_must_match_provider(self):
        for soname, base in ((None, RUNTIME), ("libwrong.so", RUNTIME), (RUNTIME, "libold.so")):
            with self.subTest(soname=soname, base=base):
                layout = shared_layout()
                layout[RUNTIME].soname = soname
                layout[RUNTIME].base_names = [base]
                self.assertTrue(self.errors(layout))

    def test_missing_version_definition_or_symbol_index_fails(self):
        for field in ("version_definitions", "symbol_versions"):
            with self.subTest(field=field):
                layout = shared_layout()
                setattr(layout[RUNTIME], field, {})
                self.assertIn("not exported with that version", self.errors(layout))

    def test_unversioned_or_unmapped_import_cannot_silently_pass(self):
        for change in ("unversioned", "unmapped", "none"):
            with self.subTest(change=change):
                layout = shared_layout()
                if change == "unversioned":
                    layout[MAA].symbols[0] = replace(layout[MAA].symbols[0], version=None)
                elif change == "unmapped":
                    layout[MAA].symbol_versions.clear()
                else:
                    layout[MAA].symbols.clear()
                self.assertTrue(self.errors(layout))

    def test_local_hidden_and_undefined_symbols_are_not_exports(self):
        for changes in ({"binding": "LOCAL"}, {"visibility": "HIDDEN"}, {"defined": False}):
            with self.subTest(changes=changes):
                layout = shared_layout()
                layout[RUNTIME].symbols[0] = replace(layout[RUNTIME].symbols[0], **changes)
                self.assertIn("not exported with that version", self.errors(layout))

    def test_missing_non_system_dependency_in_any_library_fails(self):
        for library in ("libc++_shared.so", "libprivate_vendor.so"):
            with self.subTest(library=library):
                layout = shared_layout()
                layout["libunrelated.so"] = elf("libunrelated.so", [library])
                self.assertIn(f"unresolved DT_NEEDED {library}", self.errors(layout))

    def test_duplicate_soname_under_distinct_filenames_fails(self):
        layout = shared_layout()
        layout[OTHER_RUNTIME] = deepcopy(layout[RUNTIME])
        self.assertIn(f"duplicate SONAME {RUNTIME}", self.errors(layout))

    def test_corrupt_version_hash_is_not_lost_during_validation(self):
        layout = shared_layout()
        layout[RUNTIME].errors.append(".gnu.version_d: corrupt hash")
        self.assertIn("corrupt hash", self.errors(layout))


class ApkAndCliTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "native sample.apk"
        # main() validates the executable; inspection itself is mocked below.
        self.readelf = Path(sys.executable)

    def write_apk(self, layouts, extra=()):
        infos = {}
        with zipfile.ZipFile(self.apk, "w") as archive:
            for abi, layout in layouts.items():
                for name, info in layout.items():
                    key = f"lib/{abi}/{name}"
                    archive.writestr(key, key.encode())
                    infos[key] = info
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                for name in extra:
                    archive.writestr(name, b"extra")
        return infos

    def inspect_from(self, infos):
        def inspect(path, _readelf):
            return deepcopy(infos[path.read_text()])
        return patch.object(verifier, "inspect_elf", side_effect=inspect)

    def test_every_abi_is_checked_without_cross_abi_resolution(self):
        arm = shared_layout()
        x64 = shared_layout()
        del x64[RUNTIME]
        infos = self.write_apk({"arm64-v8a": arm, "x86_64": x64})
        with self.inspect_from(infos):
            report = verifier.verify_apk(self.apk, self.readelf)
        self.assertEqual({"arm64-v8a": 3, "x86_64": 2}, report.libraries_per_abi)
        self.assertTrue(any("x86_64" in error and RUNTIME in error for error in report.errors))
        self.assertFalse(any("arm64-v8a" in error for error in report.errors))

    def test_repeated_library_names_in_different_abis_are_valid(self):
        infos = self.write_apk({"arm64-v8a": shared_layout(), "x86_64": shared_layout()})
        with self.inspect_from(infos):
            report = verifier.verify_apk(self.apk, self.readelf)
        self.assertEqual([], report.errors)
        self.assertEqual(4, len(report.resolved_imports))

    def test_duplicate_native_and_non_native_zip_entries_fail(self):
        for name in (f"lib/arm64-v8a/{RUNTIME}", "classes.dex"):
            with self.subTest(entry=name):
                self.write_apk({}, [name, name])
                with patch.object(verifier, "inspect_elf") as inspect:
                    report = verifier.verify_apk(self.apk, self.readelf)
                inspect.assert_not_called()
                self.assertIn(f"duplicate ZIP entry: {name}", report.errors)

    def test_empty_apk_missing_consumer_and_invalid_native_path_fail(self):
        layout = shared_layout()
        del layout[JNI]
        cases = [({}, []), ({"arm64-v8a": layout}, []), ({}, ["lib/../evil.so"])]
        for layouts, extra in cases:
            with self.subTest(extra=extra, abis=list(layouts)):
                infos = self.write_apk(layouts, extra)
                with self.inspect_from(infos):
                    self.assertTrue(verifier.verify_apk(self.apk, self.readelf).errors)

    def test_cli_pass_failure_and_bad_input_exit_codes(self):
        for broken, expected in ((False, 0), (True, 1)):
            with self.subTest(broken=broken):
                layout = shared_layout()
                if broken:
                    layout[JNI] = elf(JNI, [RUNTIME], [(API, "VERS_1.20.0", RUNTIME)])
                infos = self.write_apk({"arm64-v8a": layout})
                stdout, stderr = io.StringIO(), io.StringIO()
                with self.inspect_from(infos), redirect_stdout(stdout), redirect_stderr(stderr):
                    code = verifier.main([str(self.apk), "--llvm-readelf", str(self.readelf)])
                self.assertEqual(expected, code)
                self.assertIn("[FAIL]" if broken else "[PASS]", stderr.getvalue() if broken else stdout.getvalue())
        self.apk.write_bytes(b"not a zip")
        with redirect_stderr(io.StringIO()):
            self.assertEqual(2, verifier.main([str(self.apk), "--readelf", str(self.readelf)]))
            self.assertEqual(2, verifier.main([str(self.apk), "--readelf", str(self.apk) + ".missing"]))

    def test_readelf_failure_is_reported_with_packaged_filename(self):
        self.write_apk({"arm64-v8a": shared_layout()})
        with patch.object(verifier, "inspect_elf", side_effect=verifier.VerificationError("invalid ELF")):
            report = verifier.verify_apk(self.apk, self.readelf)
        self.assertTrue(any(f"arm64-v8a/{MAA}: invalid ELF" in error for error in report.errors))

    def test_readelf_errors_timeouts_and_warnings_fail_closed(self):
        for outcome in (
            subprocess.CompletedProcess([], 1, "", "invalid ELF"),
            subprocess.CompletedProcess([], 0, "", "warning: corrupt version table"),
            subprocess.TimeoutExpired("llvm-readelf", 30),
        ):
            with self.subTest(outcome=outcome):
                kwargs = {"side_effect": outcome} if isinstance(outcome, Exception) else {"return_value": outcome}
                with patch.object(verifier.subprocess, "run", **kwargs), self.assertRaises(verifier.VerificationError):
                    verifier.inspect_elf(self.apk, self.readelf)

    def test_readelf_command_preserves_symlink_name_and_accepts_spaces(self):
        output, data = elf_fixture(RUNTIME, exports=[(API, "ORT_NEXT")])
        path = Path(self.directory.name) / "test runtime.so"
        path.write_bytes(data)
        executable = Path(self.directory.name) / "NDK toolchain" / "llvm-readelf"
        with patch.object(verifier.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, output, "")) as run:
            info = verifier.inspect_elf(path, executable)
        command = run.call_args.args[0]
        self.assertEqual(str(executable), command[0])
        self.assertEqual(str(path), command[-1])
        self.assertEqual(RUNTIME, info.soname)
        self.assertEqual([], info.errors)


if __name__ == "__main__":
    unittest.main()
