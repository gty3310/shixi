// 弦歌 Mac 版 · 薄壳（v0.1.0）
// 职责只有一件：开一个窗口，用 WKWebView 承载 Web/ 的阅读界面。
// 界面与内容渲染全在 Web 核心（Web/index.html 等），内容数据由 build.sh
// 在构建时从共用资产拷入 .app 资源（见 ../README.md「内容引用方式」）。
// v0.1.0 纯阅读：无音频、无遍数、无原生桥接；后续版本在此加桥。
import AppKit
import WebKit

/// 把 xiange://app/<path> 映射到 .app 资源里的 Web/ 目录，
/// 让页面内的 fetch('data/…') 在本地文件上也能工作（file:// 下 fetch 受限）。
final class WebSchemeHandler: NSObject, WKURLSchemeHandler {
    private let root: URL

    init(root: URL) { self.root = root }

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url else {
            urlSchemeTask.didFailWithError(NSError(domain: "xiange", code: 400))
            return
        }
        var rel = url.path
        if rel.hasPrefix("/") { rel.removeFirst() }
        if rel.isEmpty { rel = "index.html" }
        let file = root.appendingPathComponent(rel)
        guard file.path.hasPrefix(root.path),
              let data = try? Data(contentsOf: file) else {
            let resp = HTTPURLResponse(url: url, statusCode: 404, httpVersion: nil, headerFields: nil)!
            urlSchemeTask.didReceive(resp)
            urlSchemeTask.didReceive(Data())
            urlSchemeTask.didFinish()
            return
        }
        let resp = HTTPURLResponse(
            url: url, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": Self.mime(for: file.pathExtension),
                           "Content-Length": String(data.count)])!
        urlSchemeTask.didReceive(resp)
        urlSchemeTask.didReceive(data)
        urlSchemeTask.didFinish()
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}

    private static func mime(for ext: String) -> String {
        switch ext.lowercased() {
        case "html": return "text/html; charset=utf-8"
        case "css": return "text/css; charset=utf-8"
        case "js": return "text/javascript; charset=utf-8"
        case "json": return "application/json; charset=utf-8"
        default: return "application/octet-stream"
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow!
    private var schemeHandler: WebSchemeHandler!

    func applicationDidFinishLaunching(_ notification: Notification) {
        let webRoot = Bundle.main.resourceURL!.appendingPathComponent("Web")
        schemeHandler = WebSchemeHandler(root: webRoot)

        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(schemeHandler, forURLScheme: "xiange")
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.load(URLRequest(url: URL(string: "xiange://app/index.html")!))

        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1240, height: 800),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered, defer: false)
        window.title = "弦歌"
        window.minSize = NSSize(width: 860, height: 540)
        window.contentView = webView
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return true
    }
}

let delegate = AppDelegate()
NSApplication.shared.delegate = delegate
NSApplication.shared.run()
