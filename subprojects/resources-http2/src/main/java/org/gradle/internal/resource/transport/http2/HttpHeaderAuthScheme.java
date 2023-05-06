/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.internal.resource.transport.http2;

import org.apache.hc.client5.http.auth.AuthChallenge;
import org.apache.hc.client5.http.auth.AuthScheme;
import org.apache.hc.client5.http.auth.AuthenticationException;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.MalformedChallengeException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;

import java.security.Principal;

public class HttpHeaderAuthScheme implements AuthScheme {
    public static final String AUTH_SCHEME_NAME = "header";

    @Override
    public String getName() {
        return AUTH_SCHEME_NAME;
    }

    @Override
    public void processChallenge(AuthChallenge authChallenge, HttpContext context) throws MalformedChallengeException {
        // no-op
    }

    @Override
    public String getRealm() {
        return null;
    }

    @Override
    public boolean isConnectionBased() {
        return false;
    }

    @Override
    public boolean isResponseReady(HttpHost host, CredentialsProvider credentialsProvider, HttpContext context) throws AuthenticationException {
        return true;
    }

    @Override
    public boolean isChallengeComplete() {
        return true;
    }

    @Override
    public String generateAuthResponse(HttpHost host, HttpRequest request, HttpContext context) throws AuthenticationException {
        return null;
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }

//    @Override
//    @SuppressWarnings("deprecation")
//    public Header authenticate(final Credentials credentials, final HttpRequest request) throws AuthenticationException {
//        return this.authenticate(credentials, request, new BasicHttpContext());
//    }

//    @Override
//    public Header authenticate(final Credentials credentials, final HttpRequest request, final HttpContext context) throws AuthenticationException {
//        Args.check(credentials instanceof HttpClientHttpHeaderCredentials, "Only " + HttpClientHttpHeaderCredentials.class.getCanonicalName() + " supported for AuthScheme " + this.getClass().getCanonicalName() + ", got " + credentials.getClass().getName());
//        HttpClientHttpHeaderCredentials httpClientHttpHeaderCredentials = (HttpClientHttpHeaderCredentials) credentials;
//        return httpClientHttpHeaderCredentials.getHeader();
//    }
}
