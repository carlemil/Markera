import Foundation
import ComposeApp

/// The Kotlin `HoleModel` bridge, backed by ONNX Runtime through `OrtRunner.c`.
/// Input is the `[1, 3, inputSize, inputSize]` fp32 tensor as raw bytes, the
/// return value the `[1, 300, 6]` fp32 output; an empty `Data` means "no rows".
///
/// The session runs without ORT's CPU arena and memory pattern: with them an
/// inference at 1536 px left the app at 1.3 GB and the next one peaked at 2.5 GB,
/// which iOS answers by killing the app (MemTest.swift has the probe).
final class OrtHoleModel: NSObject, HoleModel {
    private let ort: OpaquePointer

    /// `lean: false` is the arena + memory pattern default, kept for MemTest to compare.
    init?(modelPath: String, lean: Bool = true) {
        guard let ort = markera_ort_open(modelPath, 4, lean ? 1 : 0) else {
            print("OrtHoleModel: \(modelPath): \(String(cString: markera_ort_last_error()))")
            return nil
        }
        self.ort = ort
        super.init()
    }

    func run(input: Data, inputSize: Int32) -> Data {
        var out: UnsafeMutablePointer<Float>? = nil
        let count = input.withUnsafeBytes { bytes in
            markera_ort_run(ort, bytes.bindMemory(to: Float.self).baseAddress, inputSize, &out)
        }
        defer { markera_ort_free(out) }
        guard count >= 0, let out = out else {
            print("OrtHoleModel.run: \(String(cString: markera_ort_last_error()))")
            return Data()
        }
        return Data(bytes: out, count: count * MemoryLayout<Float>.size)
    }
}
