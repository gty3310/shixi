// 弦歌 Mac 版 · 薄壳
// v0.1.0：开窗口，用 WKWebView 承载 Web/ 的阅读界面。
// v0.2.0：加原生桥——本地音频绑定（NSOpenPanel + 安全书签持久化）、
//   xiange://audio/<bookId> 取流（支持 Range，播放器可拖进度）、
//   遍数账 chapter_stats.json 在 Application Support 下读/写（原子落盘）。
// 界面、计数与播放判定全在 Web 核心；壳只做系统能力与文件服务。
// 诵读音频永不进 repo、永不进 .app：绑定只存书签，文件留在用户本机。
import AppKit
import WebKit
import UniformTypeIdentifiers

/// xiange://app/<path> → .app 资源 Web/ 目录（页面 fetch 本地数据用）；
/// xiange://audio/<bookId> → 该书绑定的本地音频文件（支持 Range 请求）。
final class WebSchemeHandler: NSObject, WKURLSchemeHandler {
    private let root: URL
    var audioProvider: ((String) -> URL?)?

    init(root: URL) { self.root = root }

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url else {
            urlSchemeTask.didFailWithError(NSError(domain: "xiange", code: 400))
            return
        }
        if url.host == "audio" {
            let bookId = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            if let file = audioProvider?(bookId) {
                serveFile(urlSchemeTask, url: url, file: file)
            } else {
                notFound(urlSchemeTask, url: url)
            }
            return
        }
        var rel = url.path
        if rel.hasPrefix("/") { rel.removeFirst() }
        if rel.isEmpty { rel = "index.html" }
        let file = root.appendingPathComponent(rel)
        guard file.path.hasPrefix(root.path),
              let data = try? Data(contentsOf: file) else {
            notFound(urlSchemeTask, url: url)
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

    private func notFound(_ task: WKURLSchemeTask, url: URL) {
        let resp = HTTPURLResponse(url: url, statusCode: 404, httpVersion: nil, headerFields: nil)!
        task.didReceive(resp)
        task.didReceive(Data())
        task.didFinish()
    }

    /// 音频文件服务：无 Range 时整段流式回，有 Range 时回 206 分段，
    /// 使 <audio> 能拿到时长并支持 seek。
    private func serveFile(_ task: WKURLSchemeTask, url: URL, file: URL) {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: file.path),
              let size = (attrs[.size] as? NSNumber)?.int64Value,
              let fh = try? FileHandle(forReadingFrom: file) else {
            notFound(task, url: url)
            return
        }
        defer { try? fh.close() }
        var start: Int64 = 0
        var end: Int64 = size - 1
        var status = 200
        var headers: [String: String] = [
            "Content-Type": Self.audioMime(for: file.pathExtension),
            "Accept-Ranges": "bytes"
        ]
        if let range = task.request.value(forHTTPHeaderField: "Range"),
           range.hasPrefix("bytes=") {
            let parts = String(range.dropFirst(6)).components(separatedBy: "-")
            let first = parts.first ?? ""
            let second = parts.count > 1 ? parts[1] : ""
            if first.isEmpty, let suffix = Int64(second) {
                start = max(0, size - suffix)
            } else if let s = Int64(first) {
                start = s
                if let e = Int64(second) { end = e }
            }
            end = min(end, size - 1)
            if start > end || start >= size {
                let resp = HTTPURLResponse(url: url, statusCode: 416, httpVersion: nil,
                    headerFields: ["Content-Range": "bytes */\(size)"])!
                task.didReceive(resp)
                task.didReceive(Data())
                task.didFinish()
                return
            }
            status = 206
            headers["Content-Range"] = "bytes \(start)-\(end)/\(size)"
        }
        let len = end - start + 1
        headers["Content-Length"] = String(len)
        let resp = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil,
                                   headerFields: headers)!
        task.didReceive(resp)
        fh.seek(toFileOffset: UInt64(start))
        var remaining = len
        while remaining > 0 {
            let chunk = fh.readData(ofLength: Int(min(262144, remaining)))
            if chunk.isEmpty { break }
            task.didReceive(chunk)
            remaining -= Int64(chunk.count)
        }
        task.didFinish()
    }

    private static func mime(for ext: String) -> String {
        switch ext.lowercased() {
        case "html": return "text/html; charset=utf-8"
        case "css": return "text/css; charset=utf-8"
        case "js": return "text/javascript; charset=utf-8"
        case "json": return "application/json; charset=utf-8"
        default: return "application/octet-stream"
        }
    }

    private static func audioMime(for ext: String) -> String {
        switch ext.lowercased() {
        case "mp3": return "audio/mpeg"
        case "m4a", "aac": return "audio/mp4"
        case "wav": return "audio/wav"
        case "flac": return "audio/flac"
        case "ogg": return "audio/ogg"
        default: return "audio/mpeg"
        }
    }
}

