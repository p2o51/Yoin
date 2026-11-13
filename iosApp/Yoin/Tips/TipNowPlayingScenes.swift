import SwiftUI

/*
 * Now Playing on the demo phone (Android ui/tips/TipNowPlayingScenes.kt): the app's own player column
 * (`NowPlayingView(demo:)`: top bar, cover, rating bar and heart, tabs, lyrics, transport with the wave
 * progress bar, the title and the pills) at the heights its budget gives a phone of this size, in the made-up
 * song's cover colours. Its sheet is the song sheet's own body (TipSongSheet.swift).
 */

/// The player over [demo]: its playhead moves while [playing], a quarter second at a time like the engine's.
struct DemoNowPlaying: View {
    var demo: NowPlayingDemo
    var playing: Bool
    @Namespace private var namespace

    /// The playhead's tick (Android PlayheadTickMs).
    static let tickMs = 250

    var body: some View {
        NowPlayingView(npNamespace: namespace, panel: true, demo: demo)
            // Never the shell's pill ⇄ page morph: this page flies nowhere.
            .environment(\.npMorph, nil)
            .safeAreaPadding(.top, DemoPhoneMetrics.statusBar)
            .onChange(of: playing, initial: true) { _, on in demo.isPlaying = on }
            .task(id: playing) {
                guard playing else { return }
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(Self.tickMs))
                    demo.advance(by: Double(Self.tickMs) / 1000)
                }
            }
    }
}

// MARK: - Shuffle and repeat

/// The play-mode button in its place in the transport: tap through the modes, hold to split it, then each
/// half on its own.
struct PlayModesDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var demo = TipDemo.nowPlaying()

    /// Where the split button's halves are across it (shuffle | repeat).
    static let shuffleFx: CGFloat = 0.27
    static let repeatFx: CGFloat = 0.73

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            demo.split = false
            demo.shuffle = false
            demo.repeatMode = .all
            try await DemoTiming.delay(300)
            try await finger.moveTo("mode")
            try await finger.tap { demo.cyclePlayMode() }
            try await DemoTiming.delay(600)
            try await finger.tap { demo.cyclePlayMode() }
            try await DemoTiming.delay(600)
            try await finger.hold { demo.split = true }
            try await DemoTiming.delay(250)
            try await finger.moveTo("mode", fx: Self.shuffleFx)
            try await finger.tap {
                demo.splitTouches += 1
                demo.shuffle.toggle()
            }
            try await DemoTiming.delay(500)
            try await finger.moveTo("mode", fx: Self.repeatFx)
            try await finger.tap {
                demo.splitTouches += 1
                demo.repeatMode = demo.repeatMode.next
            }
            try await finger.lift()
            // Left alone, the halves merge back.
            try await DemoTiming.delay(Int(PlayModeButton.holdSeconds * 1000) + 600)
        }) {
            DemoNowPlaying(demo: demo, playing: playing)
        }
    }
}

// MARK: - Tap cover to Focus

/// Tap the cover: Focus, as the page's budget lays it out (the top bar, the rating column, the tabs and the
/// pills step away, the cover grows and its colours bloom behind it); tap it again and the player is back.
/// On the 20:9 phone: a 16:9 screen keeps the cover's size in focus.
struct FocusCoverDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var demo = TipDemo.nowPlaying()

    var body: some View {
        DemoPhone(playing: playing, tall: true, scheme: scheme, script: { finger in
            demo.focused = false
            try await DemoTiming.delay(600)
            try await finger.moveTo("cover", fy: 0.45)
            try await finger.tap { demo.focused = true }
            try await finger.lift()
            try await DemoTiming.delay(2_400)
            try await finger.moveTo("cover", fy: 0.45)
            try await finger.tap { demo.focused = false }
            try await finger.lift()
            try await DemoTiming.delay(1_400)
        }) {
            DemoNowPlaying(demo: demo, playing: playing)
        }
    }
}

// MARK: - Song options, Keep screen on

