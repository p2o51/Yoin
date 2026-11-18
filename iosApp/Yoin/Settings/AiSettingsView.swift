import SwiftUI

/// Helper for extracting the up-to-6 distinct recently played tracks offered in "Try a song".
enum AiTrySongs {
    static func recentDistinct(from rows: [PlayEvent], limit: Int = 6) -> [PlayEvent] {
        guard limit > 0 else { return [] }
        var seen = Set<String>()
        var result: [PlayEvent] = []
        result.reserveCapacity(min(rows.count, limit))
        for row in rows {
            guard !row.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
            guard seen.insert(row.songId).inserted else { continue }
            result.append(row)
            if result.count == limit { break }
        }
        return result
    }
}

/// Full AI v2 configuration page rendered inside Settings › Features › AI features
/// (Android `AiSettingsPage` + `AiSubPages`, Pixel Settings segment language).
struct AiSettingsView: View {
    var app: CoverScheme
    var row: Color

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.openURL) private var openURL

    @State private var settings = GeminiSettings.shared
    @State private var providerKeyDraft = GeminiSettings.shared.key(for: GeminiSettings.shared.prefs.provider)
    @State private var searchKeyDraft = GeminiSettings.shared.searchKey(for: GeminiSettings.shared.prefs.searchService)
    @State private var baseUrlDraft = GeminiSettings.shared.prefs.customBaseUrl
    @State private var modelDraft = GeminiSettings.shared.prefs.customModel
    @State private var discoveredModels: [String] = []
    @State private var fetchingModels = false
    @State private var modelFetchError: String?
    @State private var providerKeySaveTask: Task<Void, Never>?
    @State private var searchKeySaveTask: Task<Void, Never>?

    @FocusState private var focus: Field?

    private enum Field: Hashable {
        case providerKey, customUrl, customModel, searchKey
    }

    private var zh: Bool { Tips.showsChinese }
    private var dark: Bool { colorScheme == .dark }
    private func copy(_ en: String, zhHans: String) -> String { zh ? zhHans : en }

    private var prefs: AiProviderPrefs { settings.prefs }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            providerGrid

            AiTradeoffChart(
                provider: prefs.provider,
                tier: prefs.activeTier,
                searchService: prefs.searchService,
                app: app,
                row: row,
                dark: dark,
                zh: zh
            )

            if prefs.provider == .custom {
                customSection
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            } else {
                standardProviderSection
                    .transition(.opacity)
            }

            languageSection

            NavigationLink {
                AiTryPage(onOpen: flushPendingKeys)
            } label: {
                HStack(spacing: 8) {
                    YoinIcon("play_filled", size: 18)
                    Text(copy("Try a song", zhHans: "拿一首歌试试"))
                        .yoinRole(Tokens.type.titleMedium)
                }
                .foregroundStyle(app.onPrimary)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(app.primary, in: .capsule)
                .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
        }
        .animation(Yoin.spatial, value: prefs.provider)
        .animation(Yoin.effects, value: prefs.tier)
        .animation(Yoin.effects, value: prefs.searchService)
        .animation(Yoin.effects, value: settings.targetLanguage)
        .onDisappear { flushPendingKeys() }
    }

    // MARK: - Provider Grid

    private var providerGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            ForEach(AiProvider.allCases) { provider in
                let selected = prefs.provider == provider
                Button {
                    flushProviderKey(for: prefs.provider)
                    settings.setProvider(provider)
                    providerKeyDraft = settings.key(for: provider)
                } label: {
                    HStack(spacing: 6) {
                        Text(providerName(provider))
                            .yoinRole(Tokens.type.labelLarge)
                            .lineLimit(1)
                        if provider.isDirectInChina {
                            Spacer(minLength: 2)
                            Text(copy("Direct in China", zhHans: "中国直连"))
                                .font(.gsf(10, .medium))
                                .lineLimit(1)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(
                                    selected ? app.onPrimary.opacity(0.18) : app.secondaryContainer,
                                    in: .capsule
                                )
                                .foregroundStyle(selected ? app.onPrimary : app.onSecondaryContainer)
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 46, alignment: .leading)
                    .padding(.horizontal, 14)
                    .foregroundStyle(selected ? app.onPrimary : app.onSurface)
                    .background(
                        selected ? app.primary : row,
                        in: .rect(cornerRadius: selected ? 23 : 14, style: .circular)
                    )
                }
                .buttonStyle(.plain)
                .pointerHighlight()
                .animation(Yoin.spatialFast, value: selected)
            }
        }
    }

    // MARK: - Built-in Providers (Gemini / DeepSeek / Qwen)

    private var standardProviderSection: some View {
        VStack(alignment: .leading, spacing: 20) {
            if prefs.provider.tiers.count > 1 {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        sectionLabel(copy("Tier", zhHans: "档位"))
                        Spacer()
                        NavigationLink {
                            AiTiersPage(provider: prefs.provider, zh: zh)
                        } label: {
                            YoinIcon("info", size: 20)
                                .foregroundStyle(app.onSurfaceVariant)
                                .frame(width: 36, height: 36)
                                .contentShape(.circle)
                        }
                        .buttonStyle(.plain)
                        .pointerHighlight()
                        .accessibilityLabel(copy("How the tiers differ", zhHans: "档位区别"))
                    }

                    HStack(spacing: 8) {
                        ForEach(AiTier.allCases) { tier in
                            let selected = prefs.tier == tier
                            Button {
                                settings.setTier(tier)
                            } label: {
                                Text(tierName(tier))
                                    .yoinRole(Tokens.type.titleMedium)
                                    .frame(maxWidth: .infinity, minHeight: 54)
                                    .foregroundStyle(selected ? app.onPrimary : app.onSurface)
                                    .background(
                                        selected ? app.primary : row,
                                        in: .rect(cornerRadius: selected ? 27 : 20, style: .circular)
                                    )
                            }
                            .buttonStyle(.plain)
                            .pointerHighlight()
                            .animation(Yoin.spatial, value: selected)
                        }
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }

            VStack(alignment: .leading, spacing: 6) {
                keySegment(
                    label: copy("API key", zhHans: "API 密钥"),
                    placeholder: prefs.provider == .gemini ? "AIza…" : "sk-…",
                    text: $providerKeyDraft,
                    field: .providerKey,
                    savedValue: settings.key(for: prefs.provider),
                    onCommit: { value in settings.saveKey(value, for: prefs.provider) },
                    onClear: {
                        providerKeyDraft = ""
                        settings.saveKey("", for: prefs.provider)
                    }
                )
                .background(row, in: CloudSyncView.segment(0, 1))
                .onChange(of: providerKeyDraft) { _, newValue in
                    scheduleProviderKeySave(newValue, provider: prefs.provider)
                }

                if let link = keyLink(for: prefs.provider), let url = URL(string: link.url) {
                    Button { openURL(url) } label: {
                        HStack(spacing: 5) {
                            Text(link.title)
                                .yoinRole(Tokens.type.labelLarge)
                            YoinIcon("open_in_full", size: 13)
                        }
                        .foregroundStyle(app.primary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)
                    .pointerHighlight()
                }
            }
        }
    }

    // MARK: - Custom Endpoint & Web Search

    private var customSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 8) {
                sectionLabel(copy("Format", zhHans: "接口格式"))
                HStack(spacing: 8) {
                    ForEach(AiApiFormat.allCases) { format in
                        let selected = prefs.customFormat == format
                        Button {
                            settings.setCustomFormat(format)
                        } label: {
                            Text(format == .openAi ? copy("OpenAI compatible", zhHans: "OpenAI 兼容") : "Anthropic")
                                .yoinRole(Tokens.type.labelLarge)
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .foregroundStyle(selected ? app.onPrimary : app.onSurface)
                                .background(
                                    selected ? app.primary : row,
                                    in: .rect(cornerRadius: selected ? 22 : 14, style: .circular)
                                )
                        }
                        .buttonStyle(.plain)
                        .pointerHighlight()
                        .animation(Yoin.spatialFast, value: selected)
                    }
                }
            }

            VStack(spacing: 2) {
                textInputRow(
                    label: copy("Address", zhHans: "地址"),
                    placeholder: prefs.customFormat == .openAi
                        ? "https://api.openai.com/v1"
                        : "https://api.anthropic.com",
                    text: $baseUrlDraft,
                    field: .customUrl
                )
                .onChange(of: baseUrlDraft) { _, newValue in
                    settings.setCustomBaseUrl(newValue)
                }
                .background(row, in: CloudSyncView.segment(0, 3))

                keySegment(
                    label: copy("API key", zhHans: "API 密钥"),
                    placeholder: "sk-…",
                    text: $providerKeyDraft,
                    field: .providerKey,
                    savedValue: settings.key(for: .custom),
                    onCommit: { value in settings.saveKey(value, for: .custom) },
                    onClear: {
                        providerKeyDraft = ""
                        settings.saveKey("", for: .custom)
                    }
                )
                .onChange(of: providerKeyDraft) { _, newValue in
                    scheduleProviderKeySave(newValue, provider: .custom)
                }
                .background(row, in: CloudSyncView.segment(1, 3))

                modelInputRow
                    .background(row, in: CloudSyncView.segment(2, 3))
            }

            if let modelFetchError {
                Text(copy("Couldn't fetch models: \(modelFetchError)", zhHans: "拉取失败：\(modelFetchError)"))
                    .yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(app.error)
                    .padding(.horizontal, 8)
            }

            let chips = AiSettings.filteredModelChips(ids: discoveredModels, typed: modelDraft)
            if !chips.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(chips, id: \.self) { id in
                            let selected = modelDraft == id
                            Button {
                                modelDraft = id
                                settings.setCustomModel(id)
                            } label: {
                                Text(id)
                                    .yoinRole(Tokens.type.labelMedium)
                                    .lineLimit(1)
                                    .padding(.horizontal, 12)
                                    .frame(height: 34)
                                    .foregroundStyle(selected ? app.onPrimary : app.onSurface)
                                    .background(selected ? app.primary : row, in: .capsule)
                            }
                            .buttonStyle(.plain)
                            .pointerHighlight()
                        }
                    }
                }
                .transition(.opacity)
            }

            VStack(alignment: .leading, spacing: 8) {
                sectionLabel(copy("Thinking", zhHans: "思考"))
                HStack(spacing: 6) {
                    ForEach(AiThinking.allCases) { level in
                        let selected = prefs.customThinking == level
                        Button {
                            settings.setCustomThinking(level)
                        } label: {
                            Text(thinkingLabel(level))
                                .yoinRole(Tokens.type.labelMedium)
                                .lineLimit(1)
                                .frame(maxWidth: .infinity, minHeight: 40)
                                .foregroundStyle(selected ? app.onPrimary : app.onSurface)
                                .background(
                                    selected ? app.primary : row,
                                    in: .rect(cornerRadius: selected ? 20 : 12, style: .circular)
                                )
                        }
                        .buttonStyle(.plain)
                        .pointerHighlight()
                        .animation(Yoin.spatialFast, value: selected)
                    }
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Menu {
                    ForEach(AiSearchService.allCases) { service in
                        Button {
                            flushSearchKey(for: prefs.searchService)
                            settings.setSearchService(service)
                            searchKeyDraft = settings.searchKey(for: service)
                        } label: {
                            if service == prefs.searchService {
                                Label { Text(searchServiceName(service)) } icon: { Image("yoin.check") }
                            } else {
                                Text(searchServiceName(service))
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 12) {
                        YoinIcon("search", size: 20)
                            .foregroundStyle(app.onSurfaceVariant)
                        Text(copy("Search", zhHans: "搜索服务"))
                            .yoinRole(Tokens.type.bodyLarge)
                            .foregroundStyle(app.onSurface)
                        Spacer()
                        Text(searchServiceName(prefs.searchService))
                            .yoinRole(Tokens.type.bodyLarge)
                            .foregroundStyle(app.onSurfaceVariant)
                            .lineLimit(1)
                        YoinIcon("unfold_more", size: 20)
                            .foregroundStyle(app.onSurfaceVariant)
                    }
                    .padding(.horizontal, 16)
                    .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                    .background(row, in: CloudSyncView.segment(0, prefs.searchService == .none ? 1 : 2))
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .pointerHighlight()

                if prefs.searchService != .none {
                    let activeSearch = prefs.searchService
                    VStack(alignment: .leading, spacing: 6) {
                        keySegment(
                            label: copy("\(searchServiceName(activeSearch)) key", zhHans: "\(searchServiceName(activeSearch))密钥"),
                            placeholder: "",
                            text: $searchKeyDraft,
                            field: .searchKey,
                            savedValue: settings.searchKey(for: activeSearch),
                            onCommit: { value in settings.saveSearchKey(value, for: activeSearch) },
                            onClear: {
                                searchKeyDraft = ""
                                settings.saveSearchKey("", for: activeSearch)
                            }
                        )
                        .onChange(of: searchKeyDraft) { _, newValue in
                            scheduleSearchKeySave(newValue, service: activeSearch)
                        }
                        .background(row, in: CloudSyncView.segment(1, 2))

                        if let searchUrl = searchKeyURL(for: activeSearch), let url = URL(string: searchUrl.url) {
                            Button { openURL(url) } label: {
                                HStack(spacing: 5) {
                                    Text(searchUrl.title)
                                        .yoinRole(Tokens.type.labelLarge)
                                    YoinIcon("open_in_full", size: 13)
                                }
                                .foregroundStyle(app.primary)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 6)
                            }
                            .buttonStyle(.plain)
                            .pointerHighlight()
                        }
                    }
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
        }
    }

    private var modelInputRow: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(copy("Model", zhHans: "模型"))
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(focus == .customModel ? app.primary : app.onSurfaceVariant)
                TextField("", text: $modelDraft, prompt: Text("gpt-4o-mini / claude-sonnet").foregroundStyle(app.onSurfaceVariant.opacity(0.5)))
                    .font(.gsf(Tokens.type.bodyLarge.size.cg))
                    .foregroundStyle(app.onSurface)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focus, equals: .customModel)
                    .onChange(of: modelDraft) { _, newValue in
                        settings.setCustomModel(newValue)
                    }
            }
            Button { pullCustomModels() } label: {
                Group {
                    if fetchingModels {
                        ProgressView().controlSize(.small)
                    } else {
                        Text(copy("Fetch", zhHans: "拉取"))
                            .yoinRole(Tokens.type.labelLarge)
                    }
                }
                .foregroundStyle(canPullModels ? app.primary : app.onSurface.opacity(0.38))
                .padding(.horizontal, 12)
                .frame(minHeight: 36)
                .background(app.surfaceContainer, in: .capsule)
            }
            .buttonStyle(.plain)
            .pointerHighlight()
            .disabled(!canPullModels || fetchingModels)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .contentShape(.rect)
        .onTapGesture { focus = .customModel }
    }

    private var canPullModels: Bool {
        !baseUrlDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !providerKeyDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    // MARK: - Language Row

    private var languageSection: some View {
        Menu {
            ForEach(GeminiSettings.languages, id: \.self) { language in
                Button {
                    settings.saveLanguage(language)
                } label: {
                    if language == settings.targetLanguage {
                        Label { Text(language) } icon: { Image("yoin.check") }
                    } else {
                        Text(language)
                    }
                }
            }
        } label: {
            HStack(spacing: 12) {
                YoinIcon("translate", size: 20)
                    .foregroundStyle(app.onSurfaceVariant)
                Text(copy("Answer language", zhHans: "回答语言"))
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurface)
                Spacer()
                Text(settings.targetLanguage)
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurfaceVariant)
                    .lineLimit(1)
                YoinIcon("unfold_more", size: 20)
                    .foregroundStyle(app.onSurfaceVariant)
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .background(row, in: CloudSyncView.segment(0, 1))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .pointerHighlight()
    }

    // MARK: - Shared Form Rows

    private func sectionLabel(_ title: String) -> some View {
        Text(title)
            .yoinRole(Tokens.type.labelLarge)
            .foregroundStyle(app.onSurfaceVariant)
            .padding(.horizontal, 8)
    }

    private func textInputRow(
        label: String,
        placeholder: String,
        text: Binding<String>,
        field: Field
    ) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(focus == field ? app.primary : app.onSurfaceVariant)
            TextField("", text: text, prompt: Text(placeholder).foregroundStyle(app.onSurfaceVariant.opacity(0.55)))
                .font(.gsf(Tokens.type.bodyLarge.size.cg))
                .foregroundStyle(app.onSurface)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .focused($focus, equals: field)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .contentShape(.rect)
        .onTapGesture { focus = field }
    }

    private func keySegment(
        label: String,
        placeholder: String,
        text: Binding<String>,
        field: Field,
        savedValue: String,
        onCommit: @escaping (String) -> Void,
        onClear: @escaping () -> Void
    ) -> some View {
        let trimmed = text.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines)
        return HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(focus == field ? app.primary : app.onSurfaceVariant)
                SecureField("", text: text, prompt: Text(placeholder).foregroundStyle(app.onSurfaceVariant.opacity(0.55)))
                    .font(.gsf(Tokens.type.bodyLarge.size.cg))
                    .foregroundStyle(app.onSurface)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.password)
                    .focused($focus, equals: field)
                    .submitLabel(.done)
                    .onSubmit {
                        focus = nil
                        onCommit(trimmed)
                    }
            }
            if !trimmed.isEmpty, trimmed != savedValue {
                Button {
                    focus = nil
                    onCommit(trimmed)
                } label: {
                    Text(copy("Save", zhHans: "保存"))
                        .yoinRole(Tokens.type.labelMedium)
                        .foregroundStyle(app.onPrimary)
                        .padding(.horizontal, 10)
                        .frame(height: 30)
                        .background(app.primary, in: .capsule)
                }
                .buttonStyle(.plain)
                .pointerHighlight()
            } else if !savedValue.isEmpty {
                Button(action: onClear) {
                    YoinIcon("close", size: 16)
                        .foregroundStyle(app.onSurfaceVariant)
                        .frame(width: 30, height: 30)
                        .contentShape(.circle)
                }
                .buttonStyle(.plain)
                .pointerHighlight()
                .accessibilityLabel(copy("Clear key", zhHans: "清除密钥"))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .contentShape(.rect)
        .onTapGesture { focus = field }
    }

    // MARK: - Helpers & Labels

    private func scheduleProviderKeySave(_ draft: String, provider: AiProvider) {
        providerKeySaveTask?.cancel()
        providerKeySaveTask = Task {
            try? await Task.sleep(for: .milliseconds(450))
            guard !Task.isCancelled else { return }
            settings.saveKey(draft, for: provider)
        }
    }

    private func scheduleSearchKeySave(_ draft: String, service: AiSearchService) {
        searchKeySaveTask?.cancel()
        searchKeySaveTask = Task {
            try? await Task.sleep(for: .milliseconds(450))
            guard !Task.isCancelled else { return }
            settings.saveSearchKey(draft, for: service)
        }
    }

    private func flushProviderKey(for provider: AiProvider) {
        providerKeySaveTask?.cancel()
        providerKeySaveTask = nil
        settings.saveKey(providerKeyDraft, for: provider)
    }

    private func flushSearchKey(for service: AiSearchService) {
        searchKeySaveTask?.cancel()
        searchKeySaveTask = nil
        settings.saveSearchKey(searchKeyDraft, for: service)
    }

    private func flushPendingKeys() {
        flushProviderKey(for: prefs.provider)
        if prefs.provider == .custom {
            flushSearchKey(for: prefs.searchService)
            settings.setCustomBaseUrl(baseUrlDraft)
            settings.setCustomModel(modelDraft)
        }
    }

    private func pullCustomModels() {
        flushPendingKeys()
        let url = baseUrlDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = providerKeyDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty, !key.isEmpty else { return }
        fetchingModels = true
        modelFetchError = nil
        let format = prefs.customFormat
        Task {
            do {
                let ids = try await GeminiClient().listCustomModels(format: format, baseUrl: url, apiKey: key)
                discoveredModels = ids
                fetchingModels = false
            } catch {
                modelFetchError = (error as? GeminiClient.Failure)?.message ?? error.localizedDescription
                fetchingModels = false
            }
        }
    }

    private func providerName(_ provider: AiProvider) -> String {
        switch provider {
        case .gemini: "Gemini"
        case .deepSeek: "DeepSeek"
        case .qwen: copy("Qwen", zhHans: "千问")
        case .custom: copy("Custom", zhHans: "自定义")
        }
    }

    private func tierName(_ tier: AiTier) -> String {
        switch tier {
        case .good: copy("Good", zhHans: "好")
        case .best: copy("Best", zhHans: "最佳")
        }
    }

    private func thinkingLabel(_ thinking: AiThinking) -> String {
        switch thinking {
        case .off: copy("Off", zhHans: "不思考")
        case .low: copy("Low", zhHans: "低")
        case .medium: copy("Medium", zhHans: "中")
        case .high: copy("High", zhHans: "高")
        }
    }

    private func searchServiceName(_ service: AiSearchService) -> String {
        switch service {
        case .deepSeek: "DeepSeek"
        case .exa: "Exa"
        case .tavily: "Tavily"
        case .brave: "Brave"
        case .doubao: copy("Doubao", zhHans: "豆包")
        case .none: copy("No search", zhHans: "不搜索")
        }
    }

    private func keyLink(for provider: AiProvider) -> (title: String, url: String)? {
        switch provider {
        case .gemini:
            (copy("Get a key in Google AI Studio", zhHans: "在 Google AI Studio 获取"), "https://aistudio.google.com/apikey")
        case .deepSeek:
            (copy("Get a key on the DeepSeek platform", zhHans: "在 DeepSeek 开放平台获取"), "https://platform.deepseek.com/api_keys")
        case .qwen:
            (copy("Get a key in Alibaba Cloud Model Studio", zhHans: "在阿里云百炼获取"), "https://bailian.console.aliyun.com/?tab=model#/api-key")
        case .custom:
            nil
        }
    }

    private func searchKeyURL(for service: AiSearchService) -> (title: String, url: String)? {
        switch service {
        case .deepSeek:
            (copy("Get a DeepSeek key", zhHans: "获取 DeepSeek 密钥"), "https://platform.deepseek.com/api_keys")
        case .exa:
            (copy("Get an Exa key", zhHans: "获取 Exa 密钥"), "https://dashboard.exa.ai/api-keys")
        case .tavily:
            (copy("Get a Tavily key", zhHans: "获取 Tavily 密钥"), "https://app.tavily.com/home")
        case .brave:
            (copy("Get a Brave key", zhHans: "获取 Brave 密钥"), "https://api-dashboard.search.brave.com/app/keys")
        case .doubao:
            (copy("Get a Doubao key", zhHans: "获取豆包密钥"), "https://console.volcengine.com/search-infinity/api-key")
        case .none:
            nil
        }
    }
}

