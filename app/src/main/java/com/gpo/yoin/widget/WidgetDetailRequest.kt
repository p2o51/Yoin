package com.gpo.yoin.widget

import com.gpo.yoin.data.model.MediaId

/** A widget destination held by MainActivity until its shell can route it. */
internal data class WidgetDetailRequest(val entity: WidgetEntity, val id: MediaId) {
    companion object {
        fun parse(entity: String?, id: String?): WidgetDetailRequest? {
            val kind = entity?.let { runCatching { WidgetEntity.valueOf(it) }.getOrNull() } ?: return null
            val mediaId = MediaId.parseOrNull(id) ?: return null
            return WidgetDetailRequest(kind, mediaId)
        }
    }
}