/// Long-press the cover: the playing song's sheet; with [keepScreenOn], switch Keep screen on in it.
struct CoverMenuDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    var keepScreenOn: Bool
    @State private var demo = TipDemo.nowPlaying()
    @State private var open = false
    @State private var awake = false

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            open = false
            awake = false
            try await DemoTiming.delay(300)
            try await finger.moveTo("cover", fy: 0.45)
            try await finger.hold { open = true }
            if keepScreenOn {
                // The sheet is up and laid out before the finger looks for its last row.
                try await DemoTiming.delay(500)
                try await finger.moveTo("keep", fx: 0.86)
                try await finger.tap { awake.toggle() }
                try await DemoTiming.delay(1_200)
            } else {
                try await finger.lift()
                try await DemoTiming.delay(1_800)
            }
            // On the cover above the sheet.
            try await finger.moveTo("cover", fy: 0.3)
            try await finger.tap { open = false }
            try await DemoTiming.delay(500)
        }) {
            DemoSheetStage(open: open, onDismiss: { open = false }) {
                DemoNowPlaying(demo: demo, playing: playing)
            } sheet: {
                DemoSongSheet(track: PlaylistEntry(album: demo.album, track: demo.track), scheme: scheme, nowPlaying: true,
                              onDismiss: { open = false }) {
                    TrackMenuToggleRow(label: String(localized: "Keep screen on"), icon: "light_mode", scheme: scheme,
                                       isOn: Binding(get: { awake }, set: { on in withAnimation(Yoin.spatialFast) { awake = on } }))
                        .demoTarget("keep")
                }
            }
            .onAppear { demo.onCoverHold = { open = true } }
        }
    }
}

// MARK: - Notes pin the second

/// Write keeps the second it was tapped at as the note's anchor; scrubbing the wave progress bar back across
/// it, the bar catches there and its bubble reads Note (WavyProgressBar's note anchors). The scrub is acted
/// out on the bar (`NowPlayingDemo.scrub`), a frame at a time, as a finger's would be.
struct NoteMomentsDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var demo = TipDemo.nowPlaying()

    /// How far past the anchor the scrub starts, and where it ends before it (fractions of the bar; Android).
    static let scrubFrom: Double = 0.14
    static let scrubPast: Double = 0.05
    /// Where on the bar's 40-tall frame the line runs (it sits at the frame's foot).
    static let barFy: CGFloat = 0.85

    /// [from] to [to] along the drag's curve (FastOutSlowIn) over [ms], after the finger's touch-down.
    @MainActor
    static func scrub(_ demo: NowPlayingDemo, from: Double, to: Double, ms: Int) async throws {
        try await DemoTiming.delay(DemoTiming.dragDownMs)
        let curve = UnitCurve.bezier(startControlPoint: UnitPoint(x: 0.4, y: 0), endControlPoint: UnitPoint(x: 0.2, y: 1))
        let frames = max(1, ms / 16)
        for i in 1...frames {
            try await DemoTiming.delay(16)
            demo.scrub = from + (to - from) * curve.value(at: Double(i) / Double(frames))
        }
    }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            do {
                try await act(finger)
            } catch {
                // Stopped mid-scrub (the user took the phone): the bar lets go.
                withAnimation(.spring(duration: 0.3, bounce: 0)) { demo.scrub = nil }
                throw error
            }
        }) {
            DemoNowPlaying(demo: demo, playing: playing)
        }
    }

    private func act(_ finger: DemoFinger) async throws {
        demo.noteAnchorsMs = []
        demo.scrub = nil
        try await DemoTiming.delay(900)
        try await finger.moveTo("write")
        try await finger.tap { withAnimation(Yoin.spatial) { demo.write() } }
        try await DemoTiming.delay(1_600)
        guard let anchor = demo.noteAnchors.last else { return }
        let from = min(anchor + Self.scrubFrom, 1), to = max(anchor - Self.scrubPast, 0)
        let start = finger.at("progressBar", fx: from, fy: Self.barFy)
        try await finger.moveTo(x: start.x, y: start.y)
        withAnimation(.spring(duration: 0.35, bounce: 0.3)) { demo.scrub = from }
        let onNote = finger.at("progressBar", fx: anchor, fy: Self.barFy)
        async let bar: Void = Self.scrub(demo, from: from, to: anchor, ms: 1_100)
        try await finger.drag(toX: onNote.x, toY: onNote.y, ms: 1_100)
        try await bar
        // On the anchor the bar holds and its bubble reads Note.
        try await DemoTiming.delay(1_100)
        let past = finger.at("progressBar", fx: to, fy: Self.barFy)
        async let leave: Void = Self.scrub(demo, from: anchor, to: to, ms: 500)
        try await finger.drag(toX: past.x, toY: past.y, ms: 500)
        try await leave
        try await DemoTiming.delay(200)
        withAnimation(.spring(duration: 0.3, bounce: 0)) { demo.scrub = nil }
        try await finger.lift()
        try await DemoTiming.delay(1_600)
    }
}
