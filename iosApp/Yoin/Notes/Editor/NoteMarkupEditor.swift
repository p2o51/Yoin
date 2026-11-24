import SwiftUI
import UIKit
import YoinShared

/*
 * The note editor (Android ui/notes/editor/NoteMarkupField.kt) for the Now Playing write bar, album stickies,
 * the review field and the scrapbook's song notes: a UITextView showing NoteEditorDisplay's text (mentions as
 * whole "@Title" chips, Markdown markers faint) over NoteEditorModel, which holds the stored text and runs
 * :shared's editor rules. Typing "@" opens the mention popup as the keyboard's accessory: it sits on the
 * keyboard's top edge, so it never covers the line being written or the keyboard, and the field above it
 * keeps clear of both. Return picks the popup's top (or highlighted) row; ↑ ↓ Tab Esc work on a hardware
 * keyboard.
 */
struct NoteMarkupEditor: View {
    var model: NoteEditorModel
    @Binding var text: String
    var prompt: Text?
    var size: CGFloat
    var textColor: Color
    var mentionColor: Color
    var tint: Color
    var scheme: CoverScheme
    var lines: ClosedRange<Int> = 1...8
    /// Extra leading between lines (YoinType.lineSpacing), as the read-only text has.
    var lineSpacing: CGFloat = 0
    /// The keyboard: true asks for it, and it follows the field.
    var focused: Binding<Bool>?

    var body: some View {
        NoteTextViewBridge(model: model, text: $text, size: size, textColor: UIColor(textColor),
                           mentionColor: UIColor(mentionColor), tint: UIColor(tint), scheme: scheme, lines: lines,
                           lineSpacing: lineSpacing, focused: focused)
            .overlay(alignment: .topLeading) {
                if text.isEmpty, let prompt {
                    prompt.font(.gsf(size)).allowsHitTesting(false).accessibilityHidden(true)
                }
            }
    }
}

private struct NoteTextViewBridge: UIViewRepresentable {
    var model: NoteEditorModel
    @Binding var text: String
    var size: CGFloat
    var textColor: UIColor
    var mentionColor: UIColor
    var tint: UIColor
    var scheme: CoverScheme
    var lines: ClosedRange<Int>
    var lineSpacing: CGFloat
    var focused: Binding<Bool>?

    func makeCoordinator() -> NoteTextViewCoordinator { NoteTextViewCoordinator(model: model) }

    func makeUIView(context: Context) -> NoteTextView {
        let view = NoteTextView()
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.isScrollEnabled = false
        view.adjustsFontForContentSizeCategory = false
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.delegate = context.coordinator
        view.coordinator = context.coordinator
        context.coordinator.attach(view)
        return view
    }

    func updateUIView(_ view: NoteTextView, context: Context) {
        let c = context.coordinator
        c.style = NoteEditorStyle(size: size, textColor: textColor, mentionColor: mentionColor, lineSpacing: lineSpacing,
                                  maxChipWidth: max(view.bounds.width, 120))
        c.scheme = scheme
        c.focusBinding = focused
        view.tintColor = tint
        model.onChange = { value in if text != value { text = value } }
        // An outside change (a draft cleared after saving, a note opened for editing).
        if text != model.text { model.setText(text) }
        // Read here, so a rewrite (a row picked, a tool) updates the view.
        _ = model.revision
        c.applyIfNeeded()
        if let focused {
            if focused.wrappedValue, !view.isFirstResponder, view.window != nil {
                DispatchQueue.main.async { view.becomeFirstResponder() }
            } else if !focused.wrappedValue, view.isFirstResponder {
                DispatchQueue.main.async { view.resignFirstResponder() }
            }
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView view: NoteTextView, context: Context) -> CGSize? {
        let width = proposal.width ?? view.bounds.width
        guard width > 0, width.isFinite else { return nil }
        let line = context.coordinator.style.font.lineHeight + lineSpacing
        let natural = view.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        let lo = line * CGFloat(lines.lowerBound), hi = line * CGFloat(lines.upperBound)
        let scrolls = natural > hi + 0.5
        if view.isScrollEnabled != scrolls { view.isScrollEnabled = scrolls }
        return CGSize(width: width, height: ceil(min(max(natural, lo), hi)))
    }
}

/// The text view: hardware ↑ ↓ Tab Esc drive the popup while it is open.
final class NoteTextView: UITextView {
    weak var coordinator: NoteTextViewCoordinator?

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if let key = presses.first?.key, coordinator?.handleKey(key) == true { return }
        super.pressesBegan(presses, with: event)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        coordinator?.windowChanged()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        coordinator?.widthChanged(bounds.width)
    }
}

