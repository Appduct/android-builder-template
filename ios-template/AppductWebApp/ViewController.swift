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

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(hex: headerColorHex) ?? .systemBackground
        if darkMode { overrideUserInterfaceStyle = .dark }
        setupWebView()
        setupProgressView()
        loadInitialURL()
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
    }

    @objc private func appWillEnterForeground() {
        // Always nudge SPA pages to refetch via visibilitychange/focus events.
        webView?.evaluateJavaScript(
            "(function(){try{document.dispatchEvent(new Event('visibilitychange'));window.dispatchEvent(new Event('focus'));}catch(e){}})();",
            completionHandler: nil
        )
        // And do a full reload if the app was backgrounded for >10s.
        guard let bgAt = lastBackgroundedAt,
              Date().timeIntervalSince(bgAt) >= resumeReloadThreshold else { return }
        webView?.reload()
    }

    private func loadInitialURL() {
        guard let url = URL(string: websiteURL) else { return }
        var req = URLRequest(url: url)
        // Real-time mode: bypass HTTP cache so users always see the latest website content
        req.cachePolicy = .reloadIgnoringLocalCacheData
        webView.load(req)
    }

    @objc private func handleRefresh() {
        webView.reload()
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