// MARK: - Benchmark Scatter Chart

private struct AiTradeoffChart: View {
    var provider: AiProvider
    var tier: AiTier
    var searchService: AiSearchService
    var app: CoverScheme
    var row: Color
    var dark: Bool
    var zh: Bool

    @State private var viewIndex = 0
    @State private var flipTurns: Double = 0

    private var axes: (x: AiAxis, y: AiAxis) {
        aiChartViews[viewIndex % aiChartViews.count]
    }

    private var activeSet: AiChartSet {
        AiChartSet.forProvider(provider)
    }

    private func copy(_ en: String, zhHans: String) -> String { zh ? zhHans : en }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { geo in
                let plot = CGRect(
                    x: 42,
                    y: 8,
                    width: max(40, geo.size.width - 52),
                    height: max(40, geo.size.height - 34)
                )
                ZStack(alignment: .topLeading) {
                    Canvas { ctx, _ in
                        let gridColor = app.outlineVariant.opacity(0.45)
                        let axisPath = Path { p in
                            p.move(to: CGPoint(x: plot.minX, y: plot.minY))
                            p.addLine(to: CGPoint(x: plot.minX, y: plot.maxY))
                            p.addLine(to: CGPoint(x: plot.maxX, y: plot.maxY))
                        }
                        ctx.stroke(axisPath, with: .color(gridColor), lineWidth: 1)

                        let points = currentDotEntries()
                        for dot in points {
                            let pt = pointPosition(dot.point, set: activeSet, in: plot)
                            if dot.chosen {
                                ctx.fill(
                                    Path(ellipseIn: CGRect(x: pt.x - 10, y: pt.y - 10, width: 20, height: 20)),
                                    with: .color(dot.color.opacity(0.24))
                                )
                            }
                            let r: CGFloat = dot.chosen ? 5.5 : 4
                            ctx.fill(
                                Path(ellipseIn: CGRect(x: pt.x - r, y: pt.y - r, width: r * 2, height: r * 2)),
                                with: .color(dot.color.opacity(dot.dimmed ? 0.38 : 1))
                            )
                        }
                    }

                    // Axis range corner labels + titles
                    Text(axes.y.axisValue(in: activeSet, worst: false))
                        .font(.gsf(10)).foregroundStyle(app.onSurfaceVariant)
                        .position(x: 20, y: plot.minY + 6)
                    Text(axes.y.axisValue(in: activeSet, worst: true))
                        .font(.gsf(10)).foregroundStyle(app.onSurfaceVariant)
                        .position(x: 20, y: plot.maxY - 6)
                    Text(axisLabel(axes.y))
                        .font(.gsf(11, .semibold)).foregroundStyle(app.onSurface)
                        .position(x: plot.minX + 54, y: plot.minY + 8)

                    Text(axes.x.axisValue(in: activeSet, worst: true))
                        .font(.gsf(10)).foregroundStyle(app.onSurfaceVariant)
                        .position(x: plot.minX + 16, y: plot.maxY + 12)
                    Text(axes.x.axisValue(in: activeSet, worst: false))
                        .font(.gsf(10)).foregroundStyle(app.onSurfaceVariant)
                        .position(x: plot.maxX - 16, y: plot.maxY + 12)
                    Text(axisLabel(axes.x))
                        .font(.gsf(11, .semibold)).foregroundStyle(app.onSurface)
                        .position(x: plot.midX, y: plot.maxY + 22)

                    let entries = currentDotEntries()
                    let positions = entries.map { pointPosition($0.point, set: activeSet, in: plot) }
                    let labelIndices = entries.indices.filter { !entries[$0].label.isEmpty }
                    let sizes: [(index: Int, size: CGSize)] = labelIndices.map { i in
                        let w = CGFloat(max(entries[i].label.count, 2)) * 7.2 + 8
                        return (i, CGSize(width: w, height: 15))
                    }
                    let offsets = AiChartLabels.place(plot: plot, dots: positions, dotRadius: 9, gap: 8, labels: sizes)
                    ForEach(Array(zip(sizes, offsets)), id: \.0.index) { item, offset in
                        let entry = entries[item.index]
                        let dotPos = positions[item.index]
                        Text(entry.label)
                            .font(.gsf(11, entry.chosen ? .bold : .medium))
                            .foregroundStyle(app.onSurface.opacity(entry.dimmed ? 0.5 : 1))
                            .position(
                                x: dotPos.x + offset.x + item.size.width / 2,
                                y: dotPos.y + offset.y + item.size.height / 2
                            )
                    }
                }
            }
            .frame(height: 196)
            .animation(Yoin.spatial, value: viewIndex)
            .animation(Yoin.spatial, value: provider)
            .animation(Yoin.spatial, value: tier)
            .animation(Yoin.spatial, value: searchService)