@MainActor
final class NoteTextViewCoordinator: NSObject, UITextViewDelegate {
    let model: NoteEditorModel
    var style = NoteEditorStyle(size: 16, textColor: .label, mentionColor: .tintColor)
    var scheme: CoverScheme?
    var focusBinding: Binding<Bool>?
    private weak var view: NoteTextView?
    private var map = NoteEditorDisplayMap()
    private var appliedRevision = -1
    private var appliedWidth: CGFloat = 0
    private lazy var accessory = MentionPopupAccessory(model: model, scheme: { [weak self] in self?.scheme })

    init(model: NoteEditorModel) {
        self.model = model
    }

    func attach(_ view: NoteTextView) {
        self.view = view
        observePopup()
    }

    /// Shows the model's text when the editor rewrote it (or on first show).
    func applyIfNeeded() {
        guard let view, model.revision != appliedRevision || view.textStorage.length == 0 && !model.text.isEmpty else { return }
        apply(to: view)
    }

    private func apply(to view: NoteTextView) {
        appliedRevision = model.revision
        let (display, map) = NoteEditorDisplay.build(model.text, style: style)
        self.map = map
        view.attributedText = display
        let r = map.display(model.selection)
        view.selectedRange = NSRange(location: min(r.location, display.length), length: min(r.length, display.length - min(r.location, display.length)))
        view.typingAttributes = NoteEditorDisplay.baseAttributes(style)
        view.invalidateIntrinsicContentSize()
    }

    /// A field asked to take the keyboard before it was on screen takes it once it is.
    func windowChanged() {
        guard let view, view.window != nil, focusBinding?.wrappedValue == true, !view.isFirstResponder else { return }
        DispatchQueue.main.async { view.becomeFirstResponder() }
    }

    func widthChanged(_ width: CGFloat) {
        guard let view, abs(width - appliedWidth) > 1 else { return }
        appliedWidth = width
        style.maxChipWidth = max(width, 120)
        guard !map.spans.isEmpty, view.markedTextRange == nil else { return }
        apply(to: view)
    }

    // MARK: UITextViewDelegate

