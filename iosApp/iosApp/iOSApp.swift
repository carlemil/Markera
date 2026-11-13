import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    /// One ~40 MB ORT session for the whole process, opened in the background; nil if
    /// the model is missing (a session that fails to open makes every scan fail).
    static let holeModel = Bundle.main.path(forResource: "best", ofType: "onnx")
        .map { OrtHoleModel(modelPath: $0) }

    init() {
        SentryReporter.start()
        #if DEBUG
        MemTest.runIfRequested()
        #endif
    }

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
