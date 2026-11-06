import os
import QuartzCore
import SwiftUI

/// Animation QA, in any build (profiling needs Release): off unless a launch
/// argument asks for it.
///
/// `-qaHitches YES` logs every frame that came late (category "hitch"), with
/// what was moving, and marks it as a signpost for Instruments.
/// `-qaCycle np|search|sheet` opens and closes that surface every few
/// seconds, so a profile or a recording catches several runs.
@MainActor
enum QAProbe {
    static let log = Logger(subsystem: "com.gpo.yoin", category: "hitch")
    static let signposter = OSSignposter(subsystem: "com.gpo.yoin", category: .pointsOfInterest)

    /// `-qaDiag YES`: device diagnostics on stdout (devicectl --console): the account, the source,
    /// Apple Music access and what the library loads did; then a window snapshot in Documents
    /// (`qa-shot.png`, copied off with `devicectl device copy from`).
    static var diag: Bool { UserDefaults.standard.bool(forKey: "qaDiag") }

    static func start(_ model: AppModel) {
        let d = UserDefaults.standard
        if diag { Task { await runDiag(model) } }
        if d.bool(forKey: "qaHitches") { HitchMonitor.shared.start(model) }
        if let cycle = d.string(forKey: "qaCycle") {
            Task { await run(cycle, model) }
        }
    }

    private static func runDiag(_ model: AppModel) async {
        func report(_ when: String) {
            let p = model.profiles.active
            let store = model.store
            print("[diag] \(when) profile=\(p.map { "\($0.service) \($0.id)" } ?? "none") source=\(model.sources.active)"
                  + " appleAuthorized=\(model.appleMusic.isAuthorized) isReal=\(store.isReal)"
                  + " albums=\(store.albums.count) artists=\(store.artists.count) playlists=\(store.playlists.count)"
                  + " phases=\(store.phases.map { "\($0.key):\($0.value)" }.sorted())")
        }
        report("launch")
        typeSpecimen()
        await screenSpecimen()
        // `-qaDiagScreens YES`: Home, Library, Settings and Now Playing as they are on this device.
        if UserDefaults.standard.bool(forKey: "qaDiagScreens") {
            try? await Task.sleep(for: .seconds(4))
            snapshot("qa-home.png")
            model.select(.library)
            try? await Task.sleep(for: .seconds(3))
            snapshot("qa-library.png")
            model.select(.home)
            model.openSettings()
            try? await Task.sleep(for: .seconds(3))
            snapshot("qa-settings.png")
            model.path.removeAll()
            try? await Task.sleep(for: .seconds(1))
            if !model.isIdle {
                model.setNowPlaying(expanded: true)
                try? await Task.sleep(for: .seconds(3))
                snapshot("qa-np.png")
                model.setNowPlaying(expanded: false)
            }
            return report("screens")
        }
        // `-qaDiagRestore <profile id>`: just make that account the one in use (undo a cut-off diag run).
        if let id = UserDefaults.standard.string(forKey: "qaDiagRestore") {
            await model.profiles.switchTo(id)
            return report("restored")
        }
        print("[diag] profiles \(model.profiles.profiles.map { "\($0.service) \($0.id)" })")
        // `-qaDiagApple YES`: try the Apple Music account, then go back to the one in use.
        let original = model.profiles.activeId
        let apple = UserDefaults.standard.bool(forKey: "qaDiagApple")
            ? model.profiles.profiles.first { $0.service == .appleMusic } : nil
        if let apple {
            await model.profiles.switchTo(apple.id)
            report("switched")
            model.select(.library)
            await model.store.loadLibrary(nil)
            report("library loaded")
        }
        try? await Task.sleep(for: .seconds(4))
        report("4s")
        snapshot("qa-shot.png")
        try? await Task.sleep(for: .seconds(4))
        report("8s")
        snapshot("qa-shot2.png")
        if apple != nil, let original, original != model.profiles.activeId {
            await model.profiles.switchTo(original)
            report("switched back")
        }
    }