            HStack(alignment: .center, spacing: 8) {
                legendRow
                Spacer(minLength: 4)
                Button {
                    viewIndex = (viewIndex + 1) % aiChartViews.count
                    flipTurns += 1
                } label: {
                    HStack(spacing: 5) {
                        YoinIcon("refresh", size: 15)
                            .rotationEffect(.degrees(flipTurns * 180))
                            .animation(Yoin.spatial, value: flipTurns)
                        Text(copy("Change view", zhHans: "换个角度"))
                            .yoinRole(Tokens.type.labelMedium)
                    }
                    .foregroundStyle(app.onSurface)
                    .padding(.horizontal, 12)
                    .frame(height: 32)
                    .background(row, in: .capsule)
                }
                .buttonStyle(.plain)
                .pointerHighlight()
            }

            if activeSet == .search {
                Text(copy(
                    "Tested with deepseek-flash as the writing model, changing only the search service. Tavily and Brave are estimated from 35 songs; the rest were measured on 499.",
                    zhHans: "测试时都用 deepseek-flash 写回答，只换搜索服务。Tavily 和 Brave 按 35 首歌估算，其余是 499 首歌实测。"
                ))
                .yoinRole(Tokens.type.bodySmall)
                .foregroundStyle(app.onSurfaceVariant)
            }
        }
        .padding(14)
        .background(row.opacity(0.6), in: .rect(cornerRadius: 20, style: .circular))
    }

    private struct DotEntry {
        let point: any AiChartPoint
        let color: Color
        let label: String
        let chosen: Bool
        let dimmed: Bool
    }

    private func currentDotEntries() -> [DotEntry] {
        switch activeSet {
        case .tiers:
            return AiTierBenchmark.points.map { p in
                let isChosen = p.provider == provider && p.tier == tier
                let mine = p.provider == provider
                let rawLabel: String = {
                    guard p.provider.tiers.count > 1, mine else { return "" }
                    let base = p.tier == .good ? copy("Good", zhHans: "好") : copy("Best", zhHans: "最佳")
                    return p.estimated ? copy("\(base) (est.)", zhHans: "\(base)（估）") : base
                }()
                return DotEntry(
                    point: p,
                    color: providerDotColor(p.provider),
                    label: rawLabel,
                    chosen: isChosen,
                    dimmed: !mine
                )
            }
        case .search:
            return AiSearchBenchmark.points.map { p in
                let isChosen = p.service == searchService
                let name = searchLabel(p.service)
                let label = p.estimated ? copy("\(name) (est.)", zhHans: "\(name)（估）") : name
                return DotEntry(
                    point: p,
                    color: searchDotColor(p.service),
                    label: label,
                    chosen: isChosen,
                    dimmed: !isChosen
                )
            }
        }
    }

    private func pointPosition(_ point: any AiChartPoint, set: AiChartSet, in plot: CGRect) -> CGPoint {
        let xf = CGFloat(axes.x.fraction(point, in: set))
        let yf = CGFloat(axes.y.fraction(point, in: set))
        return CGPoint(
            x: plot.minX + xf * plot.width,
            y: plot.maxY - yf * plot.height
        )
    }

    private var legendRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                switch activeSet {
                case .tiers:
                    legendItem(name: "Gemini", color: providerDotColor(.gemini))
                    legendItem(name: "DeepSeek", color: providerDotColor(.deepSeek))
                    legendItem(name: copy("Qwen", zhHans: "千问"), color: providerDotColor(.qwen))
                case .search:
                    ForEach(AiSearchBenchmark.points, id: \.service) { p in
                        legendItem(name: searchLabel(p.service), color: searchDotColor(p.service))
                    }
                }
            }
        }
    }

    private func legendItem(name: String, color: Color) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(name).yoinRole(Tokens.type.labelSmall).foregroundStyle(app.onSurfaceVariant)
        }
    }

    private func axisLabel(_ axis: AiAxis) -> String {
        switch axis {
        case .speed: copy("Faster", zhHans: "更快")
        case .cost: copy("Cheaper", zhHans: "更省")
        case .accuracy: copy("More accurate", zhHans: "更准")
        case .overall: copy("Accurate and fast", zhHans: "又准又快")
        }
    }

    private func searchLabel(_ service: AiSearchService) -> String {
        switch service {
        case .deepSeek: "DeepSeek"
        case .exa: "Exa"
        case .tavily: "Tavily"
        case .brave: "Brave"
        case .doubao: copy("Doubao", zhHans: "豆包")
        case .none: copy("No search", zhHans: "不搜索")
        }
    }

    private func providerDotColor(_ provider: AiProvider) -> Color {
        switch provider {
        case .gemini: dark ? Color(red: 0.45, green: 0.67, blue: 0.99) : Color(red: 0.10, green: 0.45, blue: 0.91)
        case .deepSeek: dark ? Color(red: 0.56, green: 0.78, blue: 0.62) : Color(red: 0.14, green: 0.55, blue: 0.34)
        case .qwen: dark ? Color(red: 0.75, green: 0.62, blue: 0.98) : Color(red: 0.46, green: 0.25, blue: 0.85)
        case .custom: app.primary
        }
    }

    private func searchDotColor(_ service: AiSearchService) -> Color {
        switch service {
        case .deepSeek: providerDotColor(.deepSeek)
        case .exa: dark ? Color(red: 0.45, green: 0.67, blue: 0.99) : Color(red: 0.10, green: 0.45, blue: 0.91)
        case .tavily: dark ? Color(red: 0.96, green: 0.72, blue: 0.42) : Color(red: 0.82, green: 0.45, blue: 0.08)
        case .brave: dark ? Color(red: 0.96, green: 0.53, blue: 0.45) : Color(red: 0.85, green: 0.28, blue: 0.18)
        case .doubao: dark ? Color(red: 0.48, green: 0.82, blue: 0.84) : Color(red: 0.05, green: 0.54, blue: 0.58)
        case .none: app.outline
        }
    }
}

