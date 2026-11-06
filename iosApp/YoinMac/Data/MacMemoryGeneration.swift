import Foundation
import SwiftUI

@MainActor
final class MacMemoryGeneration {
    private struct Key: Hashable {
        var account: String
        var provider: String
        var album: String
        var language: String
    }
    private var busy: Set<Key> = []

    /// A successful result or failed attempt survives reopening the view and restarting the app.
    func generate(_ input: MacMemoryVoice.Input, account: String, regenerate: Bool = false,
                  client: GeminiClient = GeminiClient(), store: AlbumMemoryStore = .shared,
                  settings: GeminiSettings = .shared, isCurrent: () -> Bool) async {
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
                }) { CloudSync.shared.localChanged() }
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
            }) { CloudSync.shared.localChanged() }
        } catch { /* Android's local narrative stands in. */ }
    }

    static func cached(_ input: MacMemoryVoice.Input, row: AlbumMemoryStore.MemoryCopy?) -> AlbumMemoryStore.GeneratedText {
        guard let row else { return .init() }
        if let text = row.generatedByLanguage?[input.targetLanguage] { return text }
        // An Android/iOS cloud row has no local language map. Validate narration against Android's v2 key.
        guard row.generatedByLanguage == nil else { return .init() }
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
}
