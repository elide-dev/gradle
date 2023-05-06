/*
 * Copyright 2015 the original author or authors.
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

import org.apache.hc.core5.http.HttpHeaders
import org.apache.hc.core5.http.HttpResponse
import org.apache.hc.core5.http.message.BasicHeader
import org.apache.hc.core5.http.message.BasicHttpRequest
import org.apache.hc.core5.http.protocol.HttpContext
import spock.lang.Specification

class AlwaysFollowAndPreserveMethodRedirectStrategyTest extends Specification {

    static final String[] HTTP_METHODS = ['GET', 'POST', 'PUT', 'HEAD', 'DELETE', 'OPTIONS', 'TRACE', 'PATCH']

    // @TODO(sgammon): test method preservation
    def "should get redirect for http method [#httpMethod]"() {
        setup:
        AlwaysFollowAndPreserveMethodRedirectStrategy strategy = new AlwaysFollowAndPreserveMethodRedirectStrategy()
        BasicHttpRequest request = Mock()
        HttpResponse response = Mock()
        HttpContext context = Mock()
        response.containsHeader(HttpHeaders.LOCATION) >> true
        response.getFirstHeader(HttpHeaders.LOCATION) >> new BasicHeader('location', 'http://redirectTo')
        request.getMethod() >> httpMethod

        when:
        def redirect = strategy.getLocationURI(request, response, context)

        then:
        strategy.isRedirected(request, response, context)
        redirect != null

        where:
        httpMethod << HTTP_METHODS + HTTP_METHODS.collect { it.toLowerCase() }
    }
}
