import Shared
import SwiftUI

/// A fixed purpose-colored header with WLHeaderSheet's real scroll-coupled candidate sheet.
struct PurposeDetailScreen: View {
    let purposeID: String
    @Environment(\.wlEntryOwners) private var owners
    @Environment(\.wlRuntime) private var runtime
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.wlNavigator) private var nav
    @Environment(\.overlayHostState) private var overlay
    @Environment(\.wlColors) private var c
    @Environment(\.scenePhase) private var phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var sheet = WLHeaderSheetState()
    private var form: PurposeFormOwner? {
        get { owners.purposeDetail(entryID ?? -1).form }
        nonmutating set { owners.purposeDetail(entryID ?? -1).form = newValue }
    }
    @State private var name = ""
    @State private var description = ""
    @State private var menuAnchor = WLAnchor()
    @State private var anchors = ListAnchorBox()
    @State private var atTop = true

    var body: some View {
        let owner = owners.purposeDetail(entryID ?? -1)
        let detail = owner.state.detail
        let visible = PurposeDetailOwner.isVisible(entryID: entryID, navigator: nav, phase: phase)
        let color = PurposeStyle.color(form?.state.colorKey ?? detail?.colorKey ?? "")
        GeometryReader { geometry in
            let safeTop = geometry.safeAreaInsets.top
            ZStack {
                color.ignoresSafeArea()
                if let detail {
                    WLHeaderSheet(state: sheet, expandedTop: WLTopBarMetrics.bottom(safeTop: safeTop), barBackground: color) { progress in
                        header(detail, safeTop: safeTop, progress: progress)
                    } bar: { progress in
                        bar(detail, owner: owner, safeTop: safeTop, progress: progress)
                    } content: {
                        candidateContent(owner: owner, detail: detail)
                    }
                    .refreshable { await owner.refreshAndWait(anchor: firstVisible) }
                } else {
                    VStack {
                        WLTopBar { WLCircleButton(.back, label: String(localized: "purpose.back")) { nav.pop() } } center: { EmptyView() } trailing: { EmptyView() }
                        Spacer()
                        if owner.state.gone {
                            DetailStatusBlock(message: String(localized: "purpose.gone"), buttonText: String(localized: "purpose.close")) { nav.pop() }
                        } else if let error = owner.state.error {
                            DetailStatusBlock(message: DetailLine(DetailText.errorKey(error)).resolve(), buttonText: String(localized: "purpose.retry")) { owner.retry() }
                        } else { ProgressView() }
                        Spacer()
                    }
                }
            }
            .task(id: geometry.size.height) {
                // C7 restore: a card goes right under the expanded sheet's top band (the sheet scrolls the whole screen).
                anchors.topInset = WLTopBarMetrics.bottom(safeTop: safeTop) + 28 + 8
                anchors.viewportHeight = geometry.size.height + safeTop + geometry.safeAreaInsets.bottom
            }
        }
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                if let form {
                    Button(String(localized: "purpose.cancel")) { cancelEdit() }.disabled(form.state.submitting)
                    Spacer()
                    Button(String(localized: "purpose.save")) { form.presenter.submit() }.disabled(!form.state.canSubmit || form.state.submitting)
                }
            }
        }
        .task(id: visible) {
            if visible { await runtime?.purposeUntilReady() }
            guard !Task.isCancelled else { return }
            owner.navigationChanged(id: purposeID, entryID: entryID, navigator: nav, phase: phase, anchor: firstVisible)
        }
        #if DEBUG
        .onChange(of: detail?.id, initial: true) { _, id in
            guard id != nil, let detail else { return }
            let screen = DebugLaunchHooks.string(of: "wl.purpose.screen", in: ProcessInfo.processInfo.arguments)
            if screen == "edit" { beginEditing(detail) }
            if screen == "expanded" { sheet.animate(to: .expanded) }
        }
        #endif
        .onChange(of: form?.state.result) { _, result in
            guard let result else { return }
            owner.applyEdited(result)
            endEditing(refreshRecoveredHome: false)
            refreshHome()
        }
        .onChange(of: form?.state.message) { _, message in
            if message == .conflictLoaded { copyDraft() }
        }
        .onChange(of: form?.state.gone) { _, gone in
            if gone == true { endEditing(refreshRecoveredHome: false); owner.refresh(); refreshHome() }
        }
        .onChange(of: nav.scrollToTopRequest) { _, tab in
            guard tab == .purpose, nav.entries(.purpose).last?.id == entryID else { return }
            sheet.animate(to: .resting); nav.consumeScrollToTop(.purpose)
        }

    }

    private func header(_ detail: PurposeDetail, safeTop: CGFloat, progress: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            // Title is drawn in the front bar layer; reserve its original line here.
            Color.clear.frame(height: 44)
            Group {
                if let form {
                    WLUnderlineField(value: $description, style: WLTextStyle.body.resized(14),
                                     editable: !form.state.submitting, placeholder: String(localized: "purpose.description"), underlineColor: WishlistTokens.Purpose.onPurpose.opacity(0.3),
                                     foregroundColor: WishlistTokens.Purpose.onPurpose, maxLength: 200, limitUnit: .codePoint, multiline: true)
                        .onChange(of: description) { _, value in form.presenter.changeDescription(value: value) }
                } else if let text = detail.purposeDescription, !text.isEmpty {
                    WLText(text, WLTextStyle.body.resized(14), color: WishlistTokens.Purpose.onPurpose, maxLines: 3)
                }
            }.opacity(PurposePresentation.detailOpacity(progress: progress))
            if let form {
                VStack(alignment: .leading, spacing: 16) {
                    PurposePalette(owner: form)
                    HStack {
                        PurposeFormButtons(owner: form, inPlace: true) { cancelEdit() }
                        Spacer(minLength: 0)
                    }
                    PurposeFormMessage(owner: form)
                }
                .opacity(PurposePresentation.detailOpacity(progress: progress))
                .transition(.opacity.combined(with: .move(edge: .top)))
            } else {
                HStack(spacing: 8) {
                    disabledAction(String(localized: "purpose.add.candidates"), primary: false)
                    if detail.candidateCount > 0 { disabledAction(String(localized: "purpose.finish"), primary: true) }
                }.fixedSize(horizontal: true, vertical: false)
                    .opacity(PurposePresentation.detailOpacity(progress: progress))
            }
        }
        .padding(.top, WLTopBarMetrics.bottom(safeTop: safeTop) + 8)
        .padding(.horizontal, 20).padding(.bottom, 28)
        .animation(reduceMotion ? nil : WishlistTokens.Curve.ease.animation(ms: 260), value: form != nil)
    }

    private func bar(_ detail: PurposeDetail, owner: PurposeDetailOwner, safeTop: CGFloat, progress: CGFloat) -> some View {
        let size = PurposePresentation.iconSize(progress: progress)
        let top = WLTopBarMetrics.bottom(safeTop: safeTop) + 8
        let collapsed = WLTopBarMetrics.buttonTop(safeTop: safeTop) + 4
        return ZStack(alignment: .topLeading) {
            HStack {
                WLCircleButton(.back, label: String(localized: "purpose.back")) { nav.pop() }
                Spacer()
                if progress > 0.5 && form == nil {
                    WLCircleButton(.plus, label: String(localized: "purpose.add.candidates.accessibility")) {}.disabled(true).opacity(Double((progress - 0.5) * 2))
                }
                if form == nil {
                    WLCircleButton(.more, label: String(localized: "purpose.menu")) { menu(detail, owner: owner) }.wlAnchor(menuAnchor)
                }
            }.padding(.horizontal, 20).padding(.top, WLTopBarMetrics.buttonTop(safeTop: safeTop))
            HStack(spacing: 12) {
                PurposeIconTile(key: form?.state.iconKey ?? detail.iconKey, size: size)
                if let form {
                    WLUnderlineField(value: $name, style: WLTextStyle.display28.resized(PurposePresentation.nameSize(progress: progress)),
                                     editable: !form.state.submitting, placeholder: String(localized: "purpose.name"), underlineColor: WishlistTokens.Purpose.onPurpose.opacity(0.3),
                                     foregroundColor: WishlistTokens.Purpose.onPurpose, maxLength: 40, limitUnit: .codePoint)
                        .onChange(of: name) { _, value in form.presenter.changeName(value: value) }
                        .accessibilityLabel(String(localized: "purpose.name.accessibility"))
                } else {
                    WLText(detail.name, WLTextStyle.display28.resized(PurposePresentation.nameSize(progress: progress)),
                           color: WishlistTokens.Purpose.onPurpose, maxLines: 1).wlArrivalFocus()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 20 + 56 * progress)
            .padding(.trailing, 20 + 70 * progress)
            .padding(.top, top + (collapsed - top) * progress)
        }
    }

    private func disabledAction(_ title: String, primary: Bool) -> some View {
        Button {} label: {
            WLText(title, WLTextStyle.button.resized(14), color: primary ? c.onInverse : c.text)
                .padding(.horizontal, 18).frame(height: 44)
                .background(primary ? c.text : c.card, in: Capsule())
        }.buttonStyle(.plain).disabled(true).opacity(0.4)
    }

    private func menu(_ detail: PurposeDetail, owner: PurposeDetailOwner) {
        overlay?.showMenu(anchor: menuAnchor.frame, items: [
            WLMenuItem(text: String(localized: "purpose.edit"), icon: AnyView(WLIcon(.pen, size: 18)), enabled: detail.allowedActions.contains("EDIT")) { beginEditing(detail) },
            WLMenuItem(text: String(localized: "purpose.delete"), icon: AnyView(WLIcon(.trash, size: 18)), enabled: false) {},
        ])
    }

    private func beginEditing(_ detail: PurposeDetail) {
        sheet.animate(to: .resting)
        // Let the expanded sheet first return to its measured default, then extend the edit panel.
        Task { @MainActor in
            if sheet.progress > 0.01 { try? await Task.sleep(for: .milliseconds(reduceMotion ? 0 : 300)) }
            guard !Task.isCancelled, nav.entries(.purpose).contains(where: { $0.id == entryID }) else { return }
            let next = owners.purposeForm()
            next.presenter.startEdit(detail: detail)
            name = detail.name; description = detail.purposeDescription ?? ""
            withAnimation(reduceMotion ? nil : WishlistTokens.Curve.ease.animation(ms: 260)) { form = next }
            nav.setBackGuard(entryID ?? -1) {
                guard let form else { return true }
                if form.state.submitting { return false }
                if form.state.dirty {
                    if let overlay { overlay.showDialog(categoryDiscardDialog(overlay) { endEditing(); nav.pop() }) }
                    return false
                }
                endEditing(); return true
            }
        }
    }
    private func copyDraft() {
        guard let form else { return }
        name = form.state.name; description = form.state.purposeDescription
    }
    private func cancelEdit() {
        guard let form, !form.state.submitting else { return }
        if form.state.dirty, let overlay { overlay.showDialog(categoryDiscardDialog(overlay) { endEditing() }) }
        else { endEditing() }
    }
    private func endEditing(refreshRecoveredHome: Bool = true) {
        let owner = owners.purposeDetail(entryID ?? -1)
        let recovered = withAnimation(reduceMotion ? nil : WishlistTokens.Curve.ease.animation(ms: 260)) { owner.finishEditing() }
        if recovered && refreshRecoveredHome { refreshHome() }
        nav.setBackGuard(entryID ?? -1, nil)
    }
    private func refreshHome() {
        if let id = nav.entries(.purpose).first?.id { owners.purposeHome(id).refresh() }
    }

    /// The first candidate below the sheet's top band (C7 anchor for refresh and foreground).
    private var firstVisible: String? { anchors.firstVisible(top: sheet.sheetTop + 28) }

    /// C7 list anchor inside the sheet: restore a refreshed window's card under the expanded band (a restore to
    /// the list's first card or to the top keeps the sheet where it is), and `loadNewer` at the list's top edge.
    private func candidateContent(owner: PurposeDetailOwner, detail: PurposeDetail) -> some View {
        let state = owner.state
        return ScrollViewReader { proxy in
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s12) {
                Color.clear.frame(height: 0)
                    .onGeometryChange(for: Bool.self) { [sheet] in $0.frame(in: .global).minY >= sheet.sheetTop - 1 } action: { atTop = $0 }
                candidateList(owner: owner, detail: detail)
            }
            .modifier(ListAnchorWiring(box: anchors, restore: state.restore, laidIds: owner.plan.laidIds,
                                       firstItemId: state.items.first?.id, prepending: state.prepending) { target in
                guard case .item(let id) = target, id != owner.state.items.first?.id else { return }
                ListAnchorMath.scroll(proxy, to: id, box: anchors)
            })
            .onChange(of: atTop && state.hasNewer && !state.prepending && !state.itemsLoading) { _, wants in
                guard wants else { return }
                anchors.beginPrepend(top: sheet.sheetTop + 28, firstItemId: owner.state.items.first?.id)
                owner.loadNewer()
            }
        }
    }

    @ViewBuilder private func candidateList(owner: PurposeDetailOwner, detail: PurposeDetail) -> some View {
        HStack {
            WLText(HomeText("purpose.candidate.count", detail.candidateCount).resolve(), .button).accessibilityAddTraits(.isHeader)
            Spacer()
            Button { owner.refresh() } label: { WLIcon(.reload, size: 18).frame(width: 44, height: 28) }
                .buttonStyle(.plain).accessibilityLabel(String(localized: "purpose.refresh"))
        }.padding(.horizontal, 4)
        if owner.state.error != nil || (owner.state.itemsError != nil && !owner.state.items.isEmpty) {
            WLText(String(localized: "purpose.refresh.failed"), .label, color: c.textSecondary)
            Button(String(localized: "purpose.retry")) { owner.retry() }
        }
        if let error = owner.state.itemsError, owner.state.items.isEmpty {
            DetailStatusBlock(message: DetailLine(DetailText.errorKey(error)).resolve(), buttonText: String(localized: "purpose.retry")) { owner.retry() }.padding(.top, 60)
        } else if owner.state.items.isEmpty && !owner.state.itemsLoading {
            EmptyState(title: String(localized: "purpose.candidates.empty.title"), description: String(localized: "purpose.candidates.empty.body")) { WLIcon(.plus) }.padding(.top, 100)
        } else {
            PurposeMasonry(owner: owner, anchors: anchors)
        }
        if owner.state.appendFailed {
            Button(String(localized: "purpose.more.failed")) { owner.retry() }.padding(.vertical, 20)
        }
    }
}
