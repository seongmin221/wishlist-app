import Shared
import SwiftUI

struct ContentView: View {
    @Environment(\.colorScheme) private var colorScheme
    private let appInfo = AppInfo()

    var body: some View {
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