// MARK: - Tiers Details Page

private struct AiTiersPage: View {
    var provider: AiProvider
    var zh: Bool

    private func copy(_ en: String, zhHans: String) -> String { zh ? zhHans : en }

    private var titleText: String {
        let name: String = switch provider {
        case .gemini: "Gemini"
        case .deepSeek: "DeepSeek"
        case .qwen: copy("Qwen", zhHans: "千问")
        case .custom: copy("Custom", zhHans: "自定义")
        }
        return copy("\(name) tiers", zhHans: "\(name) 档位")
    }

    var body: some View {
        SettingsPage(title: titleText) { app, row in
            VStack(alignment: .leading, spacing: 14) {
                ForEach(provider.tiers) { tier in
                    let point = AiTierBenchmark.point(provider: provider, tier: tier)
                    VStack(alignment: .leading, spacing: 10) {
                        Text(tier == .good ? copy("Good", zhHans: "好") : copy("Best", zhHans: "最佳"))
                            .yoinRole(Tokens.type.titleMedium)
                            .foregroundStyle(app.onSurface)

                        factRow(copy("Model", zhHans: "模型"), value: modelFor(tier), app: app)
                        factRow(copy("Search", zhHans: "搜索"), value: searchFor(tier), app: app)

                        if let point {
                            let est = point.estimated
                            let sec = AiAxis.trimNumber(point.seconds)
                            let acc = AiAxis.trimNumber(point.accuracy)
                            let cost = AiAxis.trimNumber(point.costPer1000)

                            factRow(
                                copy("Speed", zhHans: "速度"),
                                value: est ? copy("\(sec) s (est.)", zhHans: "\(sec) 秒（估）") : copy("\(sec) s", zhHans: "\(sec) 秒"),
                                app: app
                            )
                            factRow(
                                copy("Accuracy", zhHans: "准确"),
                                value: est ? copy("\(acc)% (est.)", zhHans: "\(acc)%（估）") : "\(acc)%",
                                app: app
                            )
                            factRow(
                                copy("Per 1,000 songs", zhHans: "每千首"),
                                value: (est || point.costEstimated)
                                    ? copy("$\(cost) (est.)", zhHans: "$\(cost)（估）")
                                    : "$\(cost)",
                                app: app
                            )
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(row, in: .rect(cornerRadius: 20, style: .circular))
                }

                if let note = providerNote {
                    Text(note)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .padding(.horizontal, 8)
                }

                Text(copy(
                    "Speed and accuracy from a 500-song test; cost at current prices.",
                    zhHans: "速度和准确来自 500 首歌的测试，花费按当前价格算。"
                ))
                .yoinRole(Tokens.type.bodySmall)
                .foregroundStyle(app.onSurfaceVariant)
                .padding(.horizontal, 8)
            }
        }
    }

    private func factRow(_ label: String, value: String, app: CoverScheme) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(app.onSurfaceVariant)
            Text(value)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(app.onSurface)
        }
    }

