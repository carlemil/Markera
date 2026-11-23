package markera.server

import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Issue #42: Apple's domain check reads the file from a configured path, not from an uncommitted resource. */
class AppleDomainAssociationTest {

    private val route = "/.well-known/apple-developer-domain-association.txt"

    private fun domainTest(configure: (Config) -> Config, block: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            val dbFile = File.createTempFile("markera-domain-test", ".db").also { it.delete(); it.deleteOnExit() }
            application { markeraModule(configure(Config(0, dbFile.path, null, null, false)), Db(dbFile.path)) }
            block(client)
        }

    @Test
    fun theDefaultPathSitsBesideTheDatabase() {
        val config = Config(0, "/data/markera.db", null, null, false)
        assertEquals(
            File("/data/apple-developer-domain-association.txt").absoluteFile.path,
            File(config.appleDomainAssociationFile).absoluteFile.path,
        )
    }

    @Test
    fun theConfiguredFileIsServedExactlyAsPlainText() {
        val blob = File.createTempFile("apple-domain", ".txt").also { it.deleteOnExit() }
        val content = "MIIQ+AYJKoZIhvcNAQcCoIIQ6TCCEOUCAQExCzAJBgUrDgMCGgUA\n{\"team\":\"ABCDE12345\"}\n"
        blob.writeText(content)
        domainTest({ it.copy(appleDomainAssociationFile = blob.path) }) { client ->
            val response = client.get(route)
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Text.Plain), response.contentType().toString())
            assertEquals(content, response.bodyAsText())
        }
    }

    @Test
    fun aMissingFileIsACleanNotFound() {
        val absent = File.createTempFile("apple-domain", ".txt").also { it.delete() }
        domainTest({ it.copy(appleDomainAssociationFile = absent.path) }) { client ->
            assertEquals(HttpStatusCode.NotFound, client.get(route).status)
        }
        // A directory at that path is not the file either.
        val dir = File.createTempFile("apple-domain", ".dir").also { it.delete(); it.mkdir(); it.deleteOnExit() }
        domainTest({ it.copy(appleDomainAssociationFile = dir.path) }) { client ->
            assertEquals(HttpStatusCode.NotFound, client.get(route).status)
        }
    }
}
