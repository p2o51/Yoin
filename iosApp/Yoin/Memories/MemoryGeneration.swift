import Foundation
import SwiftUI

/*
 * Memory titles and narration through Gemini (Android GeminiService memory prompts; ported from YoinMac
 * MacMemoryGeneration). Only the Memories surface starts it, and only with a key set. Each album is tried
 * once per language: the attempt is written before the request, so reopening Memories, a failure or a
 * cancellation never sends it again. The result goes into AlbumMemoryStore's synced columns in Android's
 * form ([NARRATION]…[/NARRATION] [QUESTION]…[/QUESTION], Android's prompt hashes), so the other devices read
 * it; without one the deck says Android's local MemoryVoice copy.
 */
@MainActor
final class MemoryGeneration {
    static let shared = MemoryGeneration()

    private struct Key: Hashable {
        var account: String
        var provider: String
        var album: String
        var language: String
    }
    private var busy: Set<Key> = []

    /// A successful result or failed attempt survives reopening the view and restarting the app.
    func generate(_ input: MemoryVoice.Input, account: String, regenerate: Bool = false,
                  client: GeminiClient = GeminiClient(), store: AlbumMemoryStore = .shared,
                  settings: GeminiSettings = .shared, onSaved: () -> Void = { CloudSync.shared.localChanged() },
                  isCurrent: () -> Bool) async {
        let key = Key(account: account, provider: input.card.album.id.provider, album: input.card.id, language: input.targetLanguage)
        guard isCurrent(), !account.isEmpty, settings.hasKey, !busy.contains(key) else { return }
        busy.insert(key)
        defer { busy.remove(key) }

        func row() -> AlbumMemoryStore.MemoryCopy {
            store.memory(provider: key.provider, entityId: key.album)
                ?? .init(provider: key.provider, entityType: "album", entityId: key.album,
                         copy: "", promptHash: "", generatedAt: 0)
        }
        func update(_ change: (inout AlbumMemoryStore.GeneratedText, inout AlbumMemoryStore.MemoryCopy) -> Void) -> Bool {
            guard isCurrent() else { return false }
            var record = row()
            var text = record.generatedByLanguage?[key.language] ?? Self.cached(input, row: record)
            change(&text, &record)
            record.generatedByLanguage = (record.generatedByLanguage ?? [:]).merging([key.language: text]) { _, new in new }
            return withAnimation(Yoin.effects) { store.putMemory(record) }
        }

        if regenerate {
            guard update({ text, _ in text = .init() }) else { return }
        }
        let cached = Self.cached(input, row: row())
        if let writing = input.titleWriting, !cached.titleAttempted {
            // Write the attempt before suspension; cancellation or failure must not spin another request.
            guard update({ text, _ in text.titleAttempted = true }) else { return }
            do {
                let title = try await client.memoryTitle(apiKey: settings.requestKey, albumName: input.card.album.title,
                                                         artist: input.card.album.artist,
                                                         year: input.card.album.year > 0 ? input.card.album.year : nil,
                                                         writingKind: writing.kind, writingText: writing.text)
                guard isCurrent(), !Task.isCancelled else { return }
                if update({ text, record in
                    text.title = title
                    text.titlePromptHash = input.titleHash
                    record.title = title
                    record.titlePromptHash = input.titleHash
                    record.generatedAt = input.now
                }) { onSaved() }
            } catch is CancellationError { return }
            catch { /* Android keeps the cached title, else the album name. */ }
        }
        guard isCurrent(), !Task.isCancelled, !input.facts.isEmpty,
              !Self.cached(input, row: row()).narrationAttempted else { return }
        guard update({ text, _ in text.narrationAttempted = true }) else { return }
        do {
            let result = try await client.memoryNarration(apiKey: settings.requestKey,
                                                          targetLanguage: key.language, facts: input.facts)
            guard isCurrent(), !Task.isCancelled else { return }
            if update({ text, record in
                text.copy = result.encoded
                text.promptHash = input.narrationHash
                record.copy = result.encoded
                record.promptHash = input.narrationHash
                record.generatedAt = input.now
            }) { onSaved() }
        } catch { /* Android's local narrative stands in. */ }
    }

