import Foundation
import ComposeApp
import OnnxRuntimeBindings

/// The Kotlin `HoleModel` bridge, backed by ONNX Runtime.
/// Input is the `[1, 3, inputSize, inputSize]` fp32 tensor as raw bytes, the
/// return value the `[1, 300, 6]` fp32 output; an empty `Data` means "no rows".
final class OrtHoleModel: NSObject, HoleModel {
    private let env: ORTEnv
    private let session: ORTSession
    private let inputName: String
    private let outputName: String

    init?(modelPath: String) {
        do {
            let env = try ORTEnv(loggingLevel: ORTLoggingLevel.warning)
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(4)
            let session = try ORTSession(env: env, modelPath: modelPath, sessionOptions: options)
            self.env = env
            self.session = session
            self.inputName = try session.inputNames()[0]
            self.outputName = try session.outputNames()[0]
        } catch {
            print("OrtHoleModel: \(modelPath): \(error)")
            return nil
        }
        super.init()
    }

    func run(input: Data, inputSize: Int32) -> Data {
        do {
            let value = try ORTValue(
                tensorData: NSMutableData(data: input),
                elementType: ORTTensorElementDataType.float,
                shape: [1, 3, NSNumber(value: inputSize), NSNumber(value: inputSize)])
            let outputs = try session.run(
                withInputs: [inputName: value],
                outputNames: [outputName],
                runOptions: nil)
            guard let out = try outputs[outputName]?.tensorData() else { return Data() }
            // tensorData() can alias memory owned by the ORTValue, so copy.
            return Data(bytes: out.bytes, count: out.length)
        } catch {
            print("OrtHoleModel.run: \(error)")
            return Data()
        }
    }
}
