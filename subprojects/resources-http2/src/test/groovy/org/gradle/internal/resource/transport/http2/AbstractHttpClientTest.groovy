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

import org.apache.hc.core5.http.HttpEntity
import org.apache.hc.core5.http.message.BasicHttpResponse
import spock.lang.Specification

abstract class AbstractHttpClientTest extends Specification {

    MockedHttpResponse mockedHttpResponse() {
        BasicHttpResponse response = Mock()
        mockedHttpResponse(response)
    }

    MockedHttpResponse mockedHttpResponse(BasicHttpResponse response) {
        HttpEntity entity = Mock()
        InputStream content = Mock()

        new MockedHttpResponse(entity: entity, content: content, response: response)
    }

    static class MockedHttpResponse {
        HttpEntity entity
        InputStream content
        BasicHttpResponse response
    }

    void assertIsClosedCorrectly(MockedHttpResponse mockedHttpResponse) {
//        def response = mockedHttpResponse.response
//        def entity = mockedHttpResponse.entity
//        def content = mockedHttpResponse.content
//        _ * new StatusLine(response) >> new StatusLine(new ProtocolVersion("HTTP", 1, 1), 400, "I'm broken")
//     @TODO(sgammon): these are not supported on this interface
//        1 * response.close()
//        _ * response.getEntity() >> entity
//        1 * entity.isStreaming() >> true
//        1 * entity.content >> content
//        1 * content.close()
    }
}
