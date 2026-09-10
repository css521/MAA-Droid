# LALC resource contract fixture

`source.zip` is an authored test archive, not an upstream release. It contains a
synthetic two-node main pipeline, the upstream v5.0.0 mail pipeline with minimal
synthetic surrounding routes in `mail-support.json`, a string language table,
complete synthetic 1x1 RGB PNGs, action declarations read as text, and five ONNX
protobuf interface declarations. The three mail template names use copies of the
synthetic image; no game art or upstream model weights are included.

## Mail provenance and license

- Upstream: [HSLix/LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany), by HSLix and the LALC contributors.
- Tag: `v5.0.0`; exact commit: `431b432e22f0b0da08b95d7c478fa213be20b3e8`.
- Archive entry `fixture/lalc_backend/config/task/mail.json` is a byte-for-byte copy, including CRLF, of [`lalc_backend/config/task/mail.json`](https://github.com/HSLix/LixAssistantLimbusCompany/blob/431b432e22f0b0da08b95d7c478fa213be20b3e8/lalc_backend/config/task/mail.json).
- Archive entry `fixture/LICENSE` is the unmodified upstream root license. The copied mail source retains LALC's GNU Affero General Public License version 3 terms and attribution; no relicensing is intended. The installer ignores this entry outside `lalc_backend`.
- Both entries were copied from the repository's pinned `engine/limbus/src/test/resources/fixtures/lalc-v5.0.0` fixture, whose README documents Git-blob reproduction and license provenance.

SHA-256 of copied entries (paths relative to the ZIP):

```text
2163480e1110ca5e132d516303aba03b871a091fda1de8be7e2c0d93d8f5bdd2  fixture/lalc_backend/config/task/mail.json
6f1e622c82a380075843bb084a7ec3b1f1d12a4a02526d75e78b0924a860aa75  fixture/LICENSE
```

The archive uses sorted paths, fixed `1980-01-01` timestamps and deflate compression.
Its SHA-256 is `c81c4f5c882d89b03dae703ae46f18d5d4f9d56027d73d270afefaf169470c07`.

## Test scope

The ONNX declarations use IR 10 and FLOAT NCHW image inputs. Detection has dynamic
height/width; recognition has height 48, dynamic width and four output classes
for two dictionary entries plus CTC blank/space. Classifier declarations have
10x10 inputs and one output class matching their metadata. They contain no
operators or weights and MUST NOT be used for inference.

The installation tests rewrite archive entries to prove that incompatible updates
are rejected by the real LimbusResourcePack before AtomicResourceInstaller changes
the active resource directory. They need neither a sibling upstream checkout nor
Android/OpenCV/ONNX native libraries. Production model loading remains separately
validated with real upstream assets.

Mail cases change a valid-reference route and a claim target (even with Mail
disabled by default), assert the existing tree remains byte-for-byte unchanged,
and accept compatible description, timing, threshold and template image updates.
Validation must not persist the runtime Android mail rewrites or forced enable.
These are staging/activation contract tests, not mailbox recognition or real-device
mail-collection tests.
