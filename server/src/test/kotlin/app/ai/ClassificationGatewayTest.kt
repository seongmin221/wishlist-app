package app.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClassificationGatewayTest {
    private val candidates = CandidateSnapshot(setOf("CAT_HOME"), setOf("PUR_GIFT"))

    @Test fun `unknown purpose id is unusable`() {
        val result = ClassificationSchema.validate("""{"category_status":"ASSIGNED","category_id":"CAT_HOME","purpose_status":"ASSIGNED","purpose_id":"PUR_UNKNOWN"}""", candidates)
        assertIs<ClassificationResult.Unusable>(result)
    }

    @Test fun `valid assigned ids are accepted`() {
        val result = ClassificationSchema.validate("""{"category_status":"ASSIGNED","category_id":"CAT_HOME","purpose_status":"ASSIGNED","purpose_id":"PUR_GIFT"}""", candidates)
        assertEquals(ClassificationResult.Assigned("CAT_HOME", "PUR_GIFT"), result)
    }

    @Test fun `abstention has no ids`() {
        val result = ClassificationSchema.validate("""{"category_status":"ABSTAINED","category_id":null,"purpose_status":"UNASSIGNED","purpose_id":null}""", candidates)
        assertEquals(ClassificationResult.Abstained, result)
    }

    @Test fun `alias is not accepted as production snapshot`() {
        assertFailsWith<IllegalArgumentException> { OpenAiConfig("gpt-5.6-luna", "secret") }
    }

    @Test fun `request fixes token limits and store false`() {
        val request = OpenAiResponsesGateway(OpenAiConfig("gpt-5.6-luna-2026-09-01", "secret"))
            .requestBody("Lamp", candidates)
        assertEquals(80, request["max_output_tokens"]?.toString()?.toInt())
        assertEquals("false", request["store"].toString())
        assertTrue(request.toString().toByteArray(Charsets.UTF_8).size <= 900)
    }
}
