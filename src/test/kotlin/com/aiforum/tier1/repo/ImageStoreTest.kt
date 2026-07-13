package com.aiforum.tier1.repo

import com.aiforum.images.ImageStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Tier-1: ImageStore against a real temp dir (no Spring) — nothing is faked above it; this IS the IO
 * boundary, the same way the repository tests are for SQLite. Pins the security-relevant behaviour — type
 * is decided by magic bytes (not the caller), non-images are rejected, oversize is rejected, and identical
 * bytes dedup to one content-addressed file.
 */
@Tag("tier1")
class ImageStoreTest {

    @TempDir
    lateinit var dir: Path

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private fun store(maxBytes: Long = 10_000_000) = ImageStore(dir.toString(), maxBytes)

    @Test
    fun `stores a png content-addressed and on disk`() {
        val stored = store().store(png)
        assertEquals("image/png", stored.mimeType)
        assertTrue(stored.storagePath.endsWith(".png"))
        assertTrue(stored.storagePath.startsWith(stored.sha256.substring(0, 2)))
        assertTrue(Files.exists(dir.resolve(stored.storagePath)), "the blob must be written to disk")
    }

    @Test
    fun `identical bytes dedup to the same path`() {
        val a = store().store(png)
        val b = store().store(png)
        assertEquals(a.sha256, b.sha256)
        assertEquals(a.storagePath, b.storagePath)
    }

    @Test
    fun `rejects a non-image by magic bytes regardless of how it is labelled`() {
        assertThrows(ImageStore.RejectedException::class.java) {
            store().store("not an image at all".toByteArray())
        }
    }

    @Test
    fun `rejects an oversize upload`() {
        assertThrows(ImageStore.RejectedException::class.java) {
            store(maxBytes = 10).store(png)
        }
    }

    @Test
    fun `different images get different content addresses`() {
        val gif = Base64.getDecoder().decode("R0lGODlhAQABAAAAACwAAAAAAQABAAACAkQBADs=")
        assertNotEquals(store().store(png).sha256, store().store(gif).sha256)
    }
}
