package dev.bontaramsonta.poof.data

import dev.bontaramsonta.poof.core.ExistingExit
import dev.bontaramsonta.poof.core.ExitLiveness
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ControlPlaneClientTest {
    private val server = MockWebServer()
    private lateinit var client: ControlPlaneClient

    @Before fun setUp() {
        server.start()
        client = ControlPlaneClient(server.url("/").toString(), { "tok3n" }, backoffMs = listOf(1, 1, 1))
    }

    @After fun tearDown() = server.close()

    private fun respond(code: Int, body: String = "") =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test fun createReturns201() = runTest {
        respond(201, """{"instanceId":"i-0abc","region":"ap-northeast-1","publicIp":"198.51.100.7","serverPublicKey":"c2Vydg=="}""")
        val result = client.createExit("japan", "Y2xpZW50")
        assertEquals(CreateResult.Created("i-0abc", "ap-northeast-1", "198.51.100.7", "c2Vydg=="), result)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/exits", req.url.encodedPath)
        assertEquals("Bearer tok3n", req.headers["Authorization"])
        val body = req.body!!.utf8()
        assertTrue(body.contains("\"country\":\"japan\"") && body.contains("\"clientPublicKey\":\"Y2xpZW50\""))
    }

    @Test fun createReturns409WithExistingExit() = runTest {
        respond(409, """{"country":"britain","region":"eu-west-2","instanceId":"i-0def","publicIp":"203.0.113.9"}""")
        assertEquals(
            CreateResult.Conflict(ExistingExit("britain", "eu-west-2", "i-0def", "203.0.113.9")),
            client.createExit("japan", "Y2xpZW50"),
        )
    }

    @Test fun retries429() = runTest {
        respond(429)
        respond(429)
        respond(200, """{"countries":["india","japan"]}""")
        assertEquals(listOf("india", "japan"), client.countries())
        assertEquals(3, server.requestCount)
    }

    @Test fun gives429UpAfterBackoff() = runTest {
        repeat(4) { respond(429) }
        try {
            client.countries()
            fail("expected ControlPlaneException")
        } catch (e: ControlPlaneException) {
            assertEquals(429, e.status)
        }
    }

    @Test fun unauthorized() = runTest {
        respond(401, """{"error":"unauthorized"}""")
        try {
            client.countries()
            fail("expected UnauthorizedException")
        } catch (_: UnauthorizedException) {
        }
    }

    @Test fun serverErrorCarriesMessage() = runTest {
        respond(502, """{"error":"the Exit got no public IP; it was terminated"}""")
        try {
            client.createExit("india", "Y2xpZW50")
            fail("expected ControlPlaneException")
        } catch (e: ControlPlaneException) {
            assertEquals(502, e.status)
            assertEquals("the Exit got no public IP; it was terminated", e.message)
        }
    }

    // The handshake-timeout ending runs DELETE on the Session's Exit.
    @Test fun deleteExit() = runTest {
        respond(204)
        client.deleteExit("ap-northeast-1", "i-0abc")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/exits/ap-northeast-1/i-0abc", req.url.encodedPath)
        assertEquals("Bearer tok3n", req.headers["Authorization"])
    }

    @Test fun exitLiveness() = runTest {
        respond(200, """{"state":"running"}""")
        respond(200, """{"state":"shutting-down"}""")
        respond(200, """{"state":"terminated"}""")
        assertEquals(ExitLiveness.Alive, client.exitLiveness("ap-south-1", "i-0abc"))
        assertEquals(ExitLiveness.Gone, client.exitLiveness("ap-south-1", "i-0abc"))
        assertEquals(ExitLiveness.Gone, client.exitLiveness("ap-south-1", "i-0abc"))
        assertEquals("/exits/ap-south-1/i-0abc", server.takeRequest().url.encodedPath)
    }
}