    private func modelFor(_ tier: AiTier) -> String {
        switch provider {
        case .gemini: tier == .best ? AiSettings.geminiBest : AiSettings.geminiGood
        case .deepSeek: AiSettings.deepSeekModel
        case .qwen: AiSettings.qwenModel
        case .custom: ""
        }
    }

    private func searchFor(_ tier: AiTier) -> String {
        switch provider {
        case .gemini: copy("Google Search", zhHans: "Google 搜索")
        case .qwen: copy("Qwen's own search", zhHans: "千问自带搜索")
        case .deepSeek:
            tier == .best
                ? copy("DeepSeek Search twice, full pages", zhHans: "DeepSeek 搜索 2 次，读全文")
                : copy("DeepSeek Search once, full pages", zhHans: "DeepSeek 搜索 1 次，读全文")
        case .custom: ""
        }
    }

    private var providerNote: String? {
        switch provider {
        case .gemini:
            copy(
                "The first 5,000 searches each month are free; after that, about $25 per 1,000 songs. A free key without billing can't search and gets error 429.",
                zhHans: "每月前 5,000 次搜索免费，超出后每千首约 $25。没开结算的免费密钥不能搜索，会报 429。"
            )
        case .deepSeek:
            copy(
                "Works in mainland China without a proxy. Search uses the same key and is charged per call.",
                zhHans: "中国大陆可直接直连使用，不需要代理。搜索用同一个密钥，按次收费。"
            )
        case .qwen:
            copy(
                "Works in mainland China without a proxy. Search uses the same key and is charged per call.",
                zhHans: "中国大陆可直接直连使用，不需要代理。搜索用同一个密钥，按次收费。"
            )
        case .custom:
            nil
        }
    }
}

