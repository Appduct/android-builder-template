import UIKit
import WebKit

class ViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, UIScrollViewDelegate {
    private var webView: WKWebView!
    private var refreshControl: UIRefreshControl?
    private var progressView: UIProgressView!
    private let websiteURL = "%%WEBSITE_URL%%"
    private let pullToRefreshEnabled = %%PULL_TO_REFRESH%%
    private let darkMode = %%DARK_MODE%%
    private let headerColorHex = "%%HEADER_COLOR%%"
    // Reload on foreground after even a brief absence so users always see the latest
    // content when returning to the app — without needing to log out / back in.
    private var lastBackgroundedAt: Date?
    private let resumeReloadThreshold: TimeInterval = 10

    // Push-style sync polling
    private let syncProjectId = "%%PROJECT_ID%%"
    private let syncSupabaseUrl = "%%SUPABASE_URL%%"
    private let syncAnonKey = "%%SUPABASE_ANON_KEY%%"
    private let syncPollInterval: TimeInterval = 5
    private var lastSignalAt: String = ""
    private var syncSignalInitialized: Bool = false
    private var lastSyncReloadAt: Date = .distantPast
    private var syncTimer: Timer?
    private var syncSocket: URLSessionWebSocketTask?
    private var syncRef: Int = 1
    private var syncHeartbeatTimer: Timer?
    private lazy var syncUrlSession = URLSession(configuration: .default)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(hex: headerColorHex) ?? .systemBackground
        if darkMode { overrideUserInterfaceStyle = .dark }
        setupWebView()
        setupProgressView()
        loadInitialURL()
        startSyncPolling()
        checkSyncSignal()
        startSyncRealtime()
    }

    private func setupWebView() {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        // Persistent data store: enables cookies + localStorage that survive app restarts
        config.websiteDataStore = WKWebsiteDataStore.default()
        let prefs = WKWebpagePreferences()
        prefs.allowsContentJavaScript = true
        config.defaultWebpagePreferences = prefs

        webView = WKWebView(frame: view.bounds, configuration: config)
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.delegate = self
        // Respect safe area so the bottom of pages is reachable on devices with a home indicator
        webView.scrollView.contentInsetAdjustmentBehavior = .always
        webView.scrollView.alwaysBounceVertical = true
        webView.addObserver(self, forKeyPath: #keyPath(WKWebView.estimatedProgress), options: .new, context: nil)
        view.addSubview(webView)

        if pullToRefreshEnabled {
            let rc = UIRefreshControl()
            rc.addTarget(self, action: #selector(handleRefresh), for: .valueChanged)
            webView.scrollView.refreshControl = rc
            refreshControl = rc
        }

        // Real-time: reload when app returns to foreground (only after a meaningful absence)
        NotificationCenter.default.addObserver(
            self, selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification, object: nil
        )
        NotificationCenter.default.addObserver(
            self, selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification, object: nil
        )
    }

    @objc private func appDidEnterBackground() {
        lastBackgroundedAt = Date()
        stopSyncPolling()
        stopSyncRealtime()
    }

    @objc private func appWillEnterForeground() {
        // Always nudge SPA pages to refetch via visibilitychange/focus events.
        webView?.evaluateJavaScript(
            "(function(){try{document.dispatchEvent(new Event('visibilitychange'));window.dispatchEvent(new Event('focus'));}catch(e){}})();",
            completionHandler: nil
        )
        startSyncPolling()
        startSyncRealtime()
        checkSyncSignal()
        // And do a full reload if the app was backgrounded for >10s.
        guard let bgAt = lastBackgroundedAt,
              Date().timeIntervalSince(bgAt) >= resumeReloadThreshold else { return }
        loadFreshWebsite()
    }

    private func startSyncPolling() {
        stopSyncPolling()
        guard !syncProjectId.isEmpty, !syncProjectId.hasPrefix("%%"),
              !syncSupabaseUrl.isEmpty, !syncSupabaseUrl.hasPrefix("%%"),
              !syncAnonKey.isEmpty, !syncAnonKey.hasPrefix("%%") else { return }
        syncTimer = Timer.scheduledTimer(withTimeInterval: syncPollInterval, repeats: true) { [weak self] _ in
            self?.checkSyncSignal()
        }
        if let syncTimer { RunLoop.main.add(syncTimer, forMode: .common) }
    }

    private func stopSyncPolling() {
        syncTimer?.invalidate()
        syncTimer = nil
    }

    private func syncConfigured() -> Bool {
        return !syncProjectId.isEmpty && !syncProjectId.hasPrefix("%%") &&
               !syncSupabaseUrl.isEmpty && !syncSupabaseUrl.hasPrefix("%%") &&
               !syncAnonKey.isEmpty && !syncAnonKey.hasPrefix("%%")
    }

    private func nextSyncRef() -> String {
        syncRef += 1
        return String(syncRef)
    }

    private func startSyncRealtime() {
        guard syncConfigured(), syncSocket == nil else { return }
        let base = syncSupabaseUrl.hasSuffix("/") ? String(syncSupabaseUrl.dropLast()) : syncSupabaseUrl
        let wsBase = base.replacingOccurrences(of: "https://", with: "wss://")
                         .replacingOccurrences(of: "http://", with: "ws://")
        guard let encodedKey = syncAnonKey.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "\(wsBase)/realtime/v1/websocket?apikey=\(encodedKey)&vsn=1.0.0") else { return }
        var req = URLRequest(url: url)
        req.setValue(syncAnonKey, forHTTPHeaderField: "apikey")
        req.setValue("Bearer " + syncAnonKey, forHTTPHeaderField: "Authorization")
        let socket = syncUrlSession.webSocketTask(with: req)
        syncSocket = socket
        socket.resume()

        let join = """
        {"topic":"realtime:public:app_sync_signals","event":"phx_join","payload":{"config":{"broadcast":{"self":false},"presence":{"key":""},"postgres_changes":[{"event":"INSERT","schema":"public","table":"app_sync_signals","filter":"project_id=eq.\(syncProjectId)"}]},"access_token":"\(syncAnonKey)"},"ref":"\(nextSyncRef())"}
        """
        socket.send(.string(join)) { _ in }
        receiveSyncMessage()
        startSyncHeartbeat()
        checkSyncSignal()
    }

    private func receiveSyncMessage() {
        syncSocket?.receive { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success(let message):
                if case .string(let text) = message,
                   text.contains("\"event\":\"postgres_changes\""),
                   text.contains(self.syncProjectId) {
                    DispatchQueue.main.async { self.reloadFromSyncSignal() }
                }
                self.receiveSyncMessage()
            case .failure:
                self.stopSyncRealtime()
                DispatchQueue.main.asyncAfter(deadline: .now() + 5) { self.startSyncRealtime() }
            }
        }
    }

    private func startSyncHeartbeat() {
        syncHeartbeatTimer?.invalidate()
        syncHeartbeatTimer = Timer.scheduledTimer(withTimeInterval: 25, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            let heartbeat = "{\"topic\":\"phoenix\",\"event\":\"heartbeat\",\"payload\":{},\"ref\":\"\(self.nextSyncRef())\"}"
            self.syncSocket?.send(.string(heartbeat)) { _ in }
        }
        if let syncHeartbeatTimer { RunLoop.main.add(syncHeartbeatTimer, forMode: .common) }
    }

    private func stopSyncRealtime() {
        syncHeartbeatTimer?.invalidate()
        syncHeartbeatTimer = nil
        syncSocket?.cancel(with: .normalClosure, reason: nil)
        syncSocket = nil
    }

    /// Poll the backend for a reload signal published by the website. When a
    /// newer signal exists than what we last saw, reload the WebView.
    private func checkSyncSignal() {
        guard syncConfigured() else { return }
        let base = syncSupabaseUrl.hasSuffix("/") ? String(syncSupabaseUrl.dropLast()) : syncSupabaseUrl
        let urlStr = base + "/rest/v1/app_sync_signals?project_id=eq." + syncProjectId +
            "&select=created_at&order=created_at.desc&limit=1"
        guard let url = URL(string: urlStr) else { return }
        var req = URLRequest(url: url)
        req.timeoutInterval = 5
        req.setValue(syncAnonKey, forHTTPHeaderField: "apikey")
        req.setValue("Bearer " + syncAnonKey, forHTTPHeaderField: "Authorization")
        URLSession.shared.dataTask(with: req) { [weak self] data, _, _ in
            guard let self = self, let data = data,
                  let body = String(data: data, encoding: .utf8) else { return }
            let pattern = "\"created_at\"\\s*:\\s*\"([^\"]+)\""
            guard let regex = try? NSRegularExpression(pattern: pattern),
                  let match = regex.firstMatch(in: body, range: NSRange(body.startIndex..., in: body)),
                  let range = Range(match.range(at: 1), in: body) else { return }
            let ts = String(body[range])
            if !ts.isEmpty && ts != self.lastSignalAt {
                let wasInitialized = self.syncSignalInitialized
                self.lastSignalAt = ts
                self.syncSignalInitialized = true
                if wasInitialized {
                    DispatchQueue.main.async { self.reloadFromSyncSignal() }
                }
            } else if ts.isEmpty {
                self.syncSignalInitialized = true
            }
        }.resume()
    }

    private func loadInitialURL() {
        guard let url = URL(string: websiteURL) else { return }
        var req = URLRequest(url: url)
        // Real-time mode: bypass HTTP cache so users always see the latest website content
        req.cachePolicy = .reloadIgnoringLocalCacheData
        webView.load(req)
    }

    private func loadFreshWebsite() {
        guard var components = URLComponents(string: websiteURL) else {
            webView?.reload()
            return
        }
        var items = components.queryItems ?? []
        items.removeAll { $0.name == "app_sync" }
        items.append(URLQueryItem(name: "app_sync", value: String(Int(Date().timeIntervalSince1970 * 1000))))
        components.queryItems = items
        guard let url = components.url else {
            webView?.reload()
            return
        }
        var req = URLRequest(url: url)
        req.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        req.setValue("no-cache, no-store, must-revalidate", forHTTPHeaderField: "Cache-Control")
        req.setValue("no-cache", forHTTPHeaderField: "Pragma")
        req.setValue("0", forHTTPHeaderField: "Expires")
        webView?.stopLoading()
        WKWebsiteDataStore.default().removeData(ofTypes: [WKWebsiteDataTypeDiskCache, WKWebsiteDataTypeMemoryCache], modifiedSince: .distantPast) { [weak self] in
            DispatchQueue.main.async { self?.webView?.load(req) }
        }
    }

    private func reloadFromSyncSignal() {
        guard Date().timeIntervalSince(lastSyncReloadAt) >= 1.5 else { return }
        lastSyncReloadAt = Date()
        loadFreshWebsite()
    }

    @objc private func handleRefresh() {
        loadFreshWebsite()
    }

    private func setupProgressView() {
        progressView = UIProgressView(progressViewStyle: .bar)
        progressView.translatesAutoresizingMaskIntoConstraints = false
        progressView.tintColor = UIColor(hex: headerColorHex) ?? .systemBlue
        view.addSubview(progressView)
        NSLayoutConstraint.activate([
            progressView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            progressView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            progressView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            progressView.heightAnchor.constraint(equalToConstant: 2)
        ])
    }


    override func observeValue(forKeyPath keyPath: String?, of object: Any?,
                               change: [NSKeyValueChangeKey: Any]?, context: UnsafeMutableRawPointer?) {
        if keyPath == #keyPath(WKWebView.estimatedProgress) {
            progressView.progress = Float(webView.estimatedProgress)
            progressView.isHidden = webView.estimatedProgress >= 1.0
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        refreshControl?.endRefreshing()
        syncSharedCookies()
    }

    private func syncSharedCookies() {
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
            for cookie in cookies {
                HTTPCookieStorage.shared.setCookie(cookie)
            }
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = navigationAction.request.url,
           let scheme = url.scheme?.lowercased(),
           ["tel", "mailto", "sms", "whatsapp"].contains(scheme) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    deinit {
        stopSyncRealtime()
        webView?.removeObserver(self, forKeyPath: #keyPath(WKWebView.estimatedProgress))
    }
}

extension UIColor {
    convenience init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt64(s, radix: 16) else { return nil }
        self.init(red: CGFloat((v & 0xFF0000) >> 16) / 255.0,
                  green: CGFloat((v & 0x00FF00) >> 8) / 255.0,
                  blue: CGFloat(v & 0x0000FF) / 255.0,
                  alpha: 1.0)
    }
}