/// Web → 壳消息桥（window.webkit.messageHandlers.xiange）
/// 命令：pickAudio{bookId} / getAudioState / loadStats / saveStats{json}
final class Bridge: NSObject, WKScriptMessageHandler {
    var onPickAudio: ((String) -> Void)?
    var onGetAudioState: (() -> Void)?
    var onLoadStats: (() -> Void)?
    var onSaveStats: ((String) -> Void)?

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard message.name == "xiange",
              let body = message.body as? [String: Any],
              let cmd = body["cmd"] as? String else { return }
        switch cmd {
        case "pickAudio": onPickAudio?(body["bookId"] as? String ?? "")
        case "getAudioState": onGetAudioState?()
        case "loadStats": onLoadStats?()
        case "saveStats": onSaveStats?(body["json"] as? String ?? "")
        default: break
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow!
    private var webView: WKWebView!
    private var schemeHandler: WebSchemeHandler!
    private var bridge: Bridge!
    private var bookmarks: [String: Data] = [:]
    private var audioFiles: [String: URL] = [:]

    private var statsFile: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first!
        return base.appendingPathComponent("弦歌/chapter_stats.json")
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        resolveBookmarks()

        let webRoot = Bundle.main.resourceURL!.appendingPathComponent("Web")
        schemeHandler = WebSchemeHandler(root: webRoot)
        schemeHandler.audioProvider = { [weak self] bookId in self?.audioFiles[bookId] }

        bridge = Bridge()
        bridge.onPickAudio = { [weak self] bookId in self?.pickAudio(bookId: bookId) }
        bridge.onGetAudioState = { [weak self] in self?.pushAudioState() }
        bridge.onLoadStats = { [weak self] in self?.pushStats() }
        bridge.onSaveStats = { [weak self] json in self?.saveStats(json) }

        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(schemeHandler, forURLScheme: "xiange")
        config.userContentController.add(bridge, name: "xiange")
        webView = WKWebView(frame: .zero, configuration: config)
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

    // ---------- 音频绑定（书签持久化，只存本机） ----------

    private func resolveBookmarks() {
        guard let saved = UserDefaults.standard.dictionary(forKey: "audioBookmarks")
                as? [String: Data] else { return }
        bookmarks = saved
        var changed = false
        for (bookId, data) in saved {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: data,
                                     options: .withSecurityScope,
                                     relativeTo: nil,
                                     bookmarkDataIsStale: &stale) else { continue }
            _ = url.startAccessingSecurityScopedResource()
            audioFiles[bookId] = url
            if stale, let nd = try? url.bookmarkData(options: .withSecurityScope,
                                                      includingResourceValuesForKeys: nil,
                                                      relativeTo: nil) {
                bookmarks[bookId] = nd
                changed = true
            }
        }
        if changed { UserDefaults.standard.set(bookmarks, forKey: "audioBookmarks") }
    }

    private func pickAudio(bookId: String) {
        guard !bookId.isEmpty else { return }
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType.audio]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.message = "为这本书选择本地音频文件（只留在本机，不上传）"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        if let old = audioFiles[bookId] { old.stopAccessingSecurityScopedResource() }
        if let data = try? url.bookmarkData(options: .withSecurityScope,
                                             includingResourceValuesForKeys: nil,
                                             relativeTo: nil) {
            bookmarks[bookId] = data
            UserDefaults.standard.set(bookmarks, forKey: "audioBookmarks")
        }
        _ = url.startAccessingSecurityScopedResource()
        audioFiles[bookId] = url
        pushAudioState()
    }

    private func pushAudioState() {
        var obj: [String: Any] = [:]
        for (bookId, url) in audioFiles {
            obj[bookId] = ["bound": true, "name": url.lastPathComponent]
        }
        callJS("onAudioState", obj)
    }

    // ---------- 遍数账（Application Support，原子落盘） ----------

    private func pushStats() {
        let text = (try? String(contentsOf: statsFile, encoding: .utf8)) ?? ""
        callJS("onStats", text)
    }

    private func saveStats(_ json: String) {
        let file = statsFile
        try? FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(),
            withIntermediateDirectories: true)
        (json as NSString).write(to: file, atomically: true,
                                 encoding: String.Encoding.utf8.rawValue)
    }

    private func callJS(_ fn: String, _ arg: Any) {
        guard let data = try? JSONSerialization.data(withJSONObject: arg,
                                                     options: [.fragmentsAllowed]),
              let s = String(data: data, encoding: .utf8) else { return }
        webView?.evaluateJavaScript("window.XGNative&&window.XGNative.\(fn)(\(s))")
    }
}

let delegate = AppDelegate()
NSApplication.shared.delegate = delegate
NSApplication.shared.run()
