package com.gpo.yoin.ui.memories.copy

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MemorySentencesTest {

    @Test
    fun should_not_split_decimal_score_when_period_precedes_digit() {
        assertEquals(
            listOf("给 8.5，扣掉的那一点留给第十首，它不该那么短。"),
            MemorySentences.split("给 8.5，扣掉的那一点留给第十首，它不该那么短。"),
        )
        assertEquals(listOf("Was it 9.5?", "Yes."), MemorySentences.split("Was it 9.5? Yes."))
        // a full stop at the very end, or before a letter, still ends the sentence
        assertEquals(listOf("Version 2.0 is out.", "Really"), MemorySentences.split("Version 2.0 is out. Really"))
    }

    @Test
    fun should_keep_closing_quote_with_sentence() {
        assertEquals(listOf("她说：“冬天很长。”", "我没回答。"), MemorySentences.split("她说：“冬天很长。”我没回答。"))
        assertEquals(listOf("「好听。」", "就这样。"), MemorySentences.split("「好听。」就这样。"))
        assertEquals(listOf("（括号里的一句。）", "然后呢？"), MemorySentences.split("（括号里的一句。）然后呢？"))
        assertEquals(
            listOf("He said “stay.”", "Then it ended!"),
            MemorySentences.split("He said “stay.” Then it ended!"),
        )
    }

    @Test
    fun should_space_english_sentences_after_closing_quote_when_joining() {
        // deviation from the prototype's joinS, which glued these together
        assertEquals(
            "He said “stay.” Then it ended!",
            MemorySentences.join(listOf("He said “stay.”", "Then it ended!")),
        )
        assertEquals("她说：“冬天很长。”我没回答。", MemorySentences.join(listOf("她说：“冬天很长。”", "我没回答。")))
        assertEquals("Was it 9.5? Yes.", MemorySentences.join(listOf("Was it 9.5?", "Yes.")))
    }

    @Test
    fun should_match_prototype_sentences_and_weights() {
        val golden = CopyGolden.load("copy-sentences.json")
        golden.list("samples").forEach { element ->
            val sample = element.jsonObject
            val text = sample.text("text")
            val sentences = MemorySentences.split(text)
            assertEquals(text, sample.list("sentences").map { it.jsonPrimitive.content }, sentences)
            // the one deliberate change: an English sentence ending in a closing quote keeps its space
            val expectedJoin = sample.text("joined2").replace("“stay.”Then", "“stay.” Then")
            assertEquals(text, expectedJoin, MemorySentences.join(sentences.take(2)))
            assertEquals(
                text,
                sample.getValue("wlen").jsonPrimitive.content.toDouble(),
                MemorySentences.weightedLength(text),
                1e-9,
            )
        }
        golden.list("wlen").forEach { element ->
            val sample = element.jsonObject
            assertEquals(
                sample.text("text"),
                sample.getValue("wlen").jsonPrimitive.content.toDouble(),
                MemorySentences.weightedLength(sample.text("text")),
                1e-9,
            )
        }
    }
}
