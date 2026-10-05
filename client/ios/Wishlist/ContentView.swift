import Shared
import SwiftUI

struct ContentView: View {
    @Environment(\.colorScheme) private var colorScheme
    private let appInfo = AppInfo()

    var body: some View {
        #if DEBUG
        // Task 5 iOS 라우터 spike 진입점. Task 7에서 Spike/와 함께 지운다.
        if ProcessInfo.processInfo.arguments.contains("-RouterSpike") {
            RouterSpikeView()
        } else {
            placeholder
        }
        #else
        placeholder
        #endif
    }

    private var placeholder: some View {
        ZStack {
            background.ignoresSafeArea()
            Text(appInfo.displayName)
                .font(.largeTitle)
                .foregroundStyle(foreground)
        }
    }

    private var background: Color {
        colorScheme == .dark
            ? Color(red: 29 / 255, green: 29 / 255, blue: 29 / 255)
            : Color(red: 248 / 255, green: 248 / 255, blue: 248 / 255)
    }

    private var foreground: Color {
        colorScheme == .dark
            ? Color(red: 244 / 255, green: 243 / 255, blue: 240 / 255)
            : Color(red: 29 / 255, green: 29 / 255, blue: 29 / 255)
    }
}
