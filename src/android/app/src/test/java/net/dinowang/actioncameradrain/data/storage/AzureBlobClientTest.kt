package net.dinowang.actioncameradrain.data.storage

import net.dinowang.actioncameradrain.data.config.UploadAuth
import net.dinowang.actioncameradrain.data.config.UploadConfig
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AzureBlobClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AzureBlobClient

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val cfg = UploadConfig.AzureBlob(
            id = "t", label = "t",
            accountUrl = server.url("/").toString().trimEnd('/'),
            container = "ingest",
            auth = UploadAuth.Sas(sasToken = "?sv=2024-01-01&sig=abc"),
        )
        client = AzureBlobClient(cfg, OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun putBlockSendsCorrectQuery() {
        server.enqueue(MockResponse().setResponseCode(201))
        val blockId = AzureBlobClient.encodeBlockId("block-0000000000")
        client.putBlock("ingest", "gopro/100GOPRO/GH010001.MP4", blockId, "hello".toByteArray())
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        val path = req.path!!
        assertTrue(path, path.startsWith("/ingest/gopro/100GOPRO/GH010001.MP4"))
        assertTrue(path, path.contains("comp=block"))
        assertTrue(path, path.contains("blockid="))
        assertTrue(path, path.contains("sv=2024-01-01"))
        assertTrue(path, path.contains("sig=abc"))
        assertEquals("hello", req.body.readUtf8())
    }

    @Test fun putBlockListSendsXmlWithLatestEntries() {
        server.enqueue(MockResponse().setResponseCode(201))
        val ids = listOf(
            AzureBlobClient.encodeBlockId("block-0"),
            AzureBlobClient.encodeBlockId("block-1"),
        )
        client.putBlockList("ingest", "gopro/file.mp4", ids, contentType = "video/mp4")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.path!!.contains("comp=blocklist"))
        assertEquals("video/mp4", req.getHeader("x-ms-blob-content-type"))
        val xml = req.body.readUtf8()
        assertTrue(xml, xml.contains("<BlockList>"))
        assertEquals(2, "<Latest>".toRegex().findAll(xml).count())
    }

    @Test fun deleteBlobIssuesDeleteWithSas() {
        server.enqueue(MockResponse().setResponseCode(202))
        client.deleteBlob("ingest", "gopro/file.mp4")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.contains("sig=abc"))
    }

    @Test fun deleteBlobTolerates404() {
        server.enqueue(MockResponse().setResponseCode(404))
        client.deleteBlob("ingest", "gopro/missing.mp4")
        assertNotNull(server.takeRequest())
    }

    @Test fun listContainersParsesXml() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """<?xml version="1.0" encoding="utf-8"?>
            <EnumerationResults>
              <Containers>
                <Container><Name>trip-2024-japan</Name><Properties/></Container>
                <Container><Name>trip-2025-iceland</Name><Properties/></Container>
              </Containers>
            </EnumerationResults>""".trimIndent()
        ))
        val names = client.listContainers()
        assertEquals(listOf("trip-2024-japan", "trip-2025-iceland"), names)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.path!!, req.path!!.contains("comp=list"))
        assertTrue(req.path!!, req.path!!.contains("sig=abc"))
    }

    @Test fun createContainerHits201() {
        server.enqueue(MockResponse().setResponseCode(201))
        client.createContainer("trip-new")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        val path = req.path!!
        assertTrue(path, path.startsWith("/trip-new"))
        assertTrue(path, path.contains("restype=container"))
        assertTrue(path, path.contains("sig=abc"))
    }

    @Test fun createContainerTolerates409() {
        server.enqueue(MockResponse().setResponseCode(409))
        client.createContainer("trip-existing")
        assertNotNull(server.takeRequest())
    }
}