    /// The same words in each font path, rendered by SwiftUI into Documents/qa-type.png: which of
    /// them reaches Google Sans Flex on this OS.
    static func typeSpecimen() {
        let rows: [(String, Font)] = [
            ("system", .system(size: 30)),
            ("gsf static name", .gsf(30)),
            ("gsf semibold name", .gsf(30, .semibold)),
            ("variable Font(UIFont)", YoinFont.font(size: 30, YoinFont.Axes(weight: 400))),
            ("variable rounded 600", YoinFont.rounded(30, semibold: true)),
        ]
        let designed: [(String, Font)] = [
            ("gsf semibold + fontDesign(.default)", .gsf(30, .semibold)),
            ("variable rounded + fontDesign(.default)", YoinFont.rounded(30, semibold: true)),
        ]
        let view = VStack(alignment: .leading, spacing: 10) {
            ForEach(rows.indices, id: \.self) { i in
                VStack(alignment: .leading, spacing: 2) {
                    Text(rows[i].0).font(.system(size: 13)).foregroundStyle(.gray)
                    Text("Rag Gyt 9.6 Home").font(rows[i].1).foregroundStyle(.black)
                }
            }
        }
        .padding(20)
        .frame(width: 420, alignment: .leading)
        .background(.white)
        let withDesign = VStack(alignment: .leading, spacing: 10) {
            view
            ForEach(designed.indices, id: \.self) { i in
                VStack(alignment: .leading, spacing: 2) {
                    Text(designed[i].0).font(.system(size: 13)).foregroundStyle(.gray)
                    Text("Rag Gyt 9.6 Activities").font(designed[i].1).foregroundStyle(.black)
                }
                .fontDesign(.default)
            }
        }
        .padding(.bottom, 20)
        .background(.white)
        let renderer = ImageRenderer(content: withDesign)
        renderer.scale = 3
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("qa-type.png")
        if let data = renderer.uiImage?.pngData() {
            try? data.write(to: url)
            print("[diag] type specimen \(url.path)")
        }
    }

