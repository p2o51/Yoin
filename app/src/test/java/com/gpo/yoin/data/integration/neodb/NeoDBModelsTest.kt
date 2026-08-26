package com.gpo.yoin.data.integration.neodb

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 序列化回归：NeoDB 的 MarkInSchema / ReviewInSchema 把 visibility 列为必填，
 * 而 AppContainer 的 neoDbJson 是 encodeDefaults = false —— 字段一旦带默认值、
 * 值又恰好等于默认值，就会整个从 JSON 里消失。visibility=0（公开）正是最常见
 * 取值，曾因此 422：`body.mark.visibility: Field required`。
 *
 * 用与生产完全相同的 Json 配置断言必填字段在场，防止未来有人手滑把默认值
 * 加回去。
 */
class NeoDBModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun should_encode_visibility_when_mark_is_public() {
        val encoded = json.encodeToString(
            ShelfMarkRequest.serializer(),
            ShelfMarkRequest(
                shelfType = "complete",
                visibility = 0,
                ratingGrade = 8,
            ),
        )
        assertTrue(encoded.contains("\"visibility\":0"))
        assertTrue(encoded.contains("\"shelf_type\":\"complete\""))
    }

    @Test
    fun should_encode_visibility_when_review_is_public() {
        val encoded = json.encodeToString(
            ReviewRequest.serializer(),
            ReviewRequest(
                visibility = 0,
                title = "bomb",
                body = "review body",
            ),
        )
        assertTrue(encoded.contains("\"visibility\":0"))
        assertTrue(encoded.contains("\"title\":\"bomb\""))
    }
}
