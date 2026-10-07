package app.wishlist.shared.core

enum class ApiId {
    ITEM_01, ITEM_02, ITEM_03, ITEM_04, ITEM_05, ITEM_06, ITEM_07, ITEM_08,
    HOME_01, HOME_02, DUP_01, DUP_02,
    CAT_01, CAT_02, CAT_03, CAT_04, CAT_05, CAT_06,
    PUR_01, PUR_02, PUR_03, PUR_04, PUR_05, PUR_06, PUR_07, PUR_08,
    ARC_01, ARC_02, ARC_03, ARC_04, ARC_05, ARC_06, ARC_07, ARC_08, ARC_09,
    MEDIA_01, MEDIA_02;

    val wireId: String get() = name.replace('_', '-')
}
