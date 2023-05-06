/*
 * Copyright 2011 the original author or authors.
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
//file:noinspection GroovyAccessibility
package org.gradle.internal.resource.transport.http2

import org.apache.hc.client5.http.auth.AuthScope
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.core5.http.URIScheme
import org.apache.hc.core5.http.protocol.HttpContext
import org.apache.hc.core5.ssl.SSLContexts
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.internal.authentication.AllSchemesAuthentication
import org.gradle.internal.credentials.DefaultHttpHeaderCredentials
import org.gradle.internal.resource.UriTextResource
import spock.lang.Ignore
import spock.lang.Specification

class HttpClientConfigurerTest extends Specification {
    public static final String REMOTE_HOST = "host"
    public static final int SOME_PORT = 1234
    public static final String PROXY_HOST = "proxy"
    HttpAsyncClientBuilder httpClientBuilder = HttpAsyncClientBuilder.create();

    PasswordCredentials credentials = Mock()
    AllSchemesAuthentication basicAuthentication = new AllSchemesAuthentication(credentials)

    HttpContext ctx = Mock()
    HttpProxySettings proxySettings = Mock()
    HttpProxySettings secureProxySettings = Mock()
    HttpTimeoutSettings timeoutSettings = Mock()
    HttpSettings httpSettings = Mock() {
        getProxySettings() >> proxySettings
        getSecureProxySettings() >> secureProxySettings
        getTimeoutSettings() >> timeoutSettings
    }
    SslContextFactory sslContextFactory = Mock() {
        createSslContext() >> SSLContexts.createDefault()
    }
    HttpClientConfigurer configurer = new HttpClientConfigurer(httpSettings)

    def setup() {
        basicAuthentication.addHost(REMOTE_HOST, SOME_PORT)
    }

    def "configures http client with no credentials or proxy"() {
        httpSettings.authenticationSettings >> []
        httpSettings.sslContextFactory >> sslContextFactory

        when:
        configurer.configure(httpClientBuilder)

        then:
        httpClientBuilder.credentialsProvider.getCredentials(new AuthScope(null, null, -1, null, null), ctx) == null
    }

    def "configures http client with proxy credentials"() {
        httpSettings.authenticationSettings >> []
        httpSettings.sslContextFactory >> sslContextFactory
        proxySettings.proxy >> new HttpProxySettings.HttpProxy(PROXY_HOST, SOME_PORT, "proxyUser", "proxyPass")

        when:
        configurer.configure(httpClientBuilder)

        then:
        def proxyCredentials = httpClientBuilder.credentialsProvider.getCredentials(new AuthScope(PROXY_HOST, SOME_PORT), ctx)
        proxyCredentials.userPrincipal.name == "proxyUser"
        proxyCredentials.password == "proxyPass".toCharArray()
    }

    @Ignore("broken for http2 client")
    def "configures http client with proxy credentials via NTLM"() {
        httpSettings.authenticationSettings >> []
        httpSettings.sslContextFactory >> sslContextFactory
        proxySettings.proxy >> new HttpProxySettings.HttpProxy(PROXY_HOST, SOME_PORT, "domain/proxyUser", "proxyPass")

        when:
        configurer.configure(httpClientBuilder)

        then:
        def proxyCredentials = httpClientBuilder.credentialsProvider.getCredentials(new AuthScope(PROXY_HOST, SOME_PORT), ctx)
        proxyCredentials.userPrincipal.name == "domain/proxyUser"
        proxyCredentials.password == "proxyPass".toCharArray()

        and:
        def ntlmCredentials = httpClientBuilder.credentialsProvider.getCredentials(new AuthScope(null, PROXY_HOST, SOME_PORT, null, "ntlm"), ctx)
        ntlmCredentials.userPrincipal.name == 'DOMAIN\\proxyUser'
        ntlmCredentials.domain == 'DOMAIN'
        ntlmCredentials.userName == 'proxyUser'
        ntlmCredentials.password == 'proxyPass'.toCharArray()
        ntlmCredentials.workstation != ''
    }

    def "configures http client with basic auth credentials"() {
        httpSettings.authenticationSettings >> [basicAuthentication]
        credentials.username >> "user"
        credentials.password >> "pass"
        httpSettings.sslContextFactory >> sslContextFactory

        when:
        configurer.configure(httpClientBuilder)

        then:
        def basicCredentials = httpClientBuilder.credentialsProvider.getCredentials(new AuthScope(REMOTE_HOST, SOME_PORT), ctx)
        basicCredentials.userPrincipal.name == "user"
        basicCredentials.password == "pass".toCharArray()

        and:
        httpClientBuilder.requestInterceptors[0].interceptor instanceof HttpClientConfigurer.PreemptiveAuth
    }

    @Ignore("broken for http2 client")
    def "configures http client with basic auth credentials via NTLM"() {
        httpSettings.authenticationSettings >> [basicAuthentication]
        credentials.username >> "domain/user"
        credentials.password >> "pass"
        httpSettings.sslContextFactory >> sslContextFactory

        when:
        configurer.configure(httpClientBuilder)

        then:
        def basicCredentials = httpClientBuilder.credentialsProvider.getCredentials(new AuthScope(REMOTE_HOST, SOME_PORT), ctx)
        basicCredentials.userPrincipal.name == "domain/user"
        basicCredentials.password == "pass".toCharArray()

        // @TODO(sgammon): fixes here
        and:
        def ntlmCredentials = httpClientBuilder.credentialsProvider.getCredentials(new AuthScope(null, REMOTE_HOST, SOME_PORT, null, "ntlm"), ctx)
        ntlmCredentials.userPrincipal.name == 'DOMAIN\\user'
        ntlmCredentials.domain == 'DOMAIN'
        ntlmCredentials.userName == 'user'
        ntlmCredentials.password == 'pass'.toCharArray()
        ntlmCredentials.workstation != ''

        and:
        httpClientBuilder.requestInterceptors[0].interceptor instanceof HttpClientConfigurer.PreemptiveAuth
    }

    def "configures http client with http header auth credentials"() {
        def httpHeaderCredentials = new DefaultHttpHeaderCredentials()
        httpHeaderCredentials.setName("TestHttpHeaderName")
        httpHeaderCredentials.setValue("TestHttpHeaderValue")
        AllSchemesAuthentication httpHeaderAuthentication = new AllSchemesAuthentication(httpHeaderCredentials)
        httpHeaderAuthentication.addHost(REMOTE_HOST, SOME_PORT)

        httpSettings.authenticationSettings >> [httpHeaderAuthentication]
        httpSettings.sslContextFactory >> sslContextFactory

        when:
        configurer.configure(httpClientBuilder)
        HttpClientHttpHeaderCredentials actualHttpHeaderCredentials = httpClientBuilder.credentialsProvider.getCredentials(new AuthScope(REMOTE_HOST, SOME_PORT), ctx)

        then:
        actualHttpHeaderCredentials.header.name == 'TestHttpHeaderName'
        actualHttpHeaderCredentials.header.value == 'TestHttpHeaderValue'

        and:
        httpClientBuilder.requestInterceptors[0].interceptor instanceof HttpClientConfigurer.PreemptiveAuth
    }

    def "configures http client with user agent"() {
        httpSettings.authenticationSettings >> []
        httpSettings.proxySettings >> proxySettings
        httpSettings.sslContextFactory >> sslContextFactory

        when:
        configurer.configure(httpClientBuilder)

        then:
        httpClientBuilder.userAgent == UriTextResource.userAgentString
    }

    def "configures http client auth and proxy settings"() {
        when:
        configurer.configure(httpClientBuilder)

        then:
        1 * httpSettings.authenticationSettings >> []
        1 * httpSettings.proxySettings >> proxySettings
    }

    def "configures http client timeout"() {
        when:
        configurer.configure(httpClientBuilder)

        then:
        // @TODO(sgammon): socket timeout here + default socket config
        1 * timeoutSettings.connectionTimeoutMs >> 10000
        2 * timeoutSettings.socketTimeoutMs >> 30000
//        httpClientBuilder.defaultRequestConfig.connectTimeout.toMilliseconds() == 10000
//        httpClientBuilder.defaultRequestConfig.socketTimeout == 30000
    }

    def "configures http client socket and SSL context"() {
        when:
        configurer.configure(httpClientBuilder)

        then:
        1 * httpSettings.sslContextFactory >> sslContextFactory
        httpClientBuilder.connManager != null
        httpClientBuilder.connManagerShared
        // @TODO(sgammon): testing for TLS config settings
    }
}
