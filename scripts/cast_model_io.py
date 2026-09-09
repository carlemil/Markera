"""Give an fp16 YOLO export fp32 inputs and outputs.

ONNX Runtime's Objective-C API (the iOS app) has no fp16 tensor type, so the
model takes/returns fp32 and casts at its edges. The body stays fp16 and the
Cast is round-to-nearest-even like Fp16Conversions.floatToFp16, so Android,
:eval and iOS see identical numbers. Android/:eval already handle either
input type.

    python scripts/cast_model_io.py best.fp16.onnx best.onnx
"""
import sys
import onnx
from onnx import TensorProto, helper

src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
g = m.graph
for v in list(g.input) + list(g.output):
    if v.type.tensor_type.elem_type != TensorProto.FLOAT16:
        continue
    inner = v.name + "_fp16"
    v.type.tensor_type.elem_type = TensorProto.FLOAT
    if v in g.input:
        g.node.insert(0, helper.make_node("Cast", [v.name], [inner], to=TensorProto.FLOAT16))
        for n in g.node[1:]:
            n.input[:] = [inner if i == v.name else i for i in n.input]
    else:
        for n in g.node:
            n.output[:] = [inner if o == v.name else o for o in n.output]
        g.node.append(helper.make_node("Cast", [inner], [v.name], to=TensorProto.FLOAT))
onnx.checker.check_model(m)
onnx.save(m, dst)
print({v.name: v.type.tensor_type.elem_type for v in list(g.input) + list(g.output)})
