package app.wishlist.shared.data.fake

import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.domain.evaluateItem
import app.wishlist.shared.model.*
import kotlin.time.Duration.Companion.days

/** Image-comparison fixture only. Repository counts are derived from stored membership. */
data class BoardDisplayMetadata(
    val categoryChipCounts: Map<String, Int>,
    val purposeCandidateCounts: Map<String, Int>,
    val cards: List<BoardCardFixture> = emptyList(),
)

/** Board-specific art stays outside the WishlistItem snapshot and UI theme tokens. */
data class BoardCardFixture(
    val cardKey: String,
    val itemId: String,
    val faceHex: String,
    val lineHex: String,
    val cupHex: String,
    val heightToWidthRatio: Double,
    val meta: String,
)

data class BoardSeedData(
    val categories: List<Category>,
    val purposes: List<Purpose>,
    val items: List<WishlistItem>,
    val displayMetadata: BoardDisplayMetadata,
    val carrierId: String,
    val commuteId: String,
)

/** Board snapshots, not a full SELECT taxonomy or a wire catalog implementation. */
object BoardSeeds {
    fun create(clock: Clock, ids: IdGenerator): BoardSeedData {
        val now = clock.now()
        val categories = mutableListOf<Category>()
        val categoryCounts = linkedMapOf<String, Int>()
        fun group(id: String, name: String) { categories += Category(id, name) }
        fun leaf(id: String, name: String, parentId: String, count: Int, custom: Boolean = false) {
            categories += Category(id, name, parentId, if (custom) "CUSTOM" else "PUBLIC", if (custom) 1 else null)
            categoryCounts[id] = count
        }
        group("G001", "패션·잡화")
        leaf("C006", "신발", "G001", 5)
        leaf("C001", "아우터", "G001", 3)
        leaf("C007", "가방", "G001", 3)
        leaf("C002", "상의", "G001", 2)
        leaf("C011", "패션 소품", "G001", 2)
        group("G002", "뷰티·퍼스널케어")
        leaf("C012", "스킨케어", "G002", 2)
        leaf("C014", "향수", "G002", 2)
        group("G003", "디지털·IT")
        leaf("C026", "헤드폰", "G003", 8)
        leaf("C024", "키보드", "G003", 3)
        leaf("C029", "카메라·액션캠", "G003", 3)
        leaf("C023", "모니터", "G003", 2)
        leaf("C025", "마우스·트랙패드", "G003", 2)
        leaf("C030", "웨어러블 기기", "G003", 2)
        leaf("C027", "이어폰", "G003", 1)
        leaf("C020", "태블릿", "G003", 1)
        leaf(ids.newId(), "오디오 케이블·DAC", "G003", 2, custom = true)
        group("G004", "가구·인테리어")
        leaf("C039", "조명", "G004", 3)
        leaf("C034", "의자", "G004", 2)
        leaf("C035", "책상·테이블", "G004", 2)
        group("G005", "생활·주방·가전")
        leaf("C043", "주방 가전", "G005", 3)
        leaf("C050", "공기·온습도 관리", "G005", 2)
        leaf("C044", "조리 도구", "G005", 1)
        group("G006", "스포츠·아웃도어·여행")
        leaf("C056", "캠핑 용품", "G006", 4)
        leaf("C053", "러닝 용품", "G006", 2)
        leaf("C059", "여행 가방·캐리어", "G006", 2)
        leaf(ids.newId(), "백패킹 소품", "G006", 1, custom = true)
        group("G007", "취미·문화·컬렉터블")
        leaf("C068", "피규어·컬렉터블", "G007", 2)
        leaf("C066", "보드게임·퍼즐", "G007", 1)
        leaf(ids.newId(), "레고", "G007", 0, custom = true)
        group("G011", "건강·웰빙")
        leaf("C086", "수면·회복 용품", "G011", 1)

        val purposes = listOf(
            Purpose(ids.newId(), "출퇴근 헤드폰", "지하철에서 쓸 노이즈 캔슬링 헤드폰", "coral", "music", 1, now, now),
            Purpose(ids.newId(), "가을 트레일 러닝", null, "mustard", "star", 1, now, now),
            Purpose(ids.newId(), "홈오피스 의자", null, "periwinkle", "book", 1, now, now),
            Purpose(ids.newId(), "캠핑 첫 장비", null, "cyan", "tent", 1, now, now),
            Purpose(ids.newId(), "거실 조명 바꾸기", null, "mint", "home", 1, now, now),
            Purpose(ids.newId(), "엄마 생신 선물", null, "pink", "gift", 1, now, now),
            Purpose(ids.newId(), "여행 캐리어", "다음 달 출장 때 쓸 기내용 캐리어", "mustard", "plane", 1, now, now),
        )
        val commuteId = purposes.first().id
        val headphones = categories.single { it.id == "C026" }
        fun item(key: String, brand: String, model: String, price: String, merchant: String,
                 daysSinceCheck: Int, commute: Boolean = false, pending: Boolean = false): WishlistItem {
            val snapshot = WishlistItem(
                id = ids.newId(), clientSubmissionId = ids.newId(), version = 1,
                sourceUrl = "https://seed.wishlist.invalid/headphones/$key",
                product = ProductSnapshot(
                    name = "$brand $model", price = requireNotNull(DecimalAmount.parseOrNull(price)),
                    currency = "KRW", brand = brand, merchant = merchant,
                    metadataCheckedAt = now - daysSinceCheck.days,
                    nameSource = ValueSource.AI,
                ),
                category = ItemCategory(headphones.id, ValueSource.AI, name = headphones.name,
                    parentId = headphones.parentId, kind = headphones.kind),
                purpose = if (commute) ItemPurpose(commuteId, ValueSource.USER) else ItemPurpose(),
                analysis = ItemAnalysis(AnalysisStatus.READY),
                reviewStatus = if (pending) ReviewStatus.PENDING else ReviewStatus.CONFIRMED,
                lifecycleStatus = LifecycleStatus.ACTIVE, requiredAction = RequiredAction.NONE,
                createdAt = now, updatedAt = now,
            )
            val policy = evaluateItem(snapshot)
            return snapshot.copy(requiredAction = policy.requiredAction, allowedActions = policy.allowedActions)
        }
        val items = listOf(
            item("l1", "소니", "WH-1000XM6", "549000", "무신사", 2, commute = true),
            item("l2", "보스", "QuietComfort Ultra", "499000", "보스 공식몰", 2, commute = true),
            item("l3", "젠하이저", "MOMENTUM 4", "389000", "젠하이저", 5),
            item("l4", "애플", "AirPods Max", "769000", "애플", 7, commute = true),
            item("l5", "마샬", "MAJOR V", "229000", "29CM", 7, pending = true),
            item("l6", "뱅앤올룹슨", "Beoplay H95", "1190000", "뱅앤올룹슨", 14),
            item("l7", "소니", "ULT WEAR", "279000", "11번가", 14, commute = true),
            item("l8", "오디오테크니카", "ATH-M50x", "219000", "오디오테크니카", 21),
        )
        val cards = listOf(
            BoardCardFixture("l1", items[0].id, "#FFFFFF", "#9A9A96", "#D8D8D4", 1.0 / 1.0, "출퇴근 헤드폰 · 2일 전 확인"),
            BoardCardFixture("l2", items[1].id, "#7A6B5B", "#E9DFD2", "#A99683", 5.0 / 4.0, "출퇴근 헤드폰 · 2일 전 확인"),
            BoardCardFixture("l3", items[2].id, "#E9E8E4", "#7C7B77", "#BDBCB7", 4.0 / 3.0, "목적 미지정 · 5일 전 확인"),
            BoardCardFixture("l4", items[3].id, "#FFFFFF", "#9A9A96", "#D8D8D4", 1.0 / 1.0, "출퇴근 헤드폰 · 1주 전 확인"),
            BoardCardFixture("l5", items[4].id, "#3F4B44", "#C9D3CB", "#7F8E84", 5.0 / 4.0, "목적 미지정 · 1주 전 확인"),
            BoardCardFixture("l6", items[5].id, "#E9E8E4", "#7C7B77", "#BDBCB7", 4.0 / 3.0, "목적 미지정 · 2주 전 확인"),
            BoardCardFixture("l7", items[6].id, "#FFFFFF", "#9A9A96", "#D8D8D4", 1.0 / 1.0, "출퇴근 헤드폰 · 2주 전 확인"),
            BoardCardFixture("l8", items[7].id, "#7A6B5B", "#E9DFD2", "#A99683", 5.0 / 4.0, "목적 미지정 · 3주 전 확인"),
            BoardCardFixture("c1", items[0].id, "#FFFFFF", "#9A9A96", "#D8D8D4", 1.0 / 1.0, "무신사 · 2일 전 확인"),
            BoardCardFixture("c2", items[1].id, "#7A6B5B", "#E9DFD2", "#A99683", 5.0 / 4.0, "보스 공식몰 · 2일 전 확인"),
            BoardCardFixture("c3", items[3].id, "#E9E8E4", "#7C7B77", "#BDBCB7", 4.0 / 3.0, "애플 · 1주 전 확인"),
            BoardCardFixture("c4", items[6].id, "#FFFFFF", "#9A9A96", "#D8D8D4", 1.0 / 1.0, "11번가 · 2주 전 확인"),
            BoardCardFixture("c5", items[4].id, "#3F4B44", "#C9D3CB", "#7F8E84", 5.0 / 4.0, "29CM · 1주 전 확인"),
        )
        return BoardSeedData(
            categories, purposes, items,
            BoardDisplayMetadata(categoryCounts, purposes.mapIndexed { index, purpose ->
                purpose.id to listOf(5, 3, 2, 4, 3, 2, 0)[index]
            }.toMap(), cards),
            carrierId = purposes.last().id, commuteId = commuteId,
        )
    }
}
