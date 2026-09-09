import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    /// One ~40 MB ORT session for the whole process; nil if the model is
    /// missing or fails to load (the Kotlin side then detects nothing).
    static let holeModel = Bundle.main.path(forResource: "best", ofType: "onnx")
        .flatMap { OrtHoleModel(modelPath: $0) }

    var body: some Scene {
        WindowGroup { ComposeView().ignoresSafeArea() }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(holeModel: iOSApp.holeModel)
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
