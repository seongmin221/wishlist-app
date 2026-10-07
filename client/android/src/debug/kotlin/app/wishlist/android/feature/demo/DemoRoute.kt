package app.wishlist.android.feature.demo

import app.wishlist.android.navigation.WLPushStyle
import app.wishlist.android.navigation.WLRoute

/**
 * 데모 상세 경로. 종류마다 화면 정책(탭 바·이동 방식)이 다르다(보드 기준).
 * - 상품 상세(FProductDetailL): 사진 이동, 탭 바 숨김.
 * - 세부 유형 목록(FCategoryListL): 가로 밀기, 탭 바 보임.
 * - 목적 상세(FPurposeDetailL): 가로 밀기, 탭 바 보임.
 */
internal sealed interface DemoRoute : WLRoute {
    /** `itemId`는 `DemoContent.items`의 카드 id(사진 모양까지 같은 카드여야 사진 이동이 이어진다). */
    data class Product(val itemId: String) : DemoRoute {
        override val showsTabBar = false
        override val pushStyle = WLPushStyle.Photo
    }

    /** 상위 카테고리 이름 + 세부 유형 이름. */
    data class CategoryList(val top: String, val type: String) : DemoRoute {
        override val showsTabBar = true
        override val pushStyle = WLPushStyle.Slide
    }

    data class Purpose(val purposeId: String) : DemoRoute {
        override val showsTabBar = true
        override val pushStyle = WLPushStyle.Slide
    }
}
