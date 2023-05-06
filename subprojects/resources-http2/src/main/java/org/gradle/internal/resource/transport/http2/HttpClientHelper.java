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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import org.apache.commons.io.IOUtils;
import org.apache.hc.client5.http.async.HttpAsyncClient;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.async.methods.SimpleResponseConsumer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.protocol.RedirectLocations;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.URIBuilder;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.internal.UncheckedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLHandshakeException;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.hc.client5.http.protocol.HttpClientContext.REDIRECT_LOCATIONS;

/**
 * Provides some convenience and unified logging.
 */
public class HttpClientHelper implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpClientHelper.class);
    private final HttpAsyncClient client;
    private final boolean clientShared;
    private final DocumentationRegistry documentationRegistry;
    private final HttpSettings settings;

    /**
     * Maintains a queue of contexts which are shared between threads when authentication
     * is activated. When a request is performed, it will pick a context from the queue,
     * and create a new one whenever it's not available (which either means it's the first request
     * or that other requests are being performed concurrently). The queue will grow as big as
     * the max number of concurrent requests executed.
     */
    private final ConcurrentLinkedQueue<HttpContext> sharedContext;

    /**
     * Use {@link Factory#create(HttpSettings)} to instantiate instances.
     */
    @VisibleForTesting
    HttpClientHelper(DocumentationRegistry documentationRegistry, HttpSettings settings) {
        this.documentationRegistry = documentationRegistry;
        this.settings = settings;
        if (!settings.getAuthenticationSettings().isEmpty()) {
            sharedContext = new ConcurrentLinkedQueue<>();
        } else {
            sharedContext = null;
        }

        // re-use global client if handed default HTTP settings singleton
        if (settings instanceof DefaultHttpSettings && ((DefaultHttpSettings)settings).isDefaultSingleton()) {
            client = HttpClientConfigurer.obtainClient(settings);
            clientShared = true;
        } else {
            HttpClientConfigurer configurer = new HttpClientConfigurer(settings);
            HttpAsyncClientBuilder builder = HttpAsyncClientBuilder.create();
            configurer.configure(builder);
            CloseableHttpAsyncClient asyncClient = builder.build();
            asyncClient.start();
            client = asyncClient;
            clientShared = false;
        }
    }

    private static URI uriForRequest(SimpleHttpRequest request) {
        try {
            return request.getUri();
        } catch (URISyntaxException syntaxException) {
            throw UncheckedException.throwAsUncheckedException(syntaxException);
        }
    }

    private HttpClientResponse performRawHead(String source, boolean revalidate) {
        return performRequest(new SimpleHttpRequest(Method.HEAD.name(), source), revalidate);
    }

    public HttpClientResponse performHead(String source, boolean revalidate) {
        return processResponse(performRawHead(source, revalidate));
    }

    HttpClientResponse performRawGet(String source, boolean revalidate) {
        return performRequest(new SimpleHttpRequest(Method.GET.name(), source), revalidate);
    }

    @Nonnull
    public HttpClientResponse performGet(String source, boolean revalidate) {
        return processResponse(performRawGet(source, revalidate));
    }

    public HttpClientResponse performRequest(SimpleHttpRequest request, boolean revalidate) {
        String method = request.getMethod();
        if (revalidate) {
            request.addHeader(HttpHeaders.CACHE_CONTROL, "max-age=0");
        }
        try {
            return executeGetOrHead(request);
        } catch (FailureFromRedirectLocation e) {
            throw createHttpRequestException(method, e.getCause(), e.getLastRedirectLocation());
        } catch (IOException e) {
            throw createHttpRequestException(method, createCause(e), uriForRequest(request));
        }
    }

    @Nonnull
    private static HttpRequestException createHttpRequestException(String method, Throwable cause, URI uri) {
        return new HttpRequestException(String.format("Could not %s '%s'.", method, stripUserCredentials(uri)), cause);
    }

    private Exception createCause(IOException e) {
        if (e instanceof SSLHandshakeException) {
            SSLHandshakeException sslException = (SSLHandshakeException) e;
            String message = String.format(
                "%s support the client's requested TLS protocol versions: (%s). " +
                    "You may need to configure the client to allow other protocols to be used. " +
                    "See: %s",
                getConfidenceNote(sslException),
                String.join(", ", HttpClientConfigurer.supportedTlsVersions()),
                documentationRegistry.getDocumentationFor("build_environment", "sec:gradle_system_properties")
            );
            return new HttpRequestException(message, e);
        }
        return e;
    }

    @Nonnull
    private static String getConfidenceNote(SSLHandshakeException sslException) {
        if (sslException.getMessage() != null && sslException.getMessage().contains("protocol_version")) {
            // If we're handling an SSLHandshakeException with the error of 'protocol_version' we know that the server doesn't support this protocol.
            return "The server does not";
        }
        // Sometimes the SSLHandshakeException doesn't include the 'protocol_version', even though this is the cause of the error.
        // Tell the user this but with less confidence.
        return "The server may not";
    }

    protected HttpClientResponse executeGetOrHead(SimpleHttpRequest method) throws IOException {
        HttpClientResponse response = performHttpRequest(method);
        // Consume content for non-successful, responses. This avoids the connection being left open.
        if (!response.wasSuccessful()) {
            response.close();
        }
        return response;
    }

    public HttpClientResponse performHttpRequest(HttpPut put) throws IOException, URISyntaxException {
        // convert `HttpPut` to a `SimpleHttpRequest` and execute via `performHttpRequest`
        // @TODO(sgammon): efficient body consumption

        byte[] bytes = IOUtils.toByteArray(put.getEntity().getContent());
        SimpleHttpRequest request = new SimpleHttpRequest(put.getMethod(), put.getUri());
        request.setBody(bytes, ContentType.create(put.getEntity().getContentType()));
        return performHttpRequest(request);
    }

    public HttpClientResponse performHttpRequest(HttpGet get) throws IOException, URISyntaxException {
        // convert `HttpGet` to a `SimpleHttpRequest` and execute via `performHttpRequest`
        return performHttpRequest(new SimpleHttpRequest(get.getMethod(), get.getUri()));
    }

    public HttpClientResponse performHttpRequest(SimpleHttpRequest request) throws IOException {
        if (sharedContext == null) {
            // There's no authentication involved, requests can be done concurrently
            return performHttpRequest(request, new BasicHttpContext());
        }
        HttpContext httpContext = nextAvailableSharedContext();
        try {
            return performHttpRequest(request, httpContext);
        } finally {
            sharedContext.add(httpContext);
        }
    }

    private HttpContext nextAvailableSharedContext() {
        HttpContext context = sharedContext.poll();
        if (context == null) {
            return new BasicHttpContext();
        }
        return context;
    }

    private HttpClientResponse performHttpRequest(SimpleHttpRequest request, HttpContext httpContext) throws IOException {
        // Without this, HTTP Client prohibits multiple redirects to the same location within the same context
        httpContext.removeAttribute(REDIRECT_LOCATIONS);
        LOGGER.debug("Performing HTTP {}: {}", request.getMethod(), stripUserCredentials(uriForRequest(request)));

        try {
            AtomicReference<Throwable> throwable = new AtomicReference<>();
            AtomicBoolean err = new AtomicBoolean(false);
            CountDownLatch latch = new CountDownLatch(1);
            Future<SimpleHttpResponse> operation = getClient().execute(
                SimpleRequestProducer.create(request),
                SimpleResponseConsumer.create(),
                null,
                httpContext,
                new FutureCallback<SimpleHttpResponse>() {
                    @Override
                    public void completed(SimpleHttpResponse result) {
                        latch.countDown();
                    }

                    @Override
                    public void failed(Exception ex) {
                        try {
                            throwable.set(ex);
                            err.compareAndSet(false, true);
                        } finally {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void cancelled() {
                        try {
                            err.compareAndSet(false, true);
                        } finally {
                            latch.countDown();
                        }
                    }
                });

            // kick off operation and wait for it to complete
            SimpleHttpResponse response = operation.get();

            // await completion
            latch.await();

            if (err.get()) {
                Throwable t = throwable.get();
                if (t == null) {
                    t = new IllegalStateException("Unidentified error");
                }
                throw new RuntimeException(t);
            }
            return toHttpClientResponse(request, httpContext, response);

        } catch (InterruptedException | ExecutionException | RuntimeException e) {
            if (e instanceof ExecutionException) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                } else if (cause instanceof CancellationException) {
                    throw new HttpRequestException(cause.getMessage(), cause);
                }
            }

            validateRedirectChain(httpContext);
            URI lastRedirectLocation = stripUserCredentials(getLastRedirectLocation(httpContext));
            throw new RuntimeException((lastRedirectLocation == null) ? e : new FailureFromRedirectLocation(lastRedirectLocation, e));
        }
    }

    private HttpClientResponse toHttpClientResponse(SimpleHttpRequest request, HttpContext httpContext, SimpleHttpResponse response) {
        validateRedirectChain(httpContext);
        URI lastRedirectLocation = getLastRedirectLocation(httpContext);
        URI effectiveUri = lastRedirectLocation == null ? uriForRequest(request) : lastRedirectLocation;
        return new HttpClientResponse(request.getMethod(), effectiveUri, response, httpContext);
    }

    /**
     * Validates that no redirect used an insecure protocol.
     * Redirecting through an insecure protocol can allow for a MITM redirect to an attacker controlled HTTPS server.
     */
    private void validateRedirectChain(HttpContext httpContext) {
        settings.getRedirectVerifier().validateRedirects(getRedirectLocations(httpContext));
    }

    @Nonnull
    private static List<URI> getRedirectLocations(HttpContext httpContext) {
        RedirectLocations redirects = (RedirectLocations) httpContext.getAttribute(REDIRECT_LOCATIONS);
        return redirects == null ? Collections.emptyList() : redirects.getAll();
    }


    private static URI getLastRedirectLocation(HttpContext httpContext) {
        List<URI> redirectLocations = getRedirectLocations(httpContext);
        return redirectLocations.isEmpty() ? null : Iterables.getLast(redirectLocations);
    }

    @Nonnull
    private static HttpClientResponse processResponse(HttpClientResponse response) {
        if (response.wasMissing()) {
            LOGGER.info("Resource missing. [HTTP {}: {}]", response.getMethod(), stripUserCredentials(response.getEffectiveUri()));
            return response;
        }

        if (response.wasSuccessful()) {
            return response;
        }

        URI effectiveUri = stripUserCredentials(response.getEffectiveUri());
        LOGGER.info("Failed to get resource: {}. [HTTP {}: {})]", response.getMethod(), response.getStatusLine(), effectiveUri);
        throw new HttpErrorStatusCodeException(
            response.getMethod(),
            effectiveUri != null ? effectiveUri.toString() : null,
            response.getStatusLine().getStatusCode(),
            response.getStatusLine().getReasonPhrase()
        );
    }

    private HttpAsyncClient getClient() {
        return client;
    }

    @Override
    public synchronized void close() throws IOException {
        if (client != null) {
            HttpAsyncClient current = this.client;
            if (current instanceof CloseableHttpAsyncClient && !clientShared) {
                ((CloseableHttpAsyncClient) current).close(CloseMode.GRACEFUL);
            }
            if (sharedContext != null) {
                sharedContext.clear();
            }
        }
    }

    /**
     * Strips the {@link URI#getUserInfo() user info} from the {@link URI} making it
     * safe to appear in log messages.
     */
    @Nullable
    @VisibleForTesting
    static URI stripUserCredentials(@CheckForNull URI uri) {
        if (uri == null) {
            return null;
        }
        try {
            return new URIBuilder(uri).setUserInfo(null).build();
        } catch (URISyntaxException e) {
            throw UncheckedException.throwAsUncheckedException(e, true);
        }
    }

    private static class FailureFromRedirectLocation extends IOException {
        private final URI lastRedirectLocation;

        private FailureFromRedirectLocation(URI lastRedirectLocation, Throwable cause) {
            super(cause);
            this.lastRedirectLocation = lastRedirectLocation;
        }

        private URI getLastRedirectLocation() {
            return lastRedirectLocation;
        }
    }

    /**
     * Factory for creating the {@link HttpClientHelper}
     */
    @FunctionalInterface
    public interface Factory {
        HttpClientHelper create(HttpSettings settings);

        /**
         * Method should only be used for DI registry and testing.
         * For other uses of {@link HttpClientHelper}, inject an instance of {@link Factory} to create one.
         */
        static Factory createFactory(DocumentationRegistry documentationRegistry) {
            return settings -> new HttpClientHelper(documentationRegistry, settings);
        }
    }

}