    func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
        // The keyboard is composing (pinyin, kana): its marked text goes in as it likes.
        if textView.markedTextRange != nil { return true }
        guard model.intercept(range: map.stored(range), replacement: text) != nil else { return true }
        // The model took a corrected edit: show it instead of the keyboard's.
        if let view { apply(to: view) }
        return false
    }

    func textViewDidChange(_ textView: UITextView) {
        sync(textView, restyle: textView.markedTextRange == nil)
        textView.invalidateIntrinsicContentSize()
    }

    func textViewDidChangeSelection(_ textView: UITextView) {
        if textView.markedTextRange == nil {
            textView.typingAttributes = NoteEditorDisplay.baseAttributes(style)
        }
        model.selectionChanged(map.stored(textView.selectedRange), composing: marked(textView))
    }

    func textViewDidBeginEditing(_ textView: UITextView) {
        model.focusChanged(true)
        if focusBinding?.wrappedValue == false { focusBinding?.wrappedValue = true }
    }

    func textViewDidEndEditing(_ textView: UITextView) {
        model.focusChanged(false)
        if focusBinding?.wrappedValue == true { focusBinding?.wrappedValue = false }
    }

    /// The text view's text into the model (and its look back from the Markdown).
    private func sync(_ textView: UITextView, restyle: Bool) {
        let stored = NoteEditorDisplay.stored(from: textView.textStorage)
        map = NoteEditorDisplay.map(of: textView.textStorage)
        if restyle {
            let selected = textView.selectedRange
            NoteEditorDisplay.restyle(textView.textStorage, stored: stored, map: map, style: style)
            textView.selectedRange = selected
            textView.typingAttributes = NoteEditorDisplay.baseAttributes(style)
        }
        model.textChanged(stored, selection: map.stored(textView.selectedRange), composing: marked(textView))
    }

    private func marked(_ textView: UITextView) -> NSRange? {
        guard let marked = textView.markedTextRange else { return nil }
        let a = textView.offset(from: textView.beginningOfDocument, to: marked.start)
        let b = textView.offset(from: textView.beginningOfDocument, to: marked.end)
        return map.stored(NSRange(location: a, length: b - a))
    }

    // MARK: The popup

    private func observePopup() {
        withObservationTracking { _ = model.popupOpen } onChange: { [weak self] in
            Task { @MainActor in
                self?.updateAccessory()
                self?.observePopup()
            }
        }
    }

    private func updateAccessory() {
        guard let view else { return }
        let open = model.popupOpen
        guard open != (view.inputAccessoryView != nil) else { return }
        view.inputAccessoryView = open ? accessory : nil
        view.reloadInputViews()
    }

    /// ↑ ↓ move the highlight, Tab picks it, Esc shuts the popup.
    func handleKey(_ key: UIKey) -> Bool {
        guard model.popupOpen else { return false }
        switch key.keyCode {
        case .keyboardUpArrow: model.moveHighlight(-1)
        case .keyboardDownArrow: model.moveHighlight(1)
        case .keyboardEscape: model.dismissPopup(); updateAccessory()
        case .keyboardTab:
            let all = model.rows.all
            guard all.indices.contains(model.highlighted) else { return false }
            model.pick(all[model.highlighted])
        default: return false
        }
        return true
    }
}

/// The popup as the keyboard's accessory: as tall as its rows, transparent around its card.
final class MentionPopupAccessory: UIView {
    private let host: UIHostingController<MentionPopup>
    private var height: CGFloat = 120

    init(model: NoteEditorModel, scheme: @escaping @MainActor () -> CoverScheme?) {
        host = UIHostingController(rootView: MentionPopup(model: model, scheme: scheme, onHeight: { _ in }))
        super.init(frame: CGRect(x: 0, y: 0, width: 320, height: 120))
        autoresizingMask = .flexibleHeight
        backgroundColor = .clear
        host.view.backgroundColor = .clear
        host.view.translatesAutoresizingMaskIntoConstraints = false
        addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: trailingAnchor),
            host.view.topAnchor.constraint(equalTo: topAnchor),
            host.view.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        host.rootView = MentionPopup(model: model, scheme: scheme) { [weak self] h in self?.setHeight(h) }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    private func setHeight(_ h: CGFloat) {
        guard abs(h - height) > 0.5 else { return }
        height = h
        invalidateIntrinsicContentSize()
    }

    override var intrinsicContentSize: CGSize { CGSize(width: UIView.noIntrinsicMetric, height: height) }
}

/// The popup's card (Android MentionPopup): "Mention", then the rows by kind with section labels; the
/// highlighted row (what Return picks) on secondaryContainer.
struct MentionPopup: View {
    var model: NoteEditorModel
    var scheme: @MainActor () -> CoverScheme?
    var onHeight: (CGFloat) -> Void

    /// Android PopupWidth / PopupMargin.
    static let width: CGFloat = 312
    static let margin: CGFloat = 8
    static let listMaxHeight: CGFloat = 264

