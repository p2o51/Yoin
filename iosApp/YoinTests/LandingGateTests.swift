import XCTest
@testable import Yoin

@MainActor
final class LandingGateTests: XCTestCase {
    // MARK: First-launch gate

    func test_should_showFirstRun_when_freshInstallHasNoAccount() {
        let store = MemoryLandingStore()
        let session = LandingGate.atLaunch(store: store, hasAccount: false, launch: .normal)
        XCTAssertEqual(session?.mode, .firstRun)
        XCTAssertEqual(session?.persists, true)
        XCTAssertEqual(session?.fadesIn, false)
        // Showing it records nothing: only finishing does.
        XCTAssertFalse(store.isDone)
    }

    func test_should_skipAndRecord_when_existingInstallHasAccount() {
        let store = MemoryLandingStore()
        XCTAssertNil(LandingGate.atLaunch(store: store, hasAccount: true, launch: .normal))
        XCTAssertTrue(store.isDone)
        // Signing out later never brings it back.
        XCTAssertNil(LandingGate.atLaunch(store: store, hasAccount: false, launch: .normal))
    }

    func test_should_skip_when_landingAlreadyDone() {
        let store = MemoryLandingStore(done: true)
        XCTAssertNil(LandingGate.atLaunch(store: store, hasAccount: false, launch: .normal))
    }

    func test_should_neverShowOrRecord_when_demoLaunch() {
        let store = MemoryLandingStore()
        XCTAssertNil(LandingGate.atLaunch(store: store, hasAccount: false, launch: .demo))
        XCTAssertNil(LandingGate.atLaunch(store: store, hasAccount: true, launch: .demo))
        XCTAssertFalse(store.isDone)
    }

    func test_should_alwaysShowWithoutRecording_when_forced() {
        let store = MemoryLandingStore(done: true)
        let session = LandingGate.atLaunch(store: store, hasAccount: true, launch: .forced)
        XCTAssertEqual(session?.mode, .firstRun)
        XCTAssertEqual(session?.persists, false)
    }

    func test_should_fadeInAndPersist_when_rerunFromSettings() {
        let session = LandingGate.rerun()
        XCTAssertEqual(session.mode, .rerun)
        XCTAssertTrue(session.persists)
        XCTAssertTrue(session.fadesIn)
        XCTAssertNotEqual(session.id, LandingGate.rerun().id)
    }

    func test_should_persistDone_when_defaultsStoreMarked() throws {
        let suite = "LandingGateTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertFalse(DefaultsLandingStore(defaults: defaults).isDone)
        DefaultsLandingStore(defaults: defaults).markDone()
        XCTAssertTrue(DefaultsLandingStore(defaults: defaults).isDone)
        XCTAssertNil(LandingGate.atLaunch(store: DefaultsLandingStore(defaults: defaults), hasAccount: false, launch: .normal))
        DefaultsLandingStore(defaults: defaults).reset()
        XCTAssertFalse(DefaultsLandingStore(defaults: defaults).isDone)
    }

    // MARK: Steps

    func test_should_listOneConnectStepPerPickedService_when_picked() {
        XCTAssertEqual(landingSteps(picked: []), [.hello, .pick, .about, .ready])
        XCTAssertEqual(landingSteps(picked: [.appleMusic, .subsonic]), [.hello, .pick, .about, .subsonic, .appleMusic, .ready])
        XCTAssertEqual(landingSteps(picked: [.spotify]), [.hello, .pick, .about, .spotify, .ready])
        XCTAssertEqual(landingSteps(picked: [.appleMusic, .spotify, .subsonic]),
                       [.hello, .pick, .about, .subsonic, .spotify, .appleMusic, .ready])
    }

    func test_should_pickSpotify_when_toggled() {
        var flow = LandingFlow(mode: .firstRun)
        flow.toggle(.spotify)
        XCTAssertEqual(flow.picked, [.spotify])
        flow.goTo(.spotify)
        XCTAssertEqual(flow.primary(hasAccount: false).label, "Skip")
        flow.markConnected(.spotify)
        XCTAssertEqual(flow.primary(hasAccount: true), LandingPrimary(label: "Next", style: .filled))
    }

    func test_should_keepCurrentStep_when_selectionChanges() {
        var flow = LandingFlow(mode: .firstRun)
        flow.next()
        XCTAssertEqual(flow.step, .pick)
        flow.toggle(.subsonic)
        flow.toggle(.appleMusic)
        XCTAssertEqual(flow.step, .pick)
        XCTAssertEqual(flow.steps.count, 6)
        flow.next(); flow.next(); flow.next()
        XCTAssertEqual(flow.step, .appleMusic)
        // Back on Pick, unpicking a later service leaves Pick where it is.
        flow.goTo(.pick)
        flow.toggle(.appleMusic)
        XCTAssertEqual(flow.step, .pick)
        XCTAssertEqual(flow.steps, [.hello, .pick, .about, .subsonic, .ready])
    }

    func test_should_stopAtEnds_when_steppingPastThem() {
        var flow = LandingFlow(mode: .firstRun)
        flow.back()
        XCTAssertEqual(flow.step, .hello)
        XCTAssertFalse(flow.backEnabled)
        for _ in 0..<10 { flow.next() }
        XCTAssertEqual(flow.step, .ready)
        XCTAssertTrue(LandingFlow(mode: .rerun).backEnabled)
    }

    // MARK: The pill's button

    func test_should_refuseNext_when_nothingPickedAndNoAccount() {
        var flow = LandingFlow(mode: .firstRun)
        flow.next()
        XCTAssertEqual(flow.primary(hasAccount: false), LandingPrimary(label: "Next", style: .unavailable))
        XCTAssertTrue(flow.refusesPrimary(hasAccount: false))
        // A re-run with an account may skip adding one.
        XCTAssertEqual(flow.primary(hasAccount: true), LandingPrimary(label: "Skip", style: .tonal))
        XCTAssertFalse(flow.refusesPrimary(hasAccount: true))
        flow.toggle(.subsonic)
        XCTAssertEqual(flow.primary(hasAccount: false), LandingPrimary(label: "Next", style: .filled))
        XCTAssertFalse(flow.refusesPrimary(hasAccount: false))
    }

    func test_should_turnSkipIntoNext_when_serviceConnected() {
        var flow = LandingFlow(mode: .firstRun)
        flow.toggle(.subsonic)
        flow.goTo(.subsonic)
        XCTAssertEqual(flow.primary(hasAccount: false).label, "Skip")
        flow.markConnected(.subsonic)
        XCTAssertEqual(flow.primary(hasAccount: true), LandingPrimary(label: "Next", style: .filled))
        flow.goTo(.ready)
        XCTAssertEqual(flow.primary(hasAccount: true), LandingPrimary(label: "Open Yoin", style: .filled, arrow: true))
    }

    func test_should_sayNoAccountYet_when_readyWithoutAccounts() {
        XCTAssertEqual(landingBubble(.ready, noAccounts: true).title, "No account yet.")
        XCTAssertEqual(landingBubble(.ready, noAccounts: false).title, "All set.")
    }
}
