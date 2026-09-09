import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup { ComposeView().ignoresSafeArea() }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(holeModel: nil)
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
