package app.purpose

import kotlin.test.*

class PurposeInputPolicyTest {
    @Test fun `style keys and defaults are the pinned v1 resource`() {
        assertEquals(listOf("CORAL", "MUSTARD", "PERIWINKLE", "CYAN", "MINT", "PINK"), PurposeColor.entries.map { it.name })
        assertEquals(listOf("HEART", "HOME", "PLANE", "GIFT", "TENT", "MUSIC", "STAR", "BOOK"), PurposeIcon.entries.map { it.name })
        assertEquals("v1", PurposeStyle.VERSION)
        assertEquals(PurposeColor.CORAL, PurposeStyle.DEFAULT_COLOR)
        assertEquals(PurposeIcon.HEART, PurposeStyle.DEFAULT_ICON)
        assertEquals(PurposeColor.MINT, PurposeStyle.color("MINT"))
        for (key in listOf("mint", "RED", "", " MINT")) assertNull(PurposeStyle.color(key), key)
        assertNull(PurposeStyle.icon("heart"))
    }

    @Test fun `name and description limits use code points and the category character rules`() {
        assertEquals(emptySet(), PurposeInputPolicy.validate("😀".repeat(40), "설".repeat(200)))
        assertEquals(setOf("name"), PurposeInputPolicy.validate("가".repeat(41), null))
        assertEquals(setOf("description"), PurposeInputPolicy.validate("목적", "a".repeat(201)))
        assertEquals(setOf("name"), PurposeInputPolicy.validate("   ", null))
        assertEquals(setOf("name"), PurposeInputPolicy.validate(null, null))
        assertEquals(emptySet(), PurposeInputPolicy.validate("목적", "첫 줄\n둘째\r\n셋째"))
        assertEquals(setOf("description"), PurposeInputPolicy.validate("목적", "단독\r줄"))
        assertEquals(setOf("name"), PurposeInputPolicy.validate("줄\n바꿈", null))
        assertEquals(setOf("name"), PurposeInputPolicy.validate("방향‮", null))
        assertEquals(setOf("name"), PurposeInputPolicy.validate("zero​width", null))
        assertEquals(emptySet(), PurposeInputPolicy.validate("ㅤ", null))
        assertEquals(emptySet(), PurposeInputPolicy.validate("👩‍💻 작업", ""))
    }
}
