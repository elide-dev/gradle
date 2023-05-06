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
package org.gradle.internal.resource.transport.http2;


import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.gradle.authentication.Authentication;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.verifier.HttpRedirectVerifier;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Collection;

public class DefaultHttpSettings implements HttpSettings {
    // Total maximum connections for default HTTP client, and all HTTP clients (individually). Does not apply to HTTP/2.
    private static final int MAX_CONNECTIONS_TOTAL = 200;

    // Per-route maximum connections for default HTTP client, and all HTTP clients (individually). Does not apply to HTTP/2.
    private static final int MAX_CONNECTIONS_PER_ROUTE = 40;

    private static final boolean DEFAULT_COMPRESSION_ENABLED = true;
    private final Collection<Authentication> authenticationSettings;
    private final SslContextFactory sslContextFactory;
    private final HostnameVerifier hostnameVerifier;
    private final HttpRedirectVerifier redirectVerifier;
    private final int maxRedirects;
    private final int maxConnections;
    private final int maxPerRouteConnections;
    private final boolean compressionEnabled;
    private final boolean defaultSingleton;
    private final RedirectMethodHandlingStrategy redirectMethodHandlingStrategy;

    private HttpProxySettings proxySettings;
    private HttpProxySettings secureProxySettings;
    private HttpTimeoutSettings timeoutSettings;

    public static Builder builder() {
        return new Builder();
    }

    public static HttpSettings defaults() {
        return Builder.DEFAULTS;
    }

    private DefaultHttpSettings(
        Collection<Authentication> authenticationSettings,
        SslContextFactory sslContextFactory,
        HostnameVerifier hostnameVerifier,
        HttpRedirectVerifier redirectVerifier,
        RedirectMethodHandlingStrategy redirectMethodHandlingStrategy,
        int maxRedirects,
        int maxConnections,
        int maxPerRouteConnections,
        boolean isCompressionEnabled,
        boolean defaultSingleton
    ) {
        Preconditions.checkArgument(maxRedirects >= 0, "maxRedirects must be positive");
        Preconditions.checkNotNull(authenticationSettings, "authenticationSettings");
        Preconditions.checkNotNull(sslContextFactory, "sslContextFactory");
        Preconditions.checkNotNull(hostnameVerifier, "hostnameVerifier");
        Preconditions.checkNotNull(redirectVerifier, "redirectVerifier");
        Preconditions.checkNotNull(redirectMethodHandlingStrategy, "redirectMethodHandlingStrategy");

        this.maxRedirects = maxRedirects;
        this.maxConnections = maxConnections;
        this.maxPerRouteConnections = maxPerRouteConnections;
        this.authenticationSettings = authenticationSettings;
        this.sslContextFactory = sslContextFactory;
        this.hostnameVerifier = hostnameVerifier;
        this.redirectVerifier = redirectVerifier;
        this.redirectMethodHandlingStrategy = redirectMethodHandlingStrategy;
        this.compressionEnabled = isCompressionEnabled;
        this.defaultSingleton = defaultSingleton;
    }

    @Override
    public HttpProxySettings getProxySettings() {
        if (proxySettings == null) {
            proxySettings = new JavaSystemPropertiesHttpProxySettings();
        }
        return proxySettings;
    }

    @Override
    public HttpProxySettings getSecureProxySettings() {
        if (secureProxySettings == null) {
            secureProxySettings = new JavaSystemPropertiesSecureHttpProxySettings();
        }
        return secureProxySettings;
    }

    @Override
    public HttpTimeoutSettings getTimeoutSettings() {
        if (timeoutSettings == null) {
            timeoutSettings = new JavaSystemPropertiesHttpTimeoutSettings();
        }
        return timeoutSettings;
    }

    @Override
    public int getMaxRedirects() {
        return maxRedirects;
    }

    @Override
    public HttpRedirectVerifier getRedirectVerifier() {
        return redirectVerifier;
    }

    @Override
    public RedirectMethodHandlingStrategy getRedirectMethodHandlingStrategy() {
        return redirectMethodHandlingStrategy;
    }

    @Override
    public Collection<Authentication> getAuthenticationSettings() {
        return authenticationSettings;
    }

