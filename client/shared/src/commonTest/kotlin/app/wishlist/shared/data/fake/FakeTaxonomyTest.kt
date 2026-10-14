package app.wishlist.shared.data.fake

import kotlin.test.Test
import kotlin.test.assertEquals

/** The hand-copied V11 taxonomy keeps the server's IDs, counts and in-group order. */
class FakeTaxonomyTest {
    @Test fun countsAndIdSequence() {
        assertEquals((1..11).map { "G" + it.toString().padStart(3, '0') }, FakeTaxonomy.groups.map { it.id })
        assertEquals((1..87).map { "C" + it.toString().padStart(3, '0') }, FakeTaxonomy.leaves.map { it.id })
        assertEquals((0..10).toList(), FakeTaxonomy.groups.map { it.order })
        FakeTaxonomy.groups.forEach { group ->
            assertEquals(FakeTaxonomy.leavesOf(group.id).indices.toList(), FakeTaxonomy.leavesOf(group.id).map { it.order }, group.id)
        }
        assertEquals("헤드폰", FakeTaxonomy.leaf("C026")?.name)
        assertEquals(15, FakeTaxonomy.leavesOf("G003").size)
    }
}
