package com.dailyapp.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.dailyapp.core.AiPlan

/** 一次 AI 呼叫的結果。失敗分成「下一輪重試就好」與「需要使用者處理」兩類。 */
sealed interface AiCall {
    data class Ok(val json: String, val inputTokens: Long, val outputTokens: Long) : AiCall
    /** 網路、限流、伺服器忙：訊息留在佇列，下一輪重送。 */
    data class Retry(val reason: String) : AiCall
    /** API key 錯、權限、請求格式、AI 拒答：重送也不會好。 */
    data class Fail(val reason: String, val inputTokens: Long = 0, val outputTokens: Long = 0) : AiCall
}

/**
 * 直接從手機呼叫 Anthropic Messages API（不經中繼伺服器，spec 4.2）。
 * 一律使用結構化輸出（JSON schema），回傳的文字保證可以解析。
 */
class AiClient(apiKey: String) : AutoCloseable {
    private val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    fun call(system: String, input: String, schema: Map<String, Any>, maxTokens: Long = 16000L): AiCall {
        val params = buildParams(system, input, schema, maxTokens)
        return try {
            val r = client.messages().create(params)
            val inT = r.usage().inputTokens()
            val outT = r.usage().outputTokens()
            when (r.stopReason().orElse(null)) {
                StopReason.REFUSAL -> AiCall.Fail("AI 拒絕處理這批訊息", inT, outT)
                StopReason.MAX_TOKENS -> AiCall.Fail("回應過長被截斷", inT, outT)
                else -> {
                    val text = r.content().flatMap { b -> b.text().map { listOf(it.text()) }.orElse(emptyList()) }
                        .joinToString("")
                    if (text.isBlank()) AiCall.Fail("AI 沒有回傳內容", inT, outT) else AiCall.Ok(text, inT, outT)
                }
            }
        } catch (e: UnauthorizedException) {
            AiCall.Fail("API key 無效")
        } catch (e: PermissionDeniedException) {
            AiCall.Fail("API key 沒有權限使用 ${AiPlan.MODEL}")
        } catch (e: BadRequestException) {
            AiCall.Fail("請求被拒：" + (e.message ?: "400"))
        } catch (e: RateLimitException) {
            AiCall.Retry("請求太頻繁或額度不足")
        } catch (e: InternalServerException) {
            AiCall.Retry("Anthropic 伺服器忙碌")
        } catch (e: AnthropicServiceException) {
            if (e.statusCode() >= 500) AiCall.Retry("Anthropic 伺服器錯誤 ${e.statusCode()}")
            else AiCall.Fail("API 錯誤 ${e.statusCode()}")
        } catch (e: AnthropicIoException) {
            AiCall.Retry("網路連線失敗")
        }
    }

    override fun close() = client.close()

    companion object {
        fun buildParams(system: String, input: String, schema: Map<String, Any>, maxTokens: Long): MessageCreateParams =
            MessageCreateParams.builder()
                .model(AiPlan.MODEL)
                .maxTokens(maxTokens)
                .system(system)
                .outputConfig(
                    OutputConfig.builder()
                        // 分類與摘要用低強度即可，省時間也省錢
                        .effort(OutputConfig.Effort.LOW)
                        .format(JsonOutputFormat.builder().schema(schemaOf(schema)).build())
                        .build()
                )
                .addUserMessage(input)
                .build()

        private fun schemaOf(map: Map<String, Any>): JsonOutputFormat.Schema =
            JsonOutputFormat.Schema.builder().apply {
                map.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
            }.build()
    }
}
