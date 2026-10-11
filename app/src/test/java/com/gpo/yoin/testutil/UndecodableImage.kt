package com.gpo.yoin.testutil

import android.graphics.ImageDecoder
import java.nio.ByteBuffer
import kotlin.random.Random

/** 2048 bytes no decoder reads as an image: the QA fake server's `garbage200` cover, served as image/jpeg. */
fun undecodableImageBytes(): ByteArray = Random(seed = 7).nextBytes(2048)

/**
 * What ImageDecoder throws for [undecodableImageBytes]: Coil's decoder for a disk-cache file on
 * API 29+, and an IOException subclass. Needs Robolectric's native graphics
 * (`@GraphicsMode(GraphicsMode.Mode.NATIVE)`); the legacy shadows decode any bytes.
 */
fun imageDecoderFailure(): ImageDecoder.DecodeException = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(undecodableImageBytes())))
    error("ImageDecoder made an image of bytes that hold none")
} catch (failure: ImageDecoder.DecodeException) {
    failure
}
