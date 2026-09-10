# Synthetic LALC resource contract fixture

`source.zip` is an authored test archive, not an upstream release. It contains a
minimal two-node pipeline, a string language table, complete 1x1 RGB PNGs, action
declarations read as text, and five ONNX protobuf interface declarations.

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
