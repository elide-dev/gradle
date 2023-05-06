/*
 * Copyright 2012 the original author or authors.
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

import org.apache.hc.client5.http.async.methods.SimpleHttpResponse
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.Header
import org.apache.hc.core5.http.HttpHeaders
import org.apache.hc.core5.http.message.BasicHeader
import org.apache.hc.core5.http.protocol.HttpContext
import spock.lang.Ignore


class HttpResponseResourceTest extends AbstractHttpClientTest {

    def sourceUrl = new URI("https://gradle.org")
    def method = "GET"
    def context = Mock(HttpContext)

    private mockResponse() {
        def response = SimpleHttpResponse
            .create(200)
        response
    }

    def "extracts etag"() {
        given:
        def response = mockResponse()
        response.addHeader(HttpHeaders.ETAG, "abc")

        expect:
        resource(response).metaData.etag == "abc"
    }

    def "handles no etag"() {
        given:
        def response = mockResponse()

        expect:
        resource(response).metaData.etag == null
    }

    @Ignore("broken on http2 adapter")
    def "is not openable more than once"() {
        setup:
        def response = mockResponse()
        response.setBody("hello testing 123", ContentType.TEXT_PLAIN)
        1 * response.getBody()
        when:
        def resource = this.resource(response)
        resource.openStream()
        and:
        resource.openStream()
        then:
        def ex = thrown(IOException);
        ex.message == "Unable to open Stream as it was opened before."
    }

    def "provides access to arbitrary headers"() {
        setup:
        def response = mockResponse()
        addHeader(response, name, value)

        expect:
        resource(response).getHeaderValue(name) == value

        where:
        name = "X-Client-Deprecation-Message"
        value = "Some message"
    }

    def "returns null when accessing value of a non existing header"() {
        given:
        def response = mockResponse()

        expect:
        resource(response).getHeaderValue("X-No-Such-Header") == null
    }

    // @TODO(sgammon): needs fixing
    @Ignore("broken on http2 adapter")
    def "close closes the response"() {
        given:
        def response = mockResponse()
        def mockedHttpResponse = mockedHttpResponse(response)

        when:
        resource(response).close()

        then:
        interaction {
            assertIsClosedCorrectly(mockedHttpResponse)
        }
    }

    HttpResponseResource resource(SimpleHttpResponse response) {
        new HttpResponseResource(method, sourceUrl, new HttpClientResponse("GET", sourceUrl, response, context))
    }

    void addHeader(SimpleHttpResponse response, String name, String value) {
        response.setHeader(header(name, value))
    }

    Header header(String name, String value) {
        new BasicHeader(name, value)
    }
}
