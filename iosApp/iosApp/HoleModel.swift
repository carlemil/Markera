import Foundation
import ComposeApp

/// The Kotlin `HoleModel` bridge, backed by ONNX Runtime through `OrtRunner.c`.
/// Input is a pointer to the `[1, 3, inputSize, inputSize]` fp32 tensor (Kotlin keeps
/// the array pinned for the call, and ORT reads it in place), the return value the
/// `[1, 300, 6]` fp32 output; an empty `Data` means "no rows".
///
/// The session runs without ORT's CPU arena and memory pattern: with them an
/// inference at 1536 px left the app at 1.3 GB and the next one peaked at 2.5 GB,
/// which iOS answers by killing the app (MemTest.swift has the probe).
///
/// Opening the session (parsing ~40 MB and planning its memory) takes seconds, so it
/// happens on a background queue: done on the main thread at launch it froze the first
/// frame for over 2 s (a Sentry App Hang). `run` waits for it; it is only ever called
/// off the main thread.
///
/// Open, run and close all go through one serial queue, so a close can never free the
/// session under a running inference (a native run cannot be aborted): `close` only
/// enqueues the release, which runs once the inference has returned, and never blocks
/// its caller (Compose disposes on the main thread). A `run` after `close` reopens.
final class OrtHoleModel: NSObject, HoleModel {
    private let modelPath: String
    private let lean: Bool
    private let queue = DispatchQueue(label: "se.kjellstrand.markera.ort", qos: .userInitiated)
    /// Touched only on `queue`.
    private var ort: OpaquePointer?

    /// `lean: false` is the arena + memory pattern default, kept for MemTest to compare.
    init(modelPath: String, lean: Bool = true) {
        self.modelPath = modelPath
        self.lean = lean
        super.init()
        queue.async { [self] in _ = openIfNeeded() }
    }

    /// On `queue`: the open session, opening it first if needed; nil if that failed.
    private func openIfNeeded() -> OpaquePointer? {
        if ort == nil {
            ort = markera_ort_open(modelPath, 4, lean ? 1 : 0)
            if ort == nil {
                print("OrtHoleModel: \(modelPath): \(String(cString: markera_ort_last_error()))")
            }
        }
        return ort
    }

    /// Blocks until the session is open; nil if it failed to open.
    func session() -> OpaquePointer? {
        queue.sync { openIfNeeded() }
    }

    func run(input: UnsafeMutableRawPointer, inputFloats: Int32, inputSize: Int32) -> Data {
        queue.sync {
            guard let ort = openIfNeeded() else { return Data() }
            var out: UnsafeMutablePointer<Float>? = nil
            let count = markera_ort_run(ort, input.assumingMemoryBound(to: Float.self),
                                        Int(inputFloats), inputSize, &out)
            defer { markera_ort_free(out) }
            guard count >= 0, let out = out else {
                print("OrtHoleModel.run: \(String(cString: markera_ort_last_error()))")
                return Data()
            }
            return Data(bytes: out, count: count * MemoryLayout<Float>.size)
        }
    }

    /// Releases the session, env and memory info once any running inference is done.
    func close() {
        queue.async { [self] in
            markera_ort_close(ort)
            ort = nil
        }
    }

    /// Waits for everything queued so far (an open, a run or a release). MemTest only.
    func drain() {
        queue.sync {}
    }
}
