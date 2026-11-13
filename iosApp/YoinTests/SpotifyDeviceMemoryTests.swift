import XCTest
@testable import Yoin

/// Speakers without an id, the playing one, and remembered devices in the Spotify devices sheet
/// (SpotifySource.withPlaying, NowPlaying/SpotifyDeviceMemory; Android listSpotifyDevices,
/// SpotifyDeviceMemory, parity I22).
final class SpotifyDeviceMemoryTests: XCTestCase {
    private typealias Device = SpotifySource.Device
    private let day: Int64 = 24 * 60 * 60 * 1000

    private func device(_ id: String, _ name: String, active: Bool = false, hasId: Bool = true,
                        restricted: Bool = false) -> Device {
        Device(id: hasId ? id : Device.noIdKey(name), name: name, type: "Speaker", isActive: active,
               hasId: hasId, restricted: restricted)
    }

    private func now(_ name: String, id: String?) -> SpotifySource.PlayerNow {
        SpotifySource.PlayerNow(deviceId: id, deviceName: name, deviceType: "Speaker", isPlaying: true)
    }

    private func remembered(_ id: String, _ name: String, seen: Int64, hasId: Bool = true) -> SpotifyDeviceMemory.Remembered {
        .init(id: id, name: name, type: "Speaker", lastSeenMs: seen, hasId: hasId)
    }

    // MARK: The playing device

    func test_should_addThePlayingSpeakerFirst_when_theListLeavesItOut() {
        let desk = device("d", "Desk")
        let rows = SpotifySource.withPlaying([desk], now: now("Living Room", id: nil))
        XCTAssertEqual(rows.map(\.name), ["Living Room", "Desk"])
        XCTAssertEqual(rows[0].id, "spotify-no-id:Living Room")
        XCTAssertTrue(rows[0].isActive)
        XCTAssertTrue(rows[0].spotifyOnly)
        XCTAssertFalse(rows[1].spotifyOnly)
    }

    func test_should_notAddItTwice_when_theListHasItByIdOrByName() {
        XCTAssertEqual(SpotifySource.withPlaying([device("d", "Desk", active: true)], now: now("Desk", id: "d")).count, 1)
        let sonos = device("", "Living Room", hasId: false)
        XCTAssertEqual(SpotifySource.withPlaying([sonos], now: now(" living room ", id: nil)).count, 1)
    }

    func test_should_putTheActiveOneFirst_andKeepSpotifysOrder_when_listing() {
        let rows = SpotifySource.withPlaying([device("a", "A"), device("b", "B"), device("c", "C", active: true)], now: nil)
        XCTAssertEqual(rows.map(\.id), ["c", "a", "b"])
    }

    func test_should_beSpotifyOnly_when_restrictedOrWithoutAnId() {
        XCTAssertTrue(device("s", "Sonos", restricted: true).spotifyOnly)
        XCTAssertTrue(device("", "Sonos", hasId: false).spotifyOnly)
        XCTAssertFalse(device("d", "Desk").spotifyOnly)
    }

    func test_should_neverPickAnIdlessDevice_when_lookingForThisMac() {
        let mac = SpotifyThisDevice.mac(["QA Mac"])
        let idless = Device(id: Device.noIdKey("QA Mac"), name: "QA Mac", type: "Computer", isActive: true, hasId: false)
        XCTAssertNil(mac.pick([idless]))
    }

    // MARK: Memory

    func test_should_rememberListedDevices_newestFirst_andDropExpiredOnes() {
        let t: Int64 = 100 * day
        let old = [remembered("k", "Kitchen", seen: t - 3 * day), remembered("x", "Expired", seen: t - 31 * day),
                   remembered("d", "Desk (old name)", seen: t - 5 * day)]
        let merged = SpotifyDeviceMemory.merge(old, listed: [device("d", "Desk")], nowMs: t)
        XCTAssertEqual(merged.map(\.id), ["d", "k"])
        XCTAssertEqual(merged[0].name, "Desk")
        XCTAssertEqual(merged[0].lastSeenMs, t)
    }

