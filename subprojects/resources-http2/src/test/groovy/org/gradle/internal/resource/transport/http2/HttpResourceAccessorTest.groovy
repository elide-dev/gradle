/*
 * Copyright 2016 the original author or authors.
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

import org.apache.hc.core5.http.message.BasicHttpResponse
import org.apache.hc.core5.http.message.StatusLine
import org.gradle.internal.resource.ExternalResourceName
import spock.lang.Ignore
import spock.lang.Specification

class HttpResourceAccessorTest extends Specification {
    def uri = new URI("http://somewhere")
    def name = new ExternalResourceName(uri)

    @Ignore("broken on http2 adapter")
    def "should call close() on CloseableHttpResource when getMetaData is called"() {
        def response = mockHttpResponse()
        def http = Mock(HttpClientHelper) {
            performHead(uri.toString(), _ as boolean) >> new HttpClientResponse("GET", uri, response)
        }

        when:
        new HttpResourceAccessor(http).getMetaData(name, false)

        // @TODO(sgammon): equivalent closeable
        then:
        1 * response.close()
    }

    private BasicHttpResponse mockHttpResponse() {
        def response = Mock(BasicHttpResponse)
        def statusLine = Mock(StatusLine)
        statusLine.getStatusCode() >> 200
        response
    }
}
