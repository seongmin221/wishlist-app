import Shared
import SwiftUI

struct PurposeHomeScreen: View {
    @Environment(\.wlEntryOwners) private var owners
    @Environment(\.wlRuntime) private var runtime
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.wlNavigator) private var nav
    @Environment(\.overlayHostState) private var overlay
    @Environment(\.wlColors) private var c
    @Environment(\.scenePhase) private var phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(AccountPresenterOwner.self) private var account
    @State private var expanded: String?

    var body: some View {
        let owner = owners.purposeHome(entryID ?? -1)
        let state = owner.state
        WLTabScrollView(tab: .purpose) {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    WLTopBar(sidePadding: 0) { EmptyView() } center: {
                        WLText(String(localized: "purpose.title"), .display28).accessibilityAddTraits(.isHeader)
                    } trailing: { EmptyView() }
                    WLText(state.signedIn ? HomeText("purpose.active.count", state.activeCount).resolve() : " ", WLTextStyle.label.resized(13), color: c.textSecondary)
                }
                .padding(.horizontal, 20).padding(.bottom, 20)
                if !state.signedIn {
                    VStack(spacing: 20) {
                        EmptyState(title: String(localized: "purpose.signed.out.title"), description: String(localized: "purpose.signed.out.body")) { WLIcon(.heart) }
                        WLButton(String(localized: "purpose.login"), kind: .primary) { nav.push(AppDestination.login.route, sourceKey: "purpose/login") }
                            .frame(maxWidth: 200)
                    }.padding(.top, 140).frame(maxWidth: .infinity)
                } else if let error = state.error, state.purposes.isEmpty {
                    DetailStatusBlock(message: DetailLine(DetailText.errorKey(error)).resolve(), buttonText: String(localized: "purpose.retry")) { owner.retry() }
                        .padding(.top, 140)
                } else if state.loaded && state.purposes.isEmpty {
                    EmptyState(title: String(localized: "purpose.empty.title"), description: String(localized: "purpose.empty.body")) { WLIcon(.heart) }
                        .padding(.vertical, 80)
                    add(owner)
                } else if !state.purposes.isEmpty {
                    VStack(spacing: -22) {
                        ForEach(state.purposes, id: \.id) { purpose in
                            card(purpose, open: expanded == purpose.id) {
                                if expanded == purpose.id {
                                    nav.push(AppDestination.purpose(purpose.id).route, sourceKey: "purpose/card/\(purpose.id)")
                                } else {
                                    withAnimation(reduceMotion ? nil : WishlistTokens.Curve.emphasized.animation(ms: 300)) { expanded = purpose.id }
                                }
                            }
                        }
                    }.padding(.horizontal, 16)
                    add(owner).padding(.top, 20)
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 120)
                }
                if state.signedIn && state.loaded {
                    VStack(alignment: .leading, spacing: 12) {
                        WLText(String(localized: "purpose.archive"), WLTextStyle.label.resized(13), color: c.textSecondary)
                        Button {} label: {
                            HStack(spacing: 12) {
                                Image(systemName: "archivebox")
                                WLText(HomeText("purpose.archive.count", state.archiveCount).resolve(), .button)
                                Spacer()
                                WLIcon(.chevronRight, size: 18)
                            }.padding(20).frame(maxWidth: .infinity).background(c.card, in: RoundedRectangle(cornerRadius: 28))
                        }.buttonStyle(.plain).disabled(true).opacity(0.4)
                    }.padding(.horizontal, 20).padding(.top, 32)
                }
                if state.refreshFailed { WLText(String(localized: "purpose.refresh.failed"), .label, color: c.textSecondary).padding(20) }
                Color.clear.frame(height: 36)
            }
        }
        .refreshable { await owner.refreshAndWait() }
        .task { await runtime?.purposeUntilReady(); if !Task.isCancelled { owner.loadOnce() } }
        .onChange(of: state.purposes.map(\.id), initial: true) { _, ids in
            expanded = PurposePresentation.expanded(previous: expanded, ids: ids)
        }
        .onChange(of: nav.entries(.purpose).last?.id) { _, top in
            if top == entryID && state.loaded { owner.refresh() }
        }
        .onChange(of: nav.currentTab) { _, tab in
            if tab == .purpose && state.loaded { owner.refresh() }
        }
        .onChange(of: phase) { _, phase in owner.scenePhaseChanged(phase) }
        .onChange(of: account.state.account?.accountId) { previous, next in
            if ContentView.shouldDropAccountScoped(previous: previous, next: next), let overlay {
                Task { await overlay.dismissAllWhenSettled() }
            }
        }
    }

    private func add(_ owner: PurposeHomeOwner) -> some View {
        Button {
            guard let overlay else { return }
            showPurposeCreateSheet(overlay: overlay, owners: owners) { detail in
                owner.refresh()
                // Sheet must finish dismissing before navigation's edge recognizer is enabled.
                nav.push(AppDestination.purpose(detail.id).route, sourceKey: "purpose/created/\(detail.id)")
            }
        } label: {
            HStack(spacing: 8) {
                WLIcon(.plus, size: 18)
                WLText(String(localized: "purpose.add"), .button)
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            .overlay(RoundedRectangle(cornerRadius: 28).inset(by: 0.75)
                .stroke(c.textSecondary, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3])))
            .contentShape(RoundedRectangle(cornerRadius: 28))
        }
        .buttonStyle(.plain)
        .disabled(owner.state.activeCount >= owner.state.purposeLimit)
        .opacity(owner.state.activeCount >= owner.state.purposeLimit ? 0.4 : 1)
        .padding(.horizontal, 20)
    }

    private func card(_ purpose: PurposeSummary, open: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 12) {
                    PurposeIconTile(key: purpose.iconKey, size: 36)
                    WLText(purpose.name, .display28, color: WishlistTokens.Purpose.onPurpose, maxLines: 1)
                    Spacer(minLength: 0)
                    WLText("\(purpose.candidateCount)", WLTextStyle.price.resized(26), color: WishlistTokens.Purpose.onPurpose)
                }.padding(EdgeInsets(top: 14, leading: 16, bottom: 30, trailing: 20))
                if open {
                    VStack(alignment: .leading, spacing: 14) {
                        TimelineView(.everyMinute) { context in
                            let relative = DisplayFormat.shared.relative(from: purpose.activity.at, now: DetailClock.instant(context.date), utcOffsetSeconds: DetailClock.utcOffset)
                            let action = String(localized: purpose.activity.kind == "CANDIDATE_ADDED" ? "purpose.activity.added" : "purpose.activity.created")
                            WLText(String(format: String(localized: "purpose.activity"), purpose.candidateCount, HomeRowText.time(relative).resolve(), action), .label, color: WishlistTokens.Purpose.onPurpose, maxLines: 1)
                        }
                        if !purpose.previews.isEmpty {
                            HStack(spacing: 8) {
                                ForEach(purpose.previews.prefix(4), id: \.itemId) { preview in
                                    ProductPhoto(imageUrl: preview.imageUrl)
                                        .aspectRatio(1, contentMode: .fit)
                                        .clipShape(RoundedRectangle(cornerRadius: 14))
                                }
                                ForEach(0..<max(0, 4 - purpose.previews.count), id: \.self) { _ in Color.clear.aspectRatio(1, contentMode: .fit) }
                            }
                        }
                    }.padding(.horizontal, 20).padding(.top, -16).padding(.bottom, 40).transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(PurposeStyle.color(purpose.colorKey), in: RoundedRectangle(cornerRadius: 28))
            .overlay(RoundedRectangle(cornerRadius: 28).strokeBorder(c.background, lineWidth: 2))
            .contentShape(RoundedRectangle(cornerRadius: 28))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint(open ? String(localized: "purpose.open") : String(localized: "purpose.expand"))
    }
}
