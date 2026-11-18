package com.dailyapp.ai

import com.anthropic.core.jsonMapper
import com.dailyapp.core.AiPlan
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 不連網：只檢查送出的請求內容長得對。 */
class AiClientTest {
    @Test fun requestBodyHasModelSchemaAndEffort() {
        val params = AiClient.buildParams(AiPlan.SYSTEM, "{}", AiPlan.SCHEMA, 16000L)
        val body = ObjectMapper().readTree(jsonMapper().writeValueAsString(params._body()))
        assertEquals("claude-haiku-5-5", body["model"].asText())
        assertEquals("low", body["output_config"]["effort"].asText())
        val format = body["output_config"]["format"]
        assertEquals("json_schema", format["type"].asText())
        assertEquals("object", format["schema"]["type"].asText())
        assertFalse(format["schema"]["additionalProperties"].asBoolean())
        assertEquals("user", body["messages"][0]["role"].asText())
    }
}
