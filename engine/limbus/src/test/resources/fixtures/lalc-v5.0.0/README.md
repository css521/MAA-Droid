# LALC v5.0.0 JVM pipeline fixtures

## Provenance and license

- Upstream: [HSLix/LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany), by HSLix and the LALC contributors.
- Tag: `v5.0.0`.
- Exact Git commit: `431b432e22f0b0da08b95d7c478fa213be20b3e8`.
- `config/task/*.json`: all ten files from [`lalc_backend/config/task/`](https://github.com/HSLix/LixAssistantLimbusCompany/tree/431b432e22f0b0da08b95d7c478fa213be20b3e8/lalc_backend/config/task), copied byte for byte from Git blobs, including upstream CRLF. No nodes, parameters, or comments were rewritten.
- `LICENSE`: verbatim copy of the [upstream root license](https://github.com/HSLix/LixAssistantLimbusCompany/blob/431b432e22f0b0da08b95d7c478fa213be20b3e8/LICENSE). The copied pipeline source retains LALC's GNU Affero General Public License version 3 terms and upstream attribution, consistent with this project's `docs/zh-cn/develop/THIRD_PARTY_NOTICES.md`. No relicensing is intended.
- `template-names.txt`: derived text inventory of the same commit's tracked `lalc_backend/img/**/*.png` basenames, deduplicated and sorted, UTF-8 with LF and a final newline. There are 622 tracked PNG paths and 563 distinct names. This is filename metadata only; no image bytes were copied. Working-tree or ignored files are not included. Read Git's NUL-delimited paths (`ls-tree -z`) so quoted non-ASCII filenames are not accidentally omitted.
- `SHA256SUMS`: SHA-256 of each copied JSON, the copied license, and the derived name inventory. Paths are relative to this directory. The JSON/license hashes are also the hashes of the corresponding upstream blobs.

These fixtures contain only pipeline JSON, filename metadata, and license/documentation text. LALC's code license does not imply independent permission for game art, icons, or model weights; none of those assets are redistributed here. The inventory verifies template references, not image integrity or recognition accuracy.

## Tests and failure behavior

`LalcV500Fixtures.taskFiles()` reads these resources through the JVM classpath and verifies their SHA-256 before returning the filename-to-JSON map. It enumerates the ten required task files explicitly: a missing file cannot silently shrink the loaded pipeline. Missing files, missing hash entries, or changed bytes fail the test; there is no clone lookup, download, or `Assume` fallback.

The fixture is used by `PipelineRegistryTest`, `UpstreamPipelineRunTest`, and `LimbusTaskContractTest`. The pinned data contains 133 nodes, 45 referenced actions, and 50 template references. Pipeline runner tests use existing fake recognition/input implementations; these are JVM control-flow tests, not Android or native recognition tests.

## Reproducing the files

With any clone of the upstream repository available, use its Git object database rather than copying a potentially changed working tree. For example, from this directory:

```sh
LALC_REPO=/path/to/LixAssistantLimbusCompany
LALC_REV=431b432e22f0b0da08b95d7c478fa213be20b3e8
git -C "$LALC_REPO" rev-parse 'v5.0.0^{commit}'
# The result must equal LALC_REV.
for name in basic battle error event luxcavation mail main mirror reward utils; do
  git -C "$LALC_REPO" show "$LALC_REV:lalc_backend/config/task/$name.json" > "config/task/$name.json"
done
git -C "$LALC_REPO" show "$LALC_REV:LICENSE" > LICENSE
python3 - "$LALC_REPO" "$LALC_REV" <<'PY'
import hashlib
from pathlib import Path, PurePosixPath
import subprocess
import sys

repo, revision = sys.argv[1:]
paths = subprocess.check_output([
    "git", "-C", repo, "ls-tree", "-r", "-z", "--name-only", revision,
    "--", "lalc_backend/img",
]).decode("utf-8").split("\0")
names = sorted({PurePosixPath(p).stem for p in paths if p.lower().endswith(".png")})
Path("template-names.txt").write_bytes(("\n".join(names) + "\n").encode("utf-8"))
files = sorted([*Path("config/task").glob("*.json"), Path("LICENSE"), Path("template-names.txt")])
manifest = "".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.as_posix()}\n" for p in files)
Path("SHA256SUMS").write_bytes(manifest.encode("utf-8"))
PY
```

The local `.gitattributes` preserves upstream CRLF in the copied files, so checkout newline conversion cannot invalidate the hashes. Updating to another upstream revision requires reviewing the JSON and pipeline assertions together with the provenance and checksum manifest; do not refresh hashes merely to accept an unexplained difference.

## Remaining external-resource tests

This fixture does not replace these existing integration checks:

- `ResourcePackTemplateIndexUpstreamTest` in `recognize/ResourcePackTemplateIndexTest.kt`: indexes a real image directory tree, language variants, and tags. A global basename inventory does not replace those resource-layout checks.
- The real-model metadata case in `recognize/ClassifierMathTest.kt`: `ClassifierSpec.load` requires each model file to exist before reading its text metadata. The synthetic metadata tests already use temporary files; this real-resource check is left separate, without copying model weights or replacing them with misleading empty models.
- The real-package case in `LimbusPackLayoutTest.kt`: consumes an externally built ZIP and verifies that actual OCR model entries are included. A pipeline-only fixture cannot establish that packaging guarantee.

Those tests remain outside this pipeline-fixture migration and still have their existing external-resource assumptions.
