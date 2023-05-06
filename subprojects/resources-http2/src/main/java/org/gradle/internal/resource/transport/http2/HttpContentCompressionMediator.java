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

package org.gradle.internal.resource.transport.http2;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpResponseInterceptor;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.protocol.HttpContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * Applies `Accept-Encoding` headers to the request and content decompression phases to the response.
 *
 * @since 8.2
 * @author sgammon (sam@elide.ventures)
 */
public class HttpContentCompressionMediator implements HttpRequestInterceptor, HttpResponseInterceptor {
    public static final String PROPERTY_COMPRESSED = "gradle.http.compressed";
    public static final String PROPERTY_DECOMPRESSOR = "gradle.http.decompressor";
    public static final String GZIP_ENCODING = "gzip";
    public static final String BROTLI_ENCODING = "br";
    private static final AtomicBoolean BROTLI_SUPPORTED = new AtomicBoolean(false);
    private static final GzipCompressionHandler GZIP_HANDLER = new GzipCompressionHandler();
    private static final BrotliCompressionHandler BROTLI_HANDLER = new BrotliCompressionHandler();
    private static final Map<String, HttpCompressionHandler> DEFAULT_HANDLER_MAP;

    private final String encodings;
    private final Supplier<HttpCustomCompressionHandler> handlerSupplier;
    private final Map<String, HttpCompressionHandler> handlers;

    static {
        Boolean brotliSupported;
        try {
            Brotli4jLoader.ensureAvailability();
            brotliSupported = true;
        } catch (UnsatisfiedLinkError err) {
            brotliSupported = false;
        }
        BROTLI_SUPPORTED.set(brotliSupported);
        DEFAULT_HANDLER_MAP = new HashMap<>(brotliSupported ? 2 : 1);
        DEFAULT_HANDLER_MAP.put(GZIP_ENCODING, GZIP_HANDLER);
        if (BROTLI_SUPPORTED.get()) {
            DEFAULT_HANDLER_MAP.put(BROTLI_ENCODING, BROTLI_HANDLER);
        }
    }

    /**
     * Decompression action which can be triggered by downstream actions in order to decompress the content.
     */
    public interface DecompressionAction {
        /**
         * Decompresses the content of the given response.
         *
         * @param response Inflated response object.
         * @return Wrapped input stream with decompression.
         */
        InputStream wrap(SimpleHttpResponse response) throws IOException;
    }

    public static class GzipCompressionHandler implements HttpCompressionHandler {
        @Override
        public String name() {
            return GZIP_ENCODING;
        }

        @Override
        public InputStream decompress(HttpResponse response, InputStream stream) throws IOException {
            return new GZIPInputStream(stream);
        }
    }

    public static class BrotliCompressionHandler implements HttpCompressionHandler {
        @Override
        public String name() {
            return BROTLI_ENCODING;
        }

        @Override
        public InputStream decompress(HttpResponse response, InputStream stream) throws IOException {
            return new BrotliInputStream(stream);
        }
    }

    private HttpContentCompressionMediator(String encodings, Supplier<HttpCustomCompressionHandler> compressionHandlerSupplier, Map<String, HttpCompressionHandler> handlers) {
        this.encodings = encodings;
        this.handlerSupplier = compressionHandlerSupplier;
        this.handlers = handlers;
    }

    @Override
    public void process(HttpRequest request, EntityDetails entity, HttpContext context) throws HttpException, IOException {
        // set `Accept-Encoding`, joined in the order of preference
        request.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, encodings));
    }

    @Override
    public void process(HttpResponse response, EntityDetails entity, HttpContext context) throws HttpException, IOException {
        if (!response.containsHeader(HttpHeaders.CONTENT_ENCODING)) {
            return;  // no-op
        }

        try {
            String encoding = response.getFirstHeader(HttpHeaders.CONTENT_ENCODING).getValue().trim();
            HttpCompressionHandler handler = handlers.get(encoding);
            if (handler == null && handlerSupplier != null) {
                handler = handlerSupplier.get();
            }
            if (handler == null) {
                throw new IllegalStateException("No compression handler found for encoding: " + encoding);
            }

            // mark as compressed and hook up decompressor operation
            final HttpCompressionHandler effectiveHandler = handler;
            context.setAttribute(PROPERTY_COMPRESSED, Boolean.TRUE);
            context.setAttribute(PROPERTY_DECOMPRESSOR, (DecompressionAction) simple -> {
                try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                    byte[] bytes = simple.getBody().getBodyBytes();
                    try (ByteArrayInputStream instream = new ByteArrayInputStream(bytes)) {
                        try (InputStream isr = effectiveHandler.decompress(response, instream)) {
                            IOUtils.copy(isr, stream);
                        }
                    }
                    return new ByteArrayInputStream(stream.toByteArray());
                }
            });
        } catch (Exception e) {
            throw new HttpException("Failed to decompress response", e);
        }
    }

    private static HttpCompressionHandler getBuiltinHandler(String name) {
        switch (name) {
            case GZIP_ENCODING:
                return GZIP_HANDLER;
            case BROTLI_ENCODING:
                return BROTLI_HANDLER;
            default:
                throw new IllegalArgumentException("No built-in compression handler at name: " + name);
        }
    }

    public static class Builder {
        private static final HttpContentCompressionMediator INSTANCE;
        private String[] encodings;
        private Supplier<HttpCustomCompressionHandler> compressionHandler = null;

        static {
            if (BROTLI_SUPPORTED.get()) {
                INSTANCE = new HttpContentCompressionMediator(BROTLI_ENCODING + ", " + GZIP_ENCODING, null, DEFAULT_HANDLER_MAP);
            } else {
                INSTANCE = new HttpContentCompressionMediator(GZIP_ENCODING, null, DEFAULT_HANDLER_MAP);
            }
        }

        public void encodings(String... encodings) {
            this.encodings = encodings;
        }

        public void compressionHandlerSupplier(Supplier<HttpCustomCompressionHandler> compressionHandler) {
            this.compressionHandler = compressionHandler;
        }

        public HttpContentCompressionMediator build() {
            StringBuilder builder = new StringBuilder();
            Map<String, HttpCompressionHandler> handlers = new HashMap<>(encodings.length);
            boolean first = true;
            for (String encoding : encodings) {
                handlers.put(encoding, getBuiltinHandler(encoding));

                if (first) {
                    first = false;
                } else {
                    builder.append(", ");
                }
                builder.append(encoding);
            }
            return new HttpContentCompressionMediator(builder.toString(), compressionHandler, handlers);
        }
    }

    public static HttpContentCompressionMediator defaults() {
        return Builder.INSTANCE;
    }
}