    static func cached(_ input: MemoryVoice.Input, row: AlbumMemoryStore.MemoryCopy?) -> AlbumMemoryStore.GeneratedText {
        guard let row else { return .init() }
        if var text = row.generatedByLanguage?[input.targetLanguage] {
            // An attempt here that came back empty, then another device's copy synced in for these facts: say that.
            if text.copy == nil, row.promptHash == input.narrationHash,
               let n = GeminiClient.parseMemoryNarration(row.copy), GeminiClient.followsMemoryVoiceRules(n) {
                text.copy = n.encoded
                text.narrationAttempted = true
            }
            if text.title == nil, row.titlePromptHash == input.titleHash,
               let t = row.title?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty {
                text.title = t
                text.titleAttempted = true
            }
            return text
        }
        // A title is written in the listener's language, whatever Gemini answers in (its key has no language):
        // one this writing already has is kept when another language is chosen, and not asked for again.
        guard row.generatedByLanguage == nil else {
            guard row.titlePromptHash == input.titleHash,
                  let t = row.title?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return .init() }
            return .init(titleAttempted: true, title: t, titlePromptHash: row.titlePromptHash)
        }
        // An Android cloud row has no local language map. Validate narration against Android's v2 key.
        let title = row.title.flatMap { value -> String? in
            let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? nil : text
        }
        let narration = row.promptHash == input.narrationHash ? GeminiClient.parseMemoryNarration(row.copy) : nil
        let copy = narration.flatMap { GeminiClient.followsMemoryVoiceRules($0) ? $0.encoded : nil }
        return .init(titleAttempted: title != nil && row.titlePromptHash == input.titleHash,
                     narrationAttempted: copy != nil, title: title, copy: copy,
                     titlePromptHash: row.titlePromptHash, promptHash: row.promptHash)
    }

    static func paragraph(_ copy: String, targetLanguage: String) -> String? {
        guard let text = GeminiClient.parseMemoryNarration(copy), GeminiClient.followsMemoryVoiceRules(text) else { return nil }
        return text.narration + (targetLanguage.contains("Chinese") ? "" : " ") + text.question
    }

    /// The deck's cards as this language reads them (MacModel.memoryCards): the generated title under a
    /// title of the listener's own, else in place of the album name; the generated paragraph, else Android's
    /// local copy, above a blank page (none under a review).
    static func voiced(_ cards: [MemoryCard], _ inputs: MemoryDeck.Inputs, language: String, now: Int64,
                       store: AlbumMemoryStore = .shared) -> [MemoryCard] {
        cards.map { voiced($0, inputs, language: language, now: now, store: store) }
    }

    static func voiced(_ card: MemoryCard, _ inputs: MemoryDeck.Inputs, language: String, now: Int64,
                       store: AlbumMemoryStore = .shared) -> MemoryCard {
        let voice = MemoryVoice.Input(card: card, notes: inputs.notes[card.id] ?? [], now: now, targetLanguage: language)
        let generated = cached(voice, row: store.memory(provider: card.album.id.provider, entityId: card.id))
        var card = card
        card.yoinTitle = generated.title
        if card.titleKind != .user {
            card.title = generated.title ?? card.album.title
            card.titleKind = generated.title == nil ? .album : .ai
        }
        card.paragraph = card.review == nil
            ? generated.copy.flatMap { paragraph($0, targetLanguage: language) } ?? voice.fallback?.paragraph
            : nil
        return card
    }
}

extension AppModel {
    /// The Memories deck's cards, voiced (signed out: the mock deck as it is).
    func voicedMemoryCards(_ cards: [MemoryCard], _ inputs: MemoryDeck.Inputs) -> [MemoryCard] {
        guard store.isReal else { return cards }
        return MemoryGeneration.voiced(cards, inputs, language: GeminiSettings.shared.targetLanguage,
                                       now: HistoryStore.shared.nowMs)
    }

    /// Called only by the Memories surface (MacModel.generateMemoryText): every card of the open deck
    /// ([deck], read as it goes) not yet tried in this language, one after another; stops when the account, its data or the
    /// language moves on. No key: nothing.
    func generateMemoryText(in deck: () -> [String], onCard: () -> Void = {}) async {
        guard store.isReal, GeminiSettings.shared.hasKey, let account = profiles.activeId else { return }
        let gen = store.generation, provider = store.provider
        let language = GeminiSettings.shared.targetLanguage
        let live = { [self] in
            gen == store.generation && provider == store.provider && account == profiles.activeId
                && language == GeminiSettings.shared.targetLanguage
        }
        let inputs = store.memoryDeckInputs()
        for card in MemoryDeck.cards(inputs) where card.album.id.provider == provider && deck().contains(card.id) {
            guard live(), !Task.isCancelled else { return }
            let input = MemoryVoice.Input(card: card, notes: inputs.notes[card.id] ?? [],
                                          now: HistoryStore.shared.nowMs, targetLanguage: language)
            await MemoryGeneration.shared.generate(input, account: account, isCurrent: live)
            if live(), !Task.isCancelled { onCard() }
        }
    }
}
