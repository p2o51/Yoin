import SwiftUI

/*
 * Spotify speakers (Android ui/tips/TipDevicesScene.kt, owner 2026-11-08), as iOS has them: Now Playing's
 * Devices opens Yoin's own Devices sheet (SpotifyDevicesList, the sheet's own content) over the player. The
 * demo only acts out that sheet, never the Spotify app that More devices opens here (owner 2026-11-15): a
 * refresh reads the list again, then the speaker kept as recently used is picked, moves, and plays; the sheet
 * goes down as the app's does after a move, and the pill names the speaker. Made-up devices, nothing behind
 * them: a tap on the phone moves only the phone's own list.
 */
struct SpotifySpeakersDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @Environment(\.colorScheme) private var colorScheme
    @State private var demo = TipDemo.nowPlaying()
    @State private var open = false
    @State private var devices = TipDemo.devices
    @State private var loading = false
    @State private var moving: String?
    /// One more per move or refresh begun, so a later one (the user's) outlives an earlier one's end.
    @State private var turn = 0

    /// How long a read and a move take on the phone.
    static let readMs = 900
    static let moveMs = 900
    /// The pause between the move landing and the sheet going down (the app dismisses at once; the eye needs
    /// a beat to see "Playing here").
    static let landedMs = 700

    private func refresh() async {
        turn += 1
        let mine = turn
        loading = true
        try? await DemoTiming.delay(Self.readMs)
        if mine == turn { loading = false }
    }

    private func move(to d: SpotifySource.Device) async {
        guard !d.isActive, moving == nil else { return }
        turn += 1
        let mine = turn
        moving = d.id
        try? await DemoTiming.delay(Self.moveMs)
        guard mine == turn else { return }
        withAnimation(Yoin.effects) {
            devices = TipDemo.devices(playingOn: d.id)
            moving = nil
        }
        try? await DemoTiming.delay(Self.landedMs)
        guard mine == turn else { return }
        open = false
        demo.deviceName = d.name
    }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            turn += 1
            open = false
            devices = TipDemo.devices
            loading = false
            moving = nil
            demo.deviceName = nil
            try await DemoTiming.delay(400)
            try await finger.moveTo("devicesPill")
            try await finger.tap { open = true }
            try await DemoTiming.delay(900)
            try await finger.moveTo("devicesRefresh")
            try await finger.tap { Task { await refresh() } }
            try await DemoTiming.delay(Self.readMs + 300)
            let speaker = TipDemo.devices[TipDemo.recentSpeaker]
            try await finger.moveTo("device-\(speaker.id)", fx: 0.35)
            try await finger.tap { Task { await move(to: speaker) } }
            try await finger.lift()
            try await DemoTiming.delay(Self.moveMs + Self.landedMs + 2_000)
        }) {
            let sheetScheme = CoverScheme(album: demo.album, dark: colorScheme == .dark)
            DemoSheetStage(open: open, onDismiss: { open = false }) {
                DemoNowPlaying(demo: demo, playing: playing)
            } sheet: {
                SpotifyDevicesList(devices: devices, failure: nil, moving: moving, loading: loading, scheme: sheetScheme,
                                   onRefresh: { Task { await refresh() } },
                                   onRow: { d in Task { await move(to: d) } },
                                   // The Spotify app's own picker: nothing to open on the phone.
                                   onMore: {})
                    .animation(Yoin.effects, value: devices)
                    // The system sheet's grabber, in the 24 above the title.
                    .overlay(alignment: .top) {
                        Capsule()
                            .fill(sheetScheme.onSurfaceVariant.opacity(0.4))
                            .frame(width: DemoSheetMetrics.grabber.width, height: DemoSheetMetrics.grabber.height)
                            .padding(.top, DemoSheetMetrics.grabberTop)
                    }
                    .background(sheetScheme.surfaceContainerLow,
                                in: .rect(cornerRadius: DemoSheetMetrics.corner, style: .continuous))
                    .padding(.horizontal, DemoSheetMetrics.inset)
                    .padding(.bottom, DemoSheetMetrics.inset)
            }
            .onAppear { demo.onDevices = { open = true } }
        }
    }
}