    func test_should_rememberAnIdlessSpeaker_when_spotifyListsIt() {
        let merged = SpotifyDeviceMemory.merge([], listed: [device("", "Living Room", hasId: false)], nowMs: 1)
        XCTAssertEqual(merged, [.init(id: "spotify-no-id:Living Room", name: "Living Room", type: "Speaker",
                                      lastSeenMs: 1, hasId: false)])
    }

    func test_should_keepAtMostTwelve_when_merging() {
        let many = (0..<20).map { remembered("r\($0)", "R\($0)", seen: Int64($0)) }
        let merged = SpotifyDeviceMemory.merge(many, listed: [], nowMs: 30)
        XCTAssertEqual(merged.count, SpotifyDeviceMemory.max)
        XCTAssertEqual(merged.first?.id, "r19")
    }

    func test_should_notRefreshARememberedRow_when_mergingItBack() {
        let t: Int64 = 10 * day
        let row = Device(id: "k", name: "Kitchen", type: "Speaker", isActive: false, remembered: true)
        let merged = SpotifyDeviceMemory.merge([remembered("k", "Kitchen", seen: t - day)], listed: [row], nowMs: t)
        XCTAssertEqual(merged.first?.lastSeenMs, t - day)
    }

    func test_should_addRememberedRowsAfterTheListed_skippingSameIdOrName() {
        let listed = [device("d", "Desk", active: true)]
        let memory = [remembered("d", "Desk", seen: 5), remembered("d2", " desk ", seen: 4),
                      remembered("k", "Kitchen", seen: 3), remembered("spotify-no-id:Den", "Den", seen: 2, hasId: false)]
        let rows = SpotifyDeviceMemory.withRemembered(listed, memory)
        XCTAssertEqual(rows.map(\.id), ["d", "k", "spotify-no-id:Den"])
        XCTAssertFalse(rows[0].remembered)
        XCTAssertTrue(rows[1].remembered && !rows[1].isActive && !rows[1].spotifyOnly)
        XCTAssertTrue(rows[2].remembered && rows[2].spotifyOnly)
    }

    func test_should_keepDevicesPerAccount_acrossReads() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "SpotifyDeviceMemoryTests"))
        defaults.removePersistentDomain(forName: "SpotifyDeviceMemoryTests")
        defer { defaults.removePersistentDomain(forName: "SpotifyDeviceMemoryTests") }
        let memory = SpotifyDeviceMemory(defaults: defaults)
        _ = memory.rows([device("", "Living Room", active: true, hasId: false)], scope: "a", nowMs: 1_000)
        let later = memory.rows([device("d", "Desk")], scope: "a", nowMs: 2_000)
        XCTAssertEqual(later.map(\.name), ["Desk", "Living Room"])
        XCTAssertTrue(later[1].remembered)
        XCTAssertEqual(memory.rows([], scope: "b", nowMs: 2_000), [])
    }

    // MARK: Row lines

    func test_should_sayHowARowIsReached_when_listing() {
        XCTAssertEqual(SpotifyDevicesSheet.detail(device("d", "Desk"), moving: true), loc("Moving…"))
        XCTAssertEqual(SpotifyDevicesSheet.detail(device("", "Sonos", active: true, hasId: false), moving: false), loc("Playing here"))
        XCTAssertEqual(SpotifyDevicesSheet.detail(device("", "Sonos", hasId: false), moving: false), loc("Switch through Spotify"))
        var kitchen = device("k", "Kitchen")
        kitchen.remembered = true
        XCTAssertEqual(SpotifyDevicesSheet.detail(kitchen, moving: false), loc("Recently used · may need waking in Spotify"))
        XCTAssertEqual(SpotifyDevicesSheet.detail(device("d", "Desk"), moving: false), "Speaker")
    }
}