    /// The same rows as `typeSpecimen`, but on screen (an overlay window) and captured as the screen
    /// shows them: Documents/qa-type-screen.png.
    static func screenSpecimen() async {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        let w = UIWindow(windowScene: scene)
        w.windowLevel = .alert + 1
        let vc = UIViewController()
        vc.view.backgroundColor = .white
        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        vc.view.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: vc.view.leadingAnchor, constant: 20),
                                     stack.topAnchor.constraint(equalTo: vc.view.topAnchor, constant: 80)])
        func label(_ t: String, _ f: UIFont) {
            let caption = UILabel(); caption.text = t; caption.font = .systemFont(ofSize: 12); caption.textColor = .gray
            let l = UILabel(); l.text = "Rag Gyt 9.6 Home"; l.font = f; l.textColor = .black
            stack.addArrangedSubview(caption); stack.addArrangedSubview(l)
        }
        label("UIKit system", .systemFont(ofSize: 30))
        label("UIKit UIFont(name: GoogleSansFlex-Regular)", UIFont(name: "GoogleSansFlex-Regular", size: 30) ?? .systemFont(ofSize: 5))
        label("UIKit variable instance", YoinFont.uiFont(size: 30, YoinFont.Axes(weight: 400)))
        let host = UIHostingController(rootView: VStack(alignment: .leading, spacing: 8) {
            Text("SwiftUI .gsf (custom relativeTo)").font(.system(size: 12)).foregroundStyle(.gray)
            Text("Rag Gyt 9.6 Home").font(.gsf(30)).foregroundStyle(.black)
            Text("SwiftUI .custom fixedSize").font(.system(size: 12)).foregroundStyle(.gray)
            Text("Rag Gyt 9.6 Home").font(.custom("GoogleSansFlex-Regular", fixedSize: 30)).foregroundStyle(.black)
            Text("SwiftUI Font(UIFont) variable").font(.system(size: 12)).foregroundStyle(.gray)
            Text("Rag Gyt 9.6 Home").font(YoinFont.font(size: 30, YoinFont.Axes(weight: 400))).foregroundStyle(.black)
        }.background(Color.white))
        host.view.backgroundColor = .white
        stack.addArrangedSubview(host.view)
        w.rootViewController = vc
        w.isHidden = false
        try? await Task.sleep(for: .seconds(1.5))
        let image = UIGraphicsImageRenderer(bounds: w.bounds).image { _ in w.drawHierarchy(in: w.bounds, afterScreenUpdates: true) }
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("qa-type-screen.png")
        try? image.pngData()?.write(to: url)
        print("[diag] screen specimen \(url.path)")
        w.isHidden = true
    }

    static func snapshot(_ name: String) {
        guard let window = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first
        else { return print("[diag] no window") }
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(name)
        do { try image.pngData()?.write(to: url); print("[diag] snapshot \(url.path)") } catch { print("[diag] snapshot failed \(error)") }
    }

    private static func run(_ cycle: String, _ model: AppModel) async {
        try? await Task.sleep(for: .seconds(2))
        for i in 0..<4 {
            let open = signposter.beginInterval("open", id: signposter.makeSignpostID(), "\(cycle) \(i)")
            set(cycle, true, model)
            try? await Task.sleep(for: .seconds(1.5))
            signposter.endInterval("open", open)
            let close = signposter.beginInterval("close", id: signposter.makeSignpostID(), "\(cycle) \(i)")
            set(cycle, false, model)
            try? await Task.sleep(for: .seconds(1.5))
            signposter.endInterval("close", close)
        }
    }

    private static func set(_ cycle: String, _ open: Bool, _ model: AppModel) {
        HitchMonitor.shared.phase = "\(cycle) \(open ? "open" : "close")"
        HitchMonitor.shared.phaseStart = CACurrentMediaTime()
        print("[hitch] phase \(HitchMonitor.shared.phase)")
        switch cycle {
        case "np": model.setNowPlaying(expanded: open)
        case "nptap":
            // As a finger does it: down on the pill (prewarm), a tap's length, then open.
            if open {
                model.np.prewarm()
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(110))
                    model.setNowPlaying(expanded: true)
                }
            } else {
                model.setNowPlaying(expanded: false)
            }
        case "search":
            if open { model.openSearch(from: .bar) } else {
                AppModel.dismissKeyboard()
                withAnimation(Yoin.spatial) { model.searchActive = false }
            }
        case "searchtap":
            if open {
                model.prewarmSearch()
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(110))
                    model.openSearch(from: .bar)
                }
            } else {
                AppModel.dismissKeyboard()
                withAnimation(Yoin.spatial) { model.searchActive = false }
            }
        case "sheet":
            if open {
                let p = model.playback
                PlaylistActions.shared.openMenu(PlaylistEntry(album: p.album, track: p.track), context: .nowPlaying)
            } else {
                PlaylistActions.shared.menu = nil
            }
        default: break
        }
    }
}

/// Late frames, measured on the display link: a frame is late when the gap
/// since the last one is over 1.5 refresh intervals.
@MainActor
final class HitchMonitor: NSObject {
    static let shared = HitchMonitor()
    var phase = "idle"
    /// When the current phase began (media time): each late frame logs how far into it it fell.
    var phaseStart: CFTimeInterval = 0
    private var link: CADisplayLink?
    private var last: CFTimeInterval = 0
    private weak var model: AppModel?

    func start(_ model: AppModel) {
        self.model = model
        let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        l.add(to: .main, forMode: .common)
        link = l
    }

    @objc private func tick(_ l: CADisplayLink) {
        defer { last = l.timestamp }
        guard last > 0 else { return }
        let gap = l.timestamp - last
        let expected = l.targetTimestamp - l.timestamp
        guard expected > 0, gap > expected * 1.5 else { return }
        let np = model?.np.progress ?? 0
        let rise = SheetRise.shared.progress
        let ms = Int((gap * 1000).rounded())
        QAProbe.log.log("late \(ms, privacy: .public)ms phase=\(self.phase, privacy: .public) np=\(np, format: .fixed(precision: 2), privacy: .public) rise=\(rise, format: .fixed(precision: 2), privacy: .public)")
        QAProbe.signposter.emitEvent("late", "\(ms)ms \(self.phase)")
        // Also on stdout: `devicectl device process launch --console` shows it.
        let at = phaseStart > 0 ? Int(((l.timestamp - phaseStart) * 1000).rounded()) : -1
        print(String(format: "[hitch] late %dms at=%dms phase=%@ np=%.2f rise=%.2f", ms, at, phase, np, rise))
    }
}
