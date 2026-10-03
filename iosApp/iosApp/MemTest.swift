#if DEBUG
import Foundation
import Darwin

/// Debug-only memory probe for the hole model: launch with `MARKERA_MEMTEST=1`
/// (`SIMCTL_CHILD_MARKERA_MEMTEST=1 xcrun simctl launch --console-pty …`) and it prints
/// the app's memory footprint before and after loading the ORT session and the peak
/// during four inferences at the model's 1536 px input. Lines start with `MEMTEST`.
enum MemTest {
    private static let inputSize = 1536

    static func runIfRequested() {
        guard ProcessInfo.processInfo.environment["MARKERA_MEMTEST"] != nil else { return }
        DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 3) { run() }
    }

    /// `phys_footprint`: the number iOS compares against the per-app memory limit.
    static func footprintMB() -> Double {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return kr == KERN_SUCCESS ? Double(info.phys_footprint) / 1_048_576 : -1
    }

    private static func run() {
        print(String(format: "MEMTEST idle app: %.0f MB", footprintMB()))
        // `MARKERA_MEMTEST=arena` measures ORT's defaults instead of the app's lean session.
        let arena = ProcessInfo.processInfo.environment["MARKERA_MEMTEST"] == "arena"
        let loaded: OrtHoleModel? = arena
            ? Bundle.main.path(forResource: "best", ofType: "onnx").flatMap { OrtHoleModel(modelPath: $0, lean: false) }
            : iOSApp.holeModel
        guard let model = loaded else { print("MEMTEST no model"); return }
        print(String(format: "MEMTEST %@ session loaded: %.0f MB", arena ? "arena" : "lean", footprintMB()))

        let lock = NSLock()
        var peak = 0.0
        var overall = 0.0
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "memtest.sampler"))
        timer.schedule(deadline: .now(), repeating: .milliseconds(20))
        timer.setEventHandler {
            let now = footprintMB()
            lock.lock(); peak = max(peak, now); lock.unlock()
        }
        timer.resume()

        for pass in 1...4 {
            let input = Data(count: 3 * inputSize * inputSize * 4)
            let started = Date()
            let out = model.run(input: input, inputSize: Int32(inputSize))
            // Per-pass peak: a footprint that keeps climbing from pass to pass is a leak.
            lock.lock(); let soFar = peak; peak = 0; overall = max(overall, soFar); lock.unlock()
            print(String(format: "MEMTEST inference %d: %.1f s, %d output bytes, peak %.0f MB, after %.0f MB",
                         pass, Date().timeIntervalSince(started), out.count, soFar, footprintMB()))
        }
        timer.cancel()
        print(String(format: "MEMTEST done: peak %.0f MB, settled %.0f MB", overall, footprintMB()))
    }
}
#endif
