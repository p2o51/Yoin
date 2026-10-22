package com.gpo.yoin.data.migration.spotoolfy

/**
 * Words too common in music notes to say anything about the user: pronouns, connectives, "this song", "today",
 * "listen again". Lower case. A word the segmenter returns that is not here can still fail the length rule.
 */
internal object SpotoolfyStopwords {
    private val chinese = """
        一个 一下 一起 一点 一直 一遍 一段 一路 一阵 一样 一些 一种 一次 一首 一天 一切 一定 一般
        这首 那首 这段 这版 这个 那个 这些 那些 这样 那样 这里 那里 这种 这么 那么 这次 那次 那年
        这首歌 那首歌 首歌 歌曲 时候 的时候 那时候 现在 今天 昨天 明天 每天 今晚 晚上 早上 之前 之后 以后 以前
        还是 真的 有点 什么 没有 不是 就是 但是 因为 所以 然后 还有 已经 可以 可能 应该 出来 起来 进来 过来
        我们 他们 你们 她们 它们 自己 大家 别人 人家 咱们
        觉得 感觉 好像 似乎 突然 刚好 刚刚 其实 而且 或者 如果 虽然 不过 只是 只有 还要 也要 都在 都是 也是
        怎么 为什么 比较 特别 非常 十分 真是 太好 太好了 好多 很多 有些 所有 整个 唯一 一整晚 整晚 太早 太会
        第一次 第一 第二 第三 第三遍 第五遍 越听 一响 又听 听完 听到 听了 听过 听着 再听 收藏 记一下 写的 东西 加上
        跟着 收得 收得太早 地方 时间 事情 问题 样子 歌词 音乐
    """.trimIndent()

    private val english = """
        the and for you are was but not this that with have has its it's i'm you're they them their there here
        what when where which who how why all any can could would should will just like really very much more most
        some such than then too also only about into from over after before again still even ever every
        one two song songs track album music listen listening listened today tonight time feel feels feeling
        yeah yes don't didn't can't won't isn't it’s i’m don’t
    """.trimIndent()

    private val japanese = """
        これ それ あれ この その あの ここ そこ あそこ こと もの ため よう など まで から より けど でも
        して した する です ます でした ました ない なかった いる ある なる なった くれる もう まだ また
        とても すごく ちょっと 本当 今日 今夜 時間 曲 この曲 歌詞
    """.trimIndent()

    val all: Set<String> = (chinese + "\n" + english + "\n" + japanese)
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { it.lowercase() }
        .toSet()
}
