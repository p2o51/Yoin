package com.gpo.yoin.ui.settings

/** Yoin's public pages, opened in the browser from Settings › About and Home's account card. */
internal object YoinLinks {
    const val WEBSITE = "https://yoin.gojyuplus.com/"
    const val DOCS = "https://yoin.gojyuplus.com/docs"

    // The website's Simplified Chinese twins (the privacy policy is English only).
    const val WEBSITE_ZH = "https://yoin.gojyuplus.com/zh/"
    const val DOCS_ZH = "https://yoin.gojyuplus.com/zh/docs"

    const val PRIVACY_POLICY = "https://yoin.gojyuplus.com/privacy"

    // The public Notion form that files into "Bug 反馈、收集"; anyone with the link can answer.
    const val FEEDBACK_FORM = "https://conscious-snapper-b16.notion.site/3f5d1115e921807aaa7be5c39223f00f?pvs=105"

    /** The website's home page in the app's language. */
    fun website(language: String): String = if (language == "zh") WEBSITE_ZH else WEBSITE

    /** The user docs in the app's language. */
    fun docs(language: String): String = if (language == "zh") DOCS_ZH else DOCS
}
