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

import org.apache.hc.client5.http.protocol.RedirectStrategy;

import javax.annotation.Nullable;

/**
 * Extends the base {@link RedirectStrategy} with method-preserving and body-preserving indicators.
 *
 * @since 8.2
 * @author sgammon (sam@elide.dev)
 */
public interface GradleRedirectStrategy extends RedirectStrategy {
    /**
     * Indicate whether the provided HTTP method can be redirected according to this strategy.
     *
     * @param method Method name.
     * @return Whether the method can be redirected; `null` is returned to defer to default behavior.
     */
    default @Nullable Boolean isRedirectable(String method) {
        return null;
    }

    /**
     * Indicate whether the request method and status code pair should preserve the original method.
     *
     * @param method Method for the original request.
     * @param statusCode Status code for the response.
     * @return Whether the method should be preserved; `null` is returned to defer to default behavior.
     */
    default @Nullable Boolean isPreserveMethod(String method, int statusCode) {
        return null;
    }

    /**
     * Indicate whether the request method and status code pair should preserve the original body payload.
     *
     * @param method Method for the original request.
     * @param statusCode Status code for the response.
     * @return Whether the body payload should be preserved; `null` is returned to defer to default behavior.
     */
    default @Nullable Boolean isPreserveBody(String method, int statusCode) {
        return null;
    }
}
