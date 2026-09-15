package com.example.hjp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hjp.ocr.AndroidOcr
import com.example.hjp.ocr.OcrImageLoader
import com.example.hjp.referenceocr.OcrPipeline as ReferenceOcr
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrUpstreamParityInstrumentedTest {
    @Test fun originalAndIntegratedAgreeOnActualCaptureAndExplicitRotation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("ocr_photo_path")) {
            "Actual capture path required; missing evidence must not skip/pass"
        }
        val photo = File(path)
        assertTrue("Actual photo must exist", photo.isFile)
        val uri = Uri.fromFile(photo)
        val decoded = OcrImageLoader.load(context, uri)
        // Independent copy of original MainActivity.loadBitmap, including its supported EXIF rotations.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            })!!
        }
        val orientation = context.contentResolver.openInputStream(uri)!!.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val degrees = when (orientation) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        val upstream = if (degrees == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
            Matrix().apply { postRotate(degrees) }, true)
        try {
            assertTrue("Decoded preview differs from original", decoded.sameAs(upstream))
            val ocr = requireNotNull(AndroidOcr.createOrNull(context)) { "All required OCR models must load" }
            ocr.use { integrated ->
                ReferenceOcr(context).use { reference ->
                    for (turns in 0..1) {
                        val bitmap = OcrImageLoader.rotateClockwise(decoded, turns)
                        try {
                            val expected = reference.run(bitmap)
                            val actual = integrated.read(bitmap).regions
                            assertTrue("No text detected in actual capture", expected.isNotEmpty())
                            assertEquals("Region count (rotation=$turns)", expected.size, actual.size)
                            expected.zip(actual).forEachIndexed { index, (e, a) ->
                                assertEquals("Text $index (rotation=$turns)", e.text, a.text)
                                assertEquals(e.score.toDouble(), a.score.toDouble(), 0.00001)
                                e.poly.zip(a.poly).forEach { (p, q) ->
                                    assertEquals(p.x.toDouble(), q.x.toDouble(), 0.001)
                                    assertEquals(p.y.toDouble(), q.y.toDouble(), 0.001)
                                }
                            }
                            android.util.Log.i("HjpOcrParity", "rotation=$turns regions=${expected.size} pixels/text/boxes/scores=matched")
                        } finally { if (bitmap !== decoded) bitmap.recycle() }
                    }
                }
            }
        } finally {
            decoded.recycle()
            if (upstream !== raw) upstream.recycle()
            raw.recycle()
        }
    }

    @Test fun exifAndExplicitRotationPreservePixelGeometry() {
        val original = Bitmap.createBitmap(intArrayOf(1, 2, 3, 4, 5, 6).map { it or 0xff000000.toInt() }.toIntArray(), 3, 2, Bitmap.Config.ARGB_8888)
        try {
            assertSame(original, OcrImageLoader.applyExif(original, 1))
            val rotated = OcrImageLoader.applyExif(original, 6)
            try {
                assertEquals(2, rotated.width); assertEquals(3, rotated.height)
                assertEquals(original.getPixel(0, 1), rotated.getPixel(0, 0))
                val restored = OcrImageLoader.rotateClockwise(rotated, 3)
                try { assertTrue(original.sameAs(restored)) } finally { restored.recycle() }
            } finally { rotated.recycle() }
        } finally { original.recycle() }
    }
}
