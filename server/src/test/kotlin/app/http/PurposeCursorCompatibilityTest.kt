package app.http

import app.purpose.PurposeCursorPosition
import app.purpose.PurposeProjection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class PurposeCursorCompatibilityTest {
    @Test fun legacy_purpose_cursor_round_trips_before_1970_and_after_9999() {
        val owner=UUID.randomUUID();val id=UUID.randomUUID()
        for (time in listOf(Instant.parse("1969-12-31T23:59:59.999999Z"),Instant.parse("+10000-01-01T00:00:00Z"),
            Instant.ofEpochSecond(-210_866_803_200L),Instant.ofEpochSecond(9_000_000_000_000L))) {
            val token=PurposeCursorCodec.encode(owner,PurposeProjection.SUMMARY,PurposeCursorPosition(time,id))
            val fields=String(Base64.getUrlDecoder().decode(token),Charsets.UTF_8).split('|')
            assertEquals(id.toString(),fields[4]);assertEquals(time.epochSecond*1_000_000L+time.nano/1000L,fields[3].toLong())
            assertEquals(PurposeCursorPosition(time,id),PurposeCursorCodec.decode(owner,PurposeProjection.SUMMARY,token))
        }
    }
    @Test fun postgres_out_of_range_position_is_rejected_before_binding_a_database_parameter() {
        val owner=UUID.randomUUID();val id=UUID.randomUUID()
        val valid=PurposeCursorCodec.encode(owner,PurposeProjection.SUMMARY,PurposeCursorPosition(Instant.EPOCH,id))
        val fields=String(Base64.getUrlDecoder().decode(valid),Charsets.UTF_8).split('|').toMutableList()
        fields[3]="-9000000000000000000"
        val invalid=Base64.getUrlEncoder().withoutPadding().encodeToString(fields.joinToString("|").toByteArray())
        assertNull(PurposeCursorCodec.decode(owner,PurposeProjection.SUMMARY,invalid))
        assertFailsWith<IllegalArgumentException> {
            PurposeCursorCodec.encode(owner,PurposeProjection.SUMMARY,
                PurposeCursorPosition(Instant.ofEpochSecond(-9_000_000_000_000L),id))
        }
    }

}