    @Override
    public SslContextFactory getSslContextFactory() {
        return sslContextFactory;
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    @Override
    public int getMaxConnections() {
        return maxConnections;
    }

    @Override
    public int getMaxPerRouteConnections() {
        return maxPerRouteConnections;
    }

    @Override
    public boolean isCompressionEnabled() {
        return compressionEnabled;
    }

    boolean isDefaultSingleton() {
        return this.defaultSingleton;
    }

    public static class Builder {
        private Collection<Authentication> authenticationSettings;
        private SslContextFactory sslContextFactory;
        private HostnameVerifier hostnameVerifier;
        private HttpRedirectVerifier redirectVerifier;
        private int maxRedirects = 10;
        private int maxConnections = MAX_CONNECTIONS_TOTAL;
        private int maxConnectionsPerRoute = MAX_CONNECTIONS_PER_ROUTE;  // `0` means unlimited
        private boolean compressionEnabled = DEFAULT_COMPRESSION_ENABLED;
        private boolean defaultSingleton = false;
        private static DefaultHttpSettings DEFAULTS = (DefaultHttpSettings) new Builder().indicateDefaultSingleton().build();
        private RedirectMethodHandlingStrategy redirectMethodHandlingStrategy = RedirectMethodHandlingStrategy.ALWAYS_FOLLOW_AND_PRESERVE;

        private Builder indicateDefaultSingleton() {
            this.defaultSingleton = true;
            return this;
        }

        public Builder withAuthenticationSettings(Collection<Authentication> authenticationSettings) {
            this.authenticationSettings = authenticationSettings;
            return this;
        }

        public Builder withSslContextFactory(SslContextFactory sslContextFactory) {
            this.sslContextFactory = sslContextFactory;
            this.hostnameVerifier = new DefaultHostnameVerifier(null);
            return this;
        }

        public Builder withRedirectVerifier(HttpRedirectVerifier redirectVerifier) {
            this.redirectVerifier = redirectVerifier;
            return this;
        }

        public Builder withCompression(boolean enableCompression) {
            this.compressionEnabled = enableCompression;
            return this;
        }

        public Builder allowUntrustedConnections() {
            this.sslContextFactory = ALL_TRUSTING_SSL_CONTEXT_FACTORY;
            this.hostnameVerifier = ALL_TRUSTING_HOSTNAME_VERIFIER;
            return this;
        }

        public Builder maxConnections(int maxConnections) {
            Preconditions.checkArgument(maxConnections >= 0);
            this.maxConnections = maxConnections;
            return this;
        }

        public Builder maxConnectionsPerRoute(int maxConnectionsPerRoute) {
            Preconditions.checkArgument(maxConnectionsPerRoute >= 0);
            this.maxConnectionsPerRoute = maxConnectionsPerRoute;
            return this;
        }

        public Builder maxRedirects(int maxRedirects) {
            Preconditions.checkArgument(maxRedirects >= 0);
            this.maxRedirects = maxRedirects;
            return this;
        }

        public Builder withRedirectMethodHandlingStrategy(RedirectMethodHandlingStrategy redirectMethodHandlingStrategy) {
            this.redirectMethodHandlingStrategy = redirectMethodHandlingStrategy;
            return this;
        }

        public HttpSettings build() {
            return new DefaultHttpSettings(
                authenticationSettings,
                sslContextFactory,
                hostnameVerifier,
                redirectVerifier,
                redirectMethodHandlingStrategy,
                maxRedirects,
                maxConnections,
                maxConnectionsPerRoute,
                compressionEnabled,
                defaultSingleton
            );
        }
    }

    private static final HostnameVerifier ALL_TRUSTING_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private static final SslContextFactory ALL_TRUSTING_SSL_CONTEXT_FACTORY = new SslContextFactory() {
        private final Supplier<SSLContext> sslContextSupplier = Suppliers.memoize(new Supplier<SSLContext>() {
            @Override
            public SSLContext get() {
                try {
                    SSLContext sslcontext = SSLContext.getInstance("TLS");
                    sslcontext.init(null, allTrustingTrustManager, null);
                    return sslcontext;
                } catch (GeneralSecurityException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        });

        @Override
        public SSLContext createSslContext() {
            return sslContextSupplier.get();
        }

        private final TrustManager[] allTrustingTrustManager = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                @Override
                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            }
        };
    };

}
