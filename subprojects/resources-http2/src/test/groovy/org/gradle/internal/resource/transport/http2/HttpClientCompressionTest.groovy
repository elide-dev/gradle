/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.resource.transport.http2

import com.aayushatharva.brotli4j.encoder.BrotliOutputStream
import org.apache.commons.io.IOUtils
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest
import org.apache.hc.core5.http.HttpHeaders
import org.apache.hc.core5.http.Method
import org.gradle.api.internal.DocumentationRegistry
import org.gradle.integtests.fixtures.TestResources
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.test.fixtures.keystore.TestKeyStore
import org.gradle.test.fixtures.server.http.BlockingHttpsServer
import org.junit.Rule
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

class HttpClientCompressionTest extends Specification {
    private static final List<String> MODERN_TLS_VERSIONS = ["TLSv1.2", "TLSv1.3"]

    @Rule
    final TestNameTestDirectoryProvider temporaryFolder = new TestNameTestDirectoryProvider(getClass())
    @Rule
    TestResources resources = new TestResources(temporaryFolder)
    @Rule
    BlockingHttpsServer server = new BlockingHttpsServer()
    TestKeyStore keyStore = TestKeyStore.init(resources.dir)
    HttpSettings settings = DefaultHttpSettings.builder()
        .withAuthenticationSettings([])
        .withSslContextFactory { keyStore.asSSLContext() }
        .withRedirectVerifier({})
        .build()

    def "default http settings should have compression enabled"() {
        expect:
        settings.isCompressionEnabled()
    }

    def "disabling compression should be possible via http settings"() {
        def settings = DefaultHttpSettings.builder()
            .withAuthenticationSettings([])
            .withSslContextFactory { keyStore.asSSLContext() }
            .withRedirectVerifier({})
            .withCompression(false)
            .build()

        expect:
        !settings.isCompressionEnabled()
    }

    def "submitting request with compression enabled should add 'Accept-Encoding' header"() {
        given:
        HttpClientHelper client = new HttpClientHelper(new DocumentationRegistry(), settings)
        server.configure(keyStore) { it -> MODERN_TLS_VERSIONS.contains(it) }
        server.start()
        def receivedEncoding = new AtomicReference<String>(null)
        server.expect(server.get("/test", { e ->
            receivedEncoding.set(e.requestHeaders.getFirst(HttpHeaders.ACCEPT_ENCODING))
            def str = "this is the content"
            e.sendResponseHeaders(200, str.bytes.length)
            e.responseBody.write(str.bytes)
        }))

        when:
        client.performGet("${server.getUri()}/test", false)

        then:
        noExceptionThrown()
        receivedEncoding.get() != null
        receivedEncoding.get().contains(HttpContentCompressionMediator.GZIP_ENCODING)
        receivedEncoding.get().contains(HttpContentCompressionMediator.BROTLI_ENCODING)

        cleanup:
        client.close()
    }

    def "gzip-encoded response is properly decoded"() {
        given:
        HttpClientHelper client = new HttpClientHelper(new DocumentationRegistry(), settings)
        server.configure(keyStore) { it -> MODERN_TLS_VERSIONS.contains(it) }
        server.start()
        server.expect(server.get("/testgzip", { e ->
            def str = "this is the content".getBytes(StandardCharsets.UTF_8)
            def baos = new ByteArrayOutputStream()
            def gzip = new GZIPOutputStream(baos)
            gzip.write(str)
            gzip.flush()
            gzip.close()
            def bytes = baos.toByteArray()
            e.getResponseHeaders().set(HttpHeaders.CONTENT_ENCODING, HttpContentCompressionMediator.GZIP_ENCODING)
            e.sendResponseHeaders(200, bytes.length)
            e.responseBody.write(bytes)
        }))
        def response = client.performHttpRequest(
            new SimpleHttpRequest(Method.GET, URI.create("${server.getUri()}/testgzip"))
        )

        expect:
        response.wasSuccessful()
        response.getHeader(HttpHeaders.CONTENT_ENCODING) == HttpContentCompressionMediator.GZIP_ENCODING
        def stream = response.getContent()
        def outbytes = IOUtils.toByteArray(stream)
        (new String(outbytes, StandardCharsets.UTF_8)) == "this is the content"

        cleanup:
        server.stop()
    }

    def "brotli-encoded response is properly decoded"() {
        given:
        HttpClientHelper client = new HttpClientHelper(new DocumentationRegistry(), settings)
        server.configure(keyStore) { it -> MODERN_TLS_VERSIONS.contains(it) }
        server.start()
        server.expect(server.get("/testbrotli", { e ->
            def str = "this is the content".getBytes(StandardCharsets.UTF_8)
            def baos = new ByteArrayOutputStream()
            def brotli = new BrotliOutputStream(baos)
            brotli.write(str)
            brotli.flush()
            brotli.close()
            def bytes = baos.toByteArray()
            e.getResponseHeaders().set(HttpHeaders.CONTENT_ENCODING, HttpContentCompressionMediator.BROTLI_ENCODING)
            e.sendResponseHeaders(200, bytes.length)
            e.responseBody.write(bytes)
        }))
        def response = client.performHttpRequest(
            new SimpleHttpRequest(Method.GET, URI.create("${server.getUri()}/testbrotli"))
        )

        expect:
        response.wasSuccessful()
        response.getHeader(HttpHeaders.CONTENT_ENCODING) == HttpContentCompressionMediator.BROTLI_ENCODING
        def stream = response.getContent()
        def outbytes = IOUtils.toByteArray(stream)
        (new String(outbytes, StandardCharsets.UTF_8)) == "this is the content"

        cleanup:
        server.stop()
    }
}
