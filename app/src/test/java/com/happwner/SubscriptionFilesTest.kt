package com.happwner

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader

class SubscriptionFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun config(index: Int, padding: String = ""): String = """
        {
          "remarks": "server-$index",
          "padding": "$padding",
          "outbounds": [{"protocol":"vless", "settings":{"vnext":[{
            "address":"node-$index.example.invalid", "port":443,
            "users":[{"id":"00000000-0000-4000-8000-000000000001"}]
          }]}}]
        }
    """.trimIndent()

    @Test fun convertsAll142PrettyPrintedJsonConfigs() {
        val source = (0 until 142).joinToString(",\n", "[\n", "\n]") { config(it) }
        val converted = LinkConverter.convert(source, jsonToUri = true, tryBase64 = false)
        assertEquals(142, converted.lines().size)
        for (i in 0 until 142) assertTrue(converted.contains("@node-$i.example.invalid:443?"))
    }

    @Test fun converts500LargeConfigsFromDiskWithoutCollectingTheArray() {
        val input = temporary.newFile("large.json")
        val output = temporary.newFile("converted.txt")
        val padding = "a".repeat(128 * 1024)
        input.bufferedWriter().use { writer ->
            writer.write("[")
            repeat(500) { i ->
                if (i > 0) writer.write(",\n")
                writer.write(config(i, padding))
            }
            writer.write("]")
        }
        assertTrue(input.length() > 64L * 1024 * 1024)
        assertEquals(0, SubscriptionFiles.convertJsonFile(input, output, false))
        val lines = output.readLines()
        assertEquals(500, lines.size)
        for (i in 0 until 500) assertTrue(lines[i].contains("@node-$i.example.invalid:443?"))
        assertTrue(SubscriptionFiles.preview(input).length <= SubscriptionFiles.PREVIEW_CHARS)
    }

    @Test fun readerHandlesEscapesAndConsecutiveMultilineObjects() {
        val objects = mutableListOf<String>()
        JsonSubscriptionStream.forEachObject(StringReader("\uFEFF[{\"a\":\"}\\\"[\",\"b\":[{}]}, {\"c\":2}]"), consume = { objects.add(it) })
        assertEquals(2, objects.size)
        val sequence = mutableListOf<String>()
        JsonSubscriptionStream.forEachObject(StringReader(config(0) + "\n" + config(1)), consume = { sequence.add(it) })
        assertEquals(2, sequence.size)
    }

    @Test fun malformedAndDeepJsonFailBeforeRecursiveParserOrSilentTruncation() {
        for (source in listOf("[{", "[{}", "[{},]", "[{]}", "[{}] junk", "[1]", "{" + "[".repeat(200))) {
            assertThrows(IOException::class.java) { JsonSubscriptionStream.forEachObject(StringReader(source)) {} }
        }
        assertThrows(IOException::class.java) {
            LinkConverter.convert("[".repeat(200) + "]".repeat(200), tryBase64 = false)
        }
    }

    @Test fun boundedCopyPreservesBytesAndRejectsOverflow() {
        val payload = "пример \uD83D\uDE00".repeat(5000).toByteArray()
        val output = ByteArrayOutputStream()
        assertEquals(payload.size.toLong(), SubscriptionFiles.copyCapped(ByteArrayInputStream(payload), output, payload.size.toLong()))
        assertArrayEquals(payload, output.toByteArray())
        assertThrows(IOException::class.java) {
            SubscriptionFiles.copyCapped(ByteArrayInputStream(payload), ByteArrayOutputStream(), payload.size - 1L)
        }
    }

    @Test fun previewNeverSplitsSurrogatePair() {
        val file = temporary.newFile()
        file.writeText("a".repeat(SubscriptionFiles.PREVIEW_CHARS - 1) + "\uD83D\uDE00tail")
        val preview = SubscriptionFiles.preview(file)
        assertEquals(SubscriptionFiles.PREVIEW_CHARS - 1, preview.length)
    }
}
