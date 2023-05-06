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

import com.google.common.collect.Lists;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.async.HttpAsyncClient;
import org.apache.hc.client5.http.auth.AuthExchange;
import org.apache.hc.client5.http.auth.AuthScheme;
import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.CredentialsStore;
import org.apache.hc.client5.http.auth.NTCredentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.auth.BasicScheme;
import org.apache.hc.client5.http.impl.auth.BasicSchemeFactory;
import org.apache.hc.client5.http.impl.auth.DigestSchemeFactory;
import org.apache.hc.client5.http.impl.auth.SystemDefaultCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.routing.SystemDefaultRoutePlanner;
import org.apache.hc.client5.http.nio.AsyncClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.protocol.RedirectStrategy;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.HttpsSupport;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.config.H2Config;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.reactor.ssl.SSLBufferMode;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.gradle.api.JavaVersion;
import org.gradle.api.credentials.HttpHeaderCredentials;
import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.authentication.Authentication;
import org.gradle.authentication.http.BasicAuthentication;
import org.gradle.authentication.http.DigestAuthentication;
import org.gradle.authentication.http.HttpHeaderAuthentication;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.authentication.AllSchemesAuthentication;
import org.gradle.internal.authentication.AuthenticationInternal;
import org.gradle.internal.jvm.Jvm;
import org.gradle.internal.resource.UriTextResource;
import org.gradle.internal.resource.transport.http2.ntlm.NTLMCredentials;
import org.gradle.util.internal.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class HttpClientConfigurer {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpClientConfigurer.class);
    private static final String HTTPS_PROTOCOLS = "https.protocols";
    private static final String AUTH_SCHEME_BASIC = "basic";
    private static final String AUTH_SCHEME_DIGEST = "digest";
    private static final String AUTH_SCHEME_ANY = "any";
    private static final AtomicBoolean connectionManagerInitialized = new AtomicBoolean(false);
    private static AsyncClientConnectionManager DEFAULT_CONNECTION_MANAGER;
    private static final AtomicBoolean clientInitialized = new AtomicBoolean(false);
    private static CloseableHttpAsyncClient DEFAULT_CLIENT;

    // Prioritize ECDHE exchange, then 128-bit encryption via GCM, falling back to CHACHA or 256-bit.
    private static final String[] PREFERRED_CIPHERS_TLS13 = new String[]{
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_AES_128_GCM_SHA256",
        "TLS_CHACHA20_POLY1305_SHA256"
    };

    /**
     * Determines the HTTPS protocols to support for the client.
     *
     * @implNote To support the Gradle embedded test runner, this method's return value should not be cached in a static field.
     */
    private static String[] determineHttpsProtocols() {
        /*
         * System property retrieval is executed within the constructor to support the Gradle embedded test runner.
         */
        String httpsProtocols = System.getProperty(HTTPS_PROTOCOLS);
        if (httpsProtocols != null) {
            return httpsProtocols.split(",");
        } else if (JavaVersion.current().isJava8() && Jvm.current().isIbmJvm()) {
            return new String[]{"TLSv1.2"};
        } else if (jdkSupportsTLSProtocol("TLSv1.3")) {
            return new String[]{"TLSv1.2", "TLSv1.3"};
        } else {
            return new String[]{"TLSv1.2"};
        }
    }

    /**
     * Determines supported HTTPS ciphers based on the supported TLS protocol versions.
     *
     * @return `null` if the default cipher suites should be used, otherwise an array of cipher names.
     */
    private static String[] determineSupportedCiphers(String[] protocolVersions) {
        if (protocolVersions.length > 0) {
            String last = protocolVersions[protocolVersions.length - 1];
            if ("TLSv1.3".equals(last)) {
                return PREFERRED_CIPHERS_TLS13;
            }
        }
        return null;
    }

    private static boolean jdkSupportsTLSProtocol(@SuppressWarnings("SameParameterValue") final String protocol) {
        try {
            for (String supportedProtocol : SSLContext.getDefault().getSupportedSSLParameters().getProtocols()) {
                if (protocol.equals(supportedProtocol)) {
                    return true;
                }
            }
            return false;
        } catch (NoSuchAlgorithmException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    static Collection<String> supportedTlsVersions() {
        return Arrays.asList(determineHttpsProtocols());
    }

    private final String[] sslProtocols;
    private final String[] sslCiphers;
    private final HttpSettings httpSettings;

    public HttpClientConfigurer(HttpSettings httpSettings) {
        this.sslProtocols = determineHttpsProtocols();
        this.sslCiphers = determineSupportedCiphers(this.sslProtocols);
        this.httpSettings = httpSettings;
    }

    private static synchronized void initializeDefaults(HttpSettings httpSettings) {
        HttpClientConfigurer config = new HttpClientConfigurer(httpSettings);
        HttpAsyncClientBuilder builder = HttpAsyncClientBuilder.create();
        config.configure(builder);
        DEFAULT_CLIENT = builder.build();
        DEFAULT_CLIENT.start();
    }

    public static HttpAsyncClient obtainClient(HttpSettings httpSettings) {
        if (!clientInitialized.get()) {
            clientInitialized.compareAndSet(false, true);
            initializeDefaults(httpSettings);
        }
        return DEFAULT_CLIENT;
    }

    public void configure(HttpAsyncClientBuilder builder) {
        configure(
            new SystemDefaultRoutePlanner(ProxySelector.getDefault()),
            configureConnectionManager(),
            builder
        );
    }

    public AsyncClientConnectionManager configureConnectionManager() {
        if (!connectionManagerInitialized.get()) {
            connectionManagerInitialized.compareAndSet(false, true);
            final SslContextFactory factory = httpSettings.getSslContextFactory();
            final SSLContext sslContext;
            if (factory == null) {
                sslContext = SSLContexts.createDefault();
            } else {
                sslContext = factory.createSslContext();
            }

            HostnameVerifier verifier = httpSettings.getHostnameVerifier();
            if (verifier == null) {
                verifier = HttpsSupport.getDefaultHostnameVerifier();
            }

            PoolingAsyncClientConnectionManager manager = new PoolingAsyncClientConnectionManager(
                RegistryBuilder.<TlsStrategy>create()
                    .register(URIScheme.HTTPS.getId(), new DefaultClientTlsStrategy(
                        sslContext,
                        sslProtocols,
                        sslCiphers,
                        SSLBufferMode.STATIC,
                        verifier
                    ))
                    .build(),
                PoolConcurrencyPolicy.LAX,
                PoolReusePolicy.FIFO,
                TimeValue.of(Duration.ofMillis(httpSettings.getTimeoutSettings().getIdleConnectionTimeoutMs())),
                DefaultSchemePortResolver.INSTANCE,
                SystemDefaultDnsResolver.INSTANCE
            );

            int maxPerRoute = httpSettings.getMaxPerRouteConnections();
            int maxTotal = httpSettings.getMaxConnections();
            if (maxPerRoute > 0) {
                manager.setDefaultMaxPerRoute(maxPerRoute);
            }
            if (maxTotal > 0) {
                manager.setMaxTotal(maxTotal);
            }
            DEFAULT_CONNECTION_MANAGER = manager;
        }
        return DEFAULT_CONNECTION_MANAGER;
    }

    public void configure(HttpRoutePlanner planner, AsyncClientConnectionManager connectionManager, HttpAsyncClientBuilder builder) {
        SystemDefaultCredentialsProvider credentialsProvider = new SystemDefaultCredentialsProvider();
        configureConnectionManager(connectionManager, builder);
        configureSslSocketConnectionFactory(builder, httpSettings.getSslContextFactory(), httpSettings.getHostnameVerifier());
        configureAuthSchemeRegistry(builder);
        configureCredentials(builder, credentialsProvider, httpSettings.getAuthenticationSettings());
        configureProxy(planner, builder, credentialsProvider, httpSettings);
        configureUserAgent(builder);
        configureCookieSpecRegistry(builder);
        configureProtocolConfig(builder);
        configureCompression(httpSettings, builder);
        configureRequestConfig(builder);
        configureSocketConfig(builder);
        configureRedirectStrategy(planner, builder);
        builder.setDefaultCredentialsProvider(credentialsProvider);
    }

    private static void configureConnectionManager(AsyncClientConnectionManager manager, HttpAsyncClientBuilder builder) {
        builder.setConnectionManager(manager);
        builder.setConnectionManagerShared(true);
    }

    private static void configureSslSocketConnectionFactory(HttpAsyncClientBuilder builder, SslContextFactory sslContextFactory, HostnameVerifier hostnameVerifier) {
        // @TODO(sgammon): SSL socket factory support
//        builder.setSSLSocketFactory(new SSLConnectionSocketFactory(sslContextFactory.createSslContext(), sslProtocols, null, hostnameVerifier));
    }

    private static void configureCompression(HttpSettings settings, HttpAsyncClientBuilder builder) {
        if (settings.isCompressionEnabled()) {
            String[] acceptedEncodings = settings.getAcceptedEncodings();
            Supplier<HttpCustomCompressionHandler> customCompressionHandlerSupplier = settings.getCustomCompressionHandler();

            HttpContentCompressionMediator mediator;
            if (acceptedEncodings != null) {
                 HttpContentCompressionMediator.Builder mediatorBuilder = new HttpContentCompressionMediator.Builder();
                 mediatorBuilder.encodings(acceptedEncodings);
                 if (customCompressionHandlerSupplier != null) {
                     mediatorBuilder.compressionHandlerSupplier(customCompressionHandlerSupplier);
                 }
                 mediator = mediatorBuilder.build();
            } else {
                mediator = HttpContentCompressionMediator.defaults();
            }
            builder.addRequestInterceptorLast(mediator);
            builder.addResponseInterceptorLast(mediator);
        }
    }

    private static void configureAuthSchemeRegistry(HttpAsyncClientBuilder builder) {
        // @TODO(sgammon): additional schemes
        builder.setDefaultAuthSchemeRegistry(RegistryBuilder.<AuthSchemeFactory>create()
            .register(AUTH_SCHEME_BASIC, new BasicSchemeFactory())
            .register(AUTH_SCHEME_DIGEST, new DigestSchemeFactory())
            .register(HttpHeaderAuthScheme.AUTH_SCHEME_NAME, new HttpHeaderSchemeFactory())
            .build()
        );
//        builder.setDefaultAuthSchemeRegistry(RegistryBuilder.<AuthSchemeProvider>create()
//            .register(AuthSchemes.BASIC, new BasicSchemeFactory())
//            .register(AuthSchemes.DIGEST, new DigestSchemeFactory())
//            .register(AuthSchemes.NTLM, new NTLMSchemeFactory())
//            .register(AuthSchemes.SPNEGO, new SPNegoSchemeFactory())
//            .register(AuthSchemes.KERBEROS, new KerberosSchemeFactory())
//            .register(HttpHeaderAuthScheme.AUTH_SCHEME_NAME, new HttpHeaderSchemeFactory())
//            .build()
//        );
    }

    private static void configureCredentials(HttpAsyncClientBuilder builder, CredentialsStore credentialsProvider, Collection<Authentication> authentications) {
        if (authentications != null && authentications.size() > 0) {
            useCredentials(credentialsProvider, authentications);

            // Use preemptive authorisation if no other authorisation has been established
            builder.addRequestInterceptorFirst(
                new PreemptiveAuth(getAuthScheme(authentications), isPreemptiveEnabled(authentications))
            );
        }
    }

    private static AuthScheme getAuthScheme(final Collection<Authentication> authentications) {
        if (authentications.size() == 1) {
            if (authentications.iterator().next() instanceof HttpHeaderAuthentication) {
                return new HttpHeaderAuthScheme();
            }
        }
        return new BasicScheme();
    }

    private static void configureProxy(HttpRoutePlanner planner, HttpAsyncClientBuilder builder, CredentialsStore credentialsProvider, HttpSettings httpSettings) {
        HttpProxySettings.HttpProxy httpProxy = httpSettings.getProxySettings().getProxy();
        HttpProxySettings.HttpProxy httpsProxy = httpSettings.getSecureProxySettings().getProxy();

        for (HttpProxySettings.HttpProxy proxy : Lists.newArrayList(httpProxy, httpsProxy)) {
            if (proxy != null) {
                if (proxy.credentials != null) {
                    AllSchemesAuthentication authentication = new AllSchemesAuthentication(proxy.credentials);
                    authentication.addHost(proxy.host, proxy.port);
                    useCredentials(credentialsProvider, Collections.singleton(authentication));
                }
            }
        }
        builder.setRoutePlanner(planner);
    }

    private static void useCredentials(CredentialsStore credentialsProvider, Collection<? extends Authentication> authentications) {
        for (Authentication authentication : authentications) {
            AuthenticationInternal authenticationInternal = (AuthenticationInternal) authentication;

            AuthScheme scheme = getAuthScheme(Collections.singletonList(authentication));
            org.gradle.api.credentials.Credentials credentials = authenticationInternal.getCredentials();

            Collection<AuthenticationInternal.HostAndPort> hostsForAuthentication = authenticationInternal.getHostsForAuthentication();
            assert !hostsForAuthentication.isEmpty() : "Credentials and authentication required for a HTTP repository, but no hosts were defined for the authentication?";

            for (AuthenticationInternal.HostAndPort hostAndPort : hostsForAuthentication) {
                String host = hostAndPort.getHost();
                int port = hostAndPort.getPort();

                assert host != null : "HTTP credentials and authentication require a host scope to be defined as well";

                if (credentials instanceof HttpHeaderCredentials) {
                    HttpHeaderCredentials httpHeaderCredentials = (HttpHeaderCredentials) credentials;
                    Credentials httpCredentials = new HttpClientHttpHeaderCredentials(httpHeaderCredentials.getName(), httpHeaderCredentials.getValue());
                    credentialsProvider.setCredentials(new AuthScope(null, host, port, null, scheme.getName()), httpCredentials);

                    LOGGER.debug("Using {} for authenticating against '{}:{}' using {}", httpHeaderCredentials, host, port, scheme);
                } else if (credentials instanceof PasswordCredentials) {
                    PasswordCredentials passwordCredentials = (PasswordCredentials) credentials;

                    if (authentication instanceof AllSchemesAuthentication) {
                        NTLMCredentials ntlmCredentials = new NTLMCredentials(passwordCredentials);
                        Credentials httpCredentials = new NTCredentials(ntlmCredentials.getUsername(), ntlmCredentials.getPassword().toCharArray(), ntlmCredentials.getWorkstation(), ntlmCredentials.getDomain());
                        credentialsProvider.setCredentials(new AuthScope(null, host, port, null, scheme.getName()), httpCredentials);

                        LOGGER.debug("Using {} and {} for authenticating against '{}:{}' using {}", passwordCredentials, ntlmCredentials, host, port, scheme.getName());
                    }

                    String username = passwordCredentials.getUsername();
                    String password = passwordCredentials.getPassword();
                    if (username == null) {
                        username = "";
                    }
                    if (password == null) {
                        password = "";
                    }

                    Credentials httpCredentials = new UsernamePasswordCredentials(username, password.toCharArray());
                    credentialsProvider.setCredentials(new AuthScope(null, host, port, scheme.getRealm(), scheme.getName()), httpCredentials);
                    LOGGER.debug("Using {} for authenticating against '{}:{}' using {}", passwordCredentials, host, port, scheme);
                } else {
                    throw new IllegalArgumentException(String.format("Credentials must be an instance of: %s or %s", PasswordCredentials.class.getCanonicalName(), HttpHeaderCredentials.class.getCanonicalName()));
                }
            }
        }
    }

    private static boolean isPreemptiveEnabled(Collection<Authentication> authentications) {
        return CollectionUtils.any(authentications, element ->
            element instanceof BasicAuthentication || element instanceof HttpHeaderAuthentication
        );
    }

    public static void configureUserAgent(HttpAsyncClientBuilder builder) {
        builder.setUserAgent(UriTextResource.getUserAgentString());
    }

    private static void configureCookieSpecRegistry(HttpAsyncClientBuilder builder) {
//        PublicSuffixMatcher publicSuffixMatcher = PublicSuffixMatcherLoader.getDefault();
        // @TODO(sgammon): public suffix matcher
//        builder.setPublicSuffixMatcher(publicSuffixMatcher);
        // Add more data patterns to the default configuration to work around https://github.com/gradle/gradle/issues/1596
        // @TODO(sgammon): cookies
//        final CookieSpecProvider defaultProvider = new DefaultCookieSpecProvider(DefaultCookieSpecProvider.CompatibilityLevel.DEFAULT, publicSuffixMatcher, new String[]{
//            "EEE, dd-MMM-yy HH:mm:ss z", // Netscape expires pattern
//            DateUtils.PATTERN_RFC1036,
//            DateUtils.PATTERN_ASCTIME,
//            DateUtils.PATTERN_RFC1123
//        }, false);
//        final CookieSpecProvider laxStandardProvider = new RFC6265CookieSpecProvider(
//            RFC6265CookieSpecProvider.CompatibilityLevel.RELAXED, publicSuffixMatcher);
//        final CookieSpecProvider strictStandardProvider = new RFC6265CookieSpecProvider(
//            RFC6265CookieSpecProvider.CompatibilityLevel.STRICT, publicSuffixMatcher);
//        builder.setDefaultCookieSpecRegistry(RegistryBuilder.<CookieSpecProvider>create()
//            .register(CookieSpecs.DEFAULT, defaultProvider)
//            .register("best-match", defaultProvider)
//            .register("compatibility", defaultProvider)
//            .register(CookieSpecs.STANDARD, laxStandardProvider)
//            .register(CookieSpecs.STANDARD_STRICT, strictStandardProvider)
//            .register(CookieSpecs.NETSCAPE, new NetscapeDraftSpecProvider())
//            .register(CookieSpecs.IGNORE_COOKIES, new IgnoreSpecProvider())
//            .build()
//        );
    }

    @SuppressWarnings("deprecation")
    private void configureRequestConfig(HttpAsyncClientBuilder builder) {
        HttpTimeoutSettings timeoutSettings = httpSettings.getTimeoutSettings();
        RequestConfig config = RequestConfig.custom()
            .setConnectTimeout(Timeout.of(Duration.ofMillis(timeoutSettings.getConnectionTimeoutMs())))
            .setResponseTimeout(Timeout.of(Duration.ofMillis(timeoutSettings.getSocketTimeoutMs())))
            .setConnectionRequestTimeout(Timeout.of(Duration.ofMillis(timeoutSettings.getSocketTimeoutMs())))
            .setRedirectsEnabled(true)
            .setMaxRedirects(httpSettings.getMaxRedirects())
            .build();

        builder.setDefaultRequestConfig(config);
    }

    private static void configureProtocolConfig(HttpAsyncClientBuilder builder) {
        // HTTP/1.1 protocol configuration
        builder.setHttp1Config(Http1Config.copy(Http1Config.DEFAULT)
                .build());

        // HTTP/2 protocol configuration
        builder.setH2Config(H2Config.copy(H2Config.DEFAULT)
                .setPushEnabled(false)
                .setCompressionEnabled(true)  // HPACK compression (headers)
                .build());
    }

    // @TODO(sgammon): default socket config
    private void configureSocketConfig(HttpAsyncClientBuilder builder) {
//        HttpTimeoutSettings timeoutSettings = httpSettings.getTimeoutSettings();
//        builder.setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(timeoutSettings.getSocketTimeoutMs()).setSoKeepAlive(true).build());
    }

    private void configureRedirectStrategy(HttpRoutePlanner planner, HttpAsyncClientBuilder builder) {
        if (httpSettings.getMaxRedirects() > 0) {
            RedirectStrategy strategy = new RedirectVerifyingStrategyDecorator(getBaseRedirectStrategy(), httpSettings.getRedirectVerifier());
            builder.setRedirectStrategy(strategy);
            builder.replaceExecInterceptor(ChainElement.REDIRECT.name(), new PreservingAsyncRedirectExec(planner, strategy));
        } else {
            builder.disableRedirectHandling();
        }
    }

    private RedirectStrategy getBaseRedirectStrategy() {
        switch (httpSettings.getRedirectMethodHandlingStrategy()) {
            case ALLOW_FOLLOW_FOR_MUTATIONS:
                return new AllowFollowForMutatingMethodRedirectStrategy();
            case ALWAYS_FOLLOW_AND_PRESERVE:
                return new AlwaysFollowAndPreserveMethodRedirectStrategy();
            default:
                throw new IllegalArgumentException(httpSettings.getRedirectMethodHandlingStrategy().name());
        }
    }

    private static String getAuthScheme(Authentication authentication) {
        if (authentication instanceof BasicAuthentication) {
            return AUTH_SCHEME_BASIC;
        } else if (authentication instanceof DigestAuthentication) {
            return AUTH_SCHEME_DIGEST;
        } else if (authentication instanceof HttpHeaderAuthentication) {
            return HttpHeaderAuthScheme.AUTH_SCHEME_NAME;
        } else if (authentication instanceof AllSchemesAuthentication) {
            return AUTH_SCHEME_ANY;
        } else {
            throw new IllegalArgumentException(String.format("Authentication scheme of '%s' is not supported.", authentication.getClass().getSimpleName()));
        }
    }

    static class PreemptiveAuth implements HttpRequestInterceptor {
        private final AuthScheme authScheme;
        private final boolean alwaysSendAuth;

        PreemptiveAuth(AuthScheme authScheme, boolean alwaysSendAuth) {
            this.authScheme = authScheme;
            this.alwaysSendAuth = alwaysSendAuth;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void process(HttpRequest request, EntityDetails entity, HttpContext context) throws HttpException, IOException {
            Object authState = context.getAttribute(HttpClientContext.AUTH_EXCHANGE_MAP);
            if (!(authState instanceof Map)) {
                return;
            }
            Map<HttpHost, AuthExchange> authExchangeMap = (Map<HttpHost, AuthExchange>) authState;
            if (!authExchangeMap.isEmpty()) {
                AuthExchange authExchange = authExchangeMap.values().iterator().next();
                if (authExchange.getAuthScheme() != null) {
                    return;
                }
            }

            // If no authState has been established and this is a PUT or POST request, add preemptive authorisation
            String method = request.getMethod();
            if (alwaysSendAuth || method.equals(Method.PUT.name()) || method.equals(Method.POST.name())) {
                CredentialsProvider credentialsProvider = (CredentialsProvider) context.getAttribute(HttpClientContext.CREDS_PROVIDER);
                HttpHost targetHost;
                try {
                    targetHost = HttpHost.create(request.getUri());
                } catch (URISyntaxException uriSyntaxException) {
                    throw new HttpException("Invalid URI", uriSyntaxException);
                }
                Credentials credentials = credentialsProvider.getCredentials(
                    new AuthScope(targetHost.getHostName(), targetHost.getPort()),
                    context
                );
                if (credentials != null) {
                    AuthExchange exchange = new AuthExchange();
                    exchange.setState(AuthExchange.State.SUCCESS);
                    exchange.select(authScheme);
                    authExchangeMap.put(targetHost, exchange);
                }
            }
        }

        //        @Override
//        public void process(HttpRequest request, EntityDetails entity, HttpContext context) throws HttpException, IOException {
//            AuthState authState = (AuthState) context.getAttribute(HttpClientContext.TARGET_AUTH_STATE);
//
//            if (authState.getAuthScheme() != null || authState.hasAuthOptions()) {
//                return;
//            }
//
//            // If no authState has been established and this is a PUT or POST request, add preemptive authorisation
//            String requestMethod = new RequestLine(request).getMethod();
//            if (alwaysSendAuth || requestMethod.equals(HttpPut.METHOD_NAME) || requestMethod.equals(HttpPost.METHOD_NAME)) {
//                CredentialsProvider credentialsProvider = (CredentialsProvider) context.getAttribute(HttpClientContext.CREDS_PROVIDER);
//                HttpHost targetHost = (HttpHost) context.getAttribute(HttpCoreContext.HTTP_TARGET_HOST);
//                Credentials credentials = credentialsProvider.getCredentials(new AuthScope(targetHost.getHostName(), targetHost.getPort()));
//                if (credentials != null) {
//                    authState.update(authScheme, credentials);
//                }
//            }
//        }
    }

}