    var body: some View {
        let s = scheme() ?? CoverScheme(seedArgb: 0xFF6750A4, dark: false)
        let rows = model.rows
        let all = rows.all
        VStack(alignment: .leading, spacing: 0) {
            Text("Mention")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(s.onSurfaceVariant)
                .padding(.horizontal, 16)
                .frame(height: 36)
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(rows.order, id: \.self) { kind in
                            let list = rows.of(kind)
                            if !list.isEmpty {
                                Text(Self.sectionLabel(kind))
                                    .yoinRole(Tokens.type.labelMedium)
                                    .foregroundStyle(s.primary)
                                    .padding(.leading, 16).padding(.top, 8).padding(.bottom, 2)
                                ForEach(list) { row in
                                    let index = all.firstIndex(of: row) ?? -1
                                    MentionPopupRow(row: row, query: model.trigger?.query ?? "",
                                                    highlighted: index == model.highlighted, scheme: s) {
                                        model.pick(row)
                                    }
                                    .id(row.id)
                                }
                            }
                        }
                        if rows.isEmpty {
                            Text(model.loading ? String(localized: "Searching…")
                                 : String(localized: "notes.mention.empty", defaultValue: "No results"))
                                .yoinRole(Tokens.type.bodyMedium)
                                .foregroundStyle(s.onSurfaceVariant)
                                .padding(.horizontal, 16).padding(.vertical, 12)
                        }
                    }
                    .padding(.bottom, 6)
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(maxHeight: Self.listMaxHeight)
                .fixedSize(horizontal: false, vertical: true)
                .onChange(of: model.highlighted) { _, i in
                    if all.indices.contains(i) { withAnimation(Yoin.effects) { proxy.scrollTo(all[i].id) } }
                }
            }
        }
        .frame(maxWidth: Self.width, alignment: .leading)
        .background(s.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: .black.opacity(0.12), radius: 6, y: 2)
        .padding(.horizontal, Self.margin)
        .padding(.vertical, Self.margin)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Its own height, whatever the accessory has now: the accessory grows or shrinks to it.
        .fixedSize(horizontal: false, vertical: true)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { onHeight($0) }
        .frame(maxHeight: .infinity, alignment: .bottom)
        .animation(Yoin.effects, value: model.highlighted)
    }

    static func sectionLabel(_ kind: MentionKind) -> String {
        switch kind {
        case .track: String(localized: "Songs")
        case .album: String(localized: "Albums")
        default: String(localized: "Artists")
        }
    }
}

private struct MentionPopupRow: View {
    var row: MentionRow
    var query: String
    var highlighted: Bool
    var scheme: CoverScheme
    var onPick: () -> Void

    var body: some View {
        Button(action: onPick) {
            HStack(spacing: 12) {
                cover
                    .frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 0) {
                    Text(Self.highlight(row.title, query: query, color: scheme.primary))
                        .font(.gsf(Tokens.type.bodyLarge.size.cg))
                        .foregroundStyle(scheme.onSurface)
                        .lineLimit(1)
                    // Artist, then the album muted (the app's meta line).
                    if row.artist != nil || row.album != nil {
                        HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
                            if let artist = row.artist { Text(artist).layoutPriority(1) }
                            if let album = row.album { Text(album).opacity(Tokens.metaLine.MUTED_ALPHA) }
                        }
                        .font(.gsf(Tokens.type.bodySmall.size.cg))
                        .foregroundStyle(scheme.onSurfaceVariant)
                        .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(highlighted ? scheme.secondaryContainer : .clear,
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 6)
        .accessibilityLabel(Self.label(row))
    }

    @ViewBuilder private var cover: some View {
        let shape = row.kind == .artist ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        if let ref = row.cover {
            RemoteCover(ref: ref, placeholder: scheme.surfaceContainerHighest).clipShape(shape)
        } else {
            shape.fill(scheme.surfaceContainerHighest)
                .overlay {
                    Image(row.kind == .album ? "yoin.album" : row.kind == .artist ? "yoin.artist" : "yoin.music_note")
                        .font(.system(size: 18))
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
        }
    }

    /// Android highlightMatch: the typed words in the title, bold in the primary colour.
    static func highlight(_ title: String, query: String, color: Color) -> AttributedString {
        var out = AttributedString(title)
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty, let r = out.range(of: q, options: .caseInsensitive) else { return out }
        out[r].font = .gsf(Tokens.type.bodyLarge.size.cg, .bold)
        out[r].foregroundColor = color
        return out
    }

    static func label(_ row: MentionRow) -> String {
        switch row.kind {
        case .track: String(localized: "Song: \(row.title)")
        case .album: String(localized: "Album: \(row.title)")
        default: String(localized: "Artist: \(row.title)")
        }
    }
}
