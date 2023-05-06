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

import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.HttpEntities;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.gradle.internal.resource.transport.http2.HttpContentCompressionMediator.DecompressionAction;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;

public class HttpClientResponse implements Closeable {

    private final String method;
    private final URI effectiveUri;
    private final BasicHttpResponse httpResponse;
    private final HttpContext httpContext;
    private boolean closed;

    HttpClientResponse(String method, URI effectiveUri, BasicHttpResponse httpResponse, HttpContext context) {
        Objects.requireNonNull(httpResponse, "http response cannot be null");
        Objects.requireNonNull(context, "http context cannot be null");
        this.method = method;
        this.effectiveUri = effectiveUri;
        this.httpResponse = httpResponse;
        this.httpContext = context;
    }

    public String getHeader(String name) {
        Header header = httpResponse.getFirstHeader(name);
        return header == null ? null : header.getValue();
    }

    public InputStream getContent() throws IOException {
        // cast to a `SimpleHttpResponse` and return the body bytes
        SimpleHttpResponse simple;
        if (httpResponse instanceof SimpleHttpResponse) {
            simple = (SimpleHttpResponse) httpResponse;

            // check for compression
            Boolean isCompressed = (Boolean)httpContext.getAttribute(HttpContentCompressionMediator.PROPERTY_COMPRESSED);
            if (Boolean.TRUE.equals(isCompressed)) {
                // if the body is marked as compressed, there should be a compressor mounted at another property, which
                // we can use to obtain a decompressor-wrapped `InputStream`.
                DecompressionAction decompressor = (DecompressionAction)httpContext.getAttribute(HttpContentCompressionMediator.PROPERTY_DECOMPRESSOR);
                Objects.requireNonNull(decompressor, "failed to locate decompressor for compressed payload");
                try (InputStream isr = decompressor.wrap(simple)) {
                    return isr;
                }
            } else {
                // otherwise, inflate as a regular body.
                try (HttpEntity entity = HttpEntities.create(simple.getBodyBytes(), simple.getContentType())) {
                    return entity.getContent();
                }
            }
        }
        return null;
    }

    public StatusLine getStatusLine() {
        return new StatusLine(httpResponse);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
        }
    }

    String getMethod() {
        return method;
    }

    URI getEffectiveUri() {
        return effectiveUri;
    }

    boolean wasSuccessful() {
        int statusCode = httpResponse.getCode();
        return statusCode >= 200 && statusCode < 400;
    }

    boolean wasMissing() {
        int statusCode = getStatusLine().getStatusCode();
        return statusCode == 404;
    }
}
