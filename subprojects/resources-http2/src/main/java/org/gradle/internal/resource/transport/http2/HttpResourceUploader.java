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

package org.gradle.internal.resource.transport.http2;

import org.apache.commons.io.IOUtils;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Method;
import org.gradle.internal.resource.ExternalResourceName;
import org.gradle.internal.resource.ReadableContent;
import org.gradle.internal.resource.transfer.ExternalResourceUploader;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public class HttpResourceUploader implements ExternalResourceUploader {

    private final HttpClientHelper http;

    public HttpResourceUploader(HttpClientHelper http) {
        this.http = http;
    }

    @Override
    public void upload(@NotNull ReadableContent resource, ExternalResourceName destination) throws IOException {
        SimpleHttpRequest request = new SimpleHttpRequest(
            Method.PUT,
            destination.getUri()
        );
        try (final RepeatableInputStreamEntity entity = new RepeatableInputStreamEntity(resource, ContentType.APPLICATION_OCTET_STREAM)) {
            try (InputStream stream = entity.getContent()) {
                try {
                    byte[] bytes;
                    if (stream != null) {
                        bytes = IOUtils.toByteArray(stream);
                    } else {
                        bytes = new byte[0];
                    }
                    request.setBody(bytes, ContentType.APPLICATION_OCTET_STREAM);

                    try (HttpClientResponse response = http.performHttpRequest(request)) {
                        if (!response.wasSuccessful()) {
                            URI effectiveUri = response.getEffectiveUri();
                            throw new HttpErrorStatusCodeException(response.getMethod(), effectiveUri.toString(), response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
                        }
                    }
                } catch (HttpErrorStatusCodeException e) {
                    throw e;  // re-throw

                } catch (Throwable e) {
                    throw new HttpRequestException("Could not PUT to '" + destination.getUri() + "'.", e);
                }
            }
        }
    }
}