// MARK: - Try a Song Page

private struct AiTryPage: View {
    var onOpen: () -> Void

    @Environment(AppModel.self) private var model
    @State private var settings = GeminiSettings.shared
    @State private var history = HistoryStore.shared
    @State private var runningId: String?
    @State private var outcome: Outcome?

    private enum Outcome {
        case done(track: PlayEvent, millis: Int, run: GeminiClient.AboutRun)
        case failed(track: PlayEvent, millis: Int, message: String)
    }

    private var zh: Bool { Tips.showsChinese }
    private func copy(_ en: String, zhHans: String) -> String { zh ? zhHans : en }

    private var songs: [PlayEvent] {
        guard model.store.isReal else { return [] }
        let raw = history.recentPlays(scope: model.store.historyScope, provider: model.store.provider, limit: 200)
        return AiTrySongs.recentDistinct(from: raw, limit: 6)
    }

    var body: some View {
        SettingsPage(title: copy("Try a song", zhHans: "拿一首歌试试")) { app, row in
            VStack(alignment: .leading, spacing: 16) {
                if songs.isEmpty {
                    Text(copy("Play a song first", zhHans: "先播一首歌"))
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurfaceVariant)
                        .padding(.horizontal, 8)
                } else {
                    VStack(spacing: 2) {
                        ForEach(Array(songs.enumerated()), id: \.element.songId) { index, song in
                            Button { runTest(song) } label: {
                                HStack(spacing: 12) {
                                    CoverArt(palette: palette(for: song), cornerRadius: 8)
                                        .frame(width: 44, height: 44)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(song.title)
                                            .yoinRole(Tokens.type.bodyLarge)
                                            .foregroundStyle(app.onSurface)
                                            .lineLimit(1)
                                        Text(song.artist)
                                            .yoinRole(Tokens.type.bodyMedium)
                                            .foregroundStyle(app.onSurfaceVariant)
                                            .lineLimit(1)
                                    }
                                    Spacer(minLength: 8)
                                    if runningId == song.songId {
                                        ProgressView().controlSize(.small)
                                    }
                                }
                                .padding(.horizontal, 14)
                                .padding(.vertical, 10)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(row, in: CloudSyncView.segment(index, songs.count))
                                .contentShape(.rect)
                            }
                            .buttonStyle(.plain)
                            .pointerHighlight()
                            .disabled(runningId != nil)
                        }
                    }
                }

                if let outcome {
                    resultCard(outcome, app: app, row: row)
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                }
            }
            .animation(Yoin.spatial, value: outcome != nil)
        }
        .onAppear(perform: onOpen)
    }

    @ViewBuilder
    private func resultCard(_ result: Outcome, app: CoverScheme, row: Color) -> some View {
        switch result {
        case let .done(track, millis, run):
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("\(track.title) · \(track.artist)")
                        .yoinRole(Tokens.type.titleSmall)
                        .foregroundStyle(app.onSurface)
                    Spacer()
                    Text("\(millis) ms · \(run.model)")
                        .yoinRole(Tokens.type.labelSmall)
                        .foregroundStyle(app.onSurfaceVariant)
                }
                Text(searchSummary(for: run))
                    .yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(app.primary)

                ForEach(run.values, id: \.key) { field in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(NowPlayingAboutView.label(field.key, expanded: true))
                            .yoinRole(Tokens.type.labelMedium)
                            .foregroundStyle(app.onSurfaceVariant)
                        Text(NowPlayingAboutView.markdown(field.answer))
                            .yoinRole(Tokens.type.bodyMedium)
                            .foregroundStyle(app.onSurface)
                    }
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(row, in: .rect(cornerRadius: 20, style: .circular))

        case let .failed(_, millis, message):
            VStack(alignment: .leading, spacing: 4) {
                Text(copy("Failed: \(message)", zhHans: "失败：\(message)"))
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(app.error)
                if millis > 0 {
                    Text("\(millis) ms")
                        .yoinRole(Tokens.type.labelSmall)
                        .foregroundStyle(app.onSurfaceVariant)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(row, in: .rect(cornerRadius: 20, style: .circular))
        }
    }

    private func searchSummary(for run: GeminiClient.AboutRun) -> String {
        switch run.searchKind {
        case .builtIn:
            return copy("The model searched on its own", zhHans: "模型自己搜索")
        case .external:
            let q = run.searchQueries.joined(separator: " / ")
            return copy(
                "Searched: \(q) · \(run.pagesRead) pages read",
                zhHans: "搜了：\(q) · 读了 \(run.pagesRead) 个网页"
            )
        case .none:
            return copy("No search", zhHans: "不搜索")
        }
    }

    private func runTest(_ song: PlayEvent) {
        onOpen()
        let ai = settings.effectiveAiSettings
        guard ai.isReady else {
            outcome = .failed(track: song, millis: 0, message: copy("Paste a key first", zhHans: "先贴上密钥"))
            return
        }
        let targetLanguage = settings.targetLanguage
        runningId = song.songId
        let started = ContinuousClock.now
        Task {
            do {
                let run = try await GeminiClient().runCanonicalAbout(
                    ai: ai,
                    title: song.title,
                    artist: song.artist,
                    album: song.album,
                    targetLanguage: targetLanguage
                )
                let elapsed = Int((ContinuousClock.now - started) / .milliseconds(1))
                outcome = .done(track: song, millis: elapsed, run: run)
            } catch is CancellationError {
                outcome = nil
            } catch {
                let elapsed = Int((ContinuousClock.now - started) / .milliseconds(1))
                let message = (error as? GeminiClient.Failure)?.message ?? error.localizedDescription
                outcome = .failed(track: song, millis: elapsed, message: message)
            }
            runningId = nil
        }
    }

    private func palette(for row: PlayEvent) -> CoverPalette {
        if row.provider == AccountScope.subsonicProvider, let client = model.store.client {
            return SubsonicMapping(client: client).palette(coverId: row.coverKey ?? row.albumId)
        }
        return AppleMusicMapping.palette(row.coverKey.flatMap(URL.init(string:)), key: row.songId)
    }
}
