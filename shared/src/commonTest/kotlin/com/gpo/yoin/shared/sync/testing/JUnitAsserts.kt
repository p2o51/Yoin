package com.gpo.yoin.shared.sync.testing

/*
 * JUnit 4 assertion signatures (message first) over kotlin.test, so Android's
 * sync tests port to commonTest line for line.
 */

fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)

fun assertEquals(message: String?, expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual, message)

fun assertNotEquals(expected: Any?, actual: Any?) = kotlin.test.assertNotEquals(expected, actual)

fun assertNotEquals(message: String?, expected: Any?, actual: Any?) =
    kotlin.test.assertNotEquals(expected, actual, message)

fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)

fun assertTrue(message: String?, condition: Boolean) = kotlin.test.assertTrue(condition, message)

fun assertFalse(condition: Boolean) = kotlin.test.assertFalse(condition)

fun assertFalse(message: String?, condition: Boolean) = kotlin.test.assertFalse(condition, message)

fun assertNull(actual: Any?) = kotlin.test.assertNull(actual)

fun assertNull(message: String?, actual: Any?) = kotlin.test.assertNull(actual, message)

fun assertNotNull(actual: Any?) {
    kotlin.test.assertNotNull(actual)
}

fun assertNotNull(message: String?, actual: Any?) {
    kotlin.test.assertNotNull(actual, message)
}

fun assertArrayEquals(expected: ByteArray, actual: ByteArray) = kotlin.test.assertContentEquals(expected, actual)

fun fail(message: String? = null): Nothing = kotlin.test.fail(message)

/** Stand-in for java.io.IOException in ported tests. */
class IOException(message: String) : Exception(message)
