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

import org.apache.hc.core5.http.HttpResponse;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Base compression handler interface.
 */
public interface HttpCompressionHandler {
    /**
     * Name of the encoding to match or this handler; custom encodings should return `null`
     * to avoid selection.
     *
     * @return Name of the encoding, in lowercase (for example, `gzip`).
     */
    default @Nullable String name() {
        return null;
    }

    /**
     * Wrap the provided stream with a decompression stream, if necessary. If no decompression should be applied, the
     * stream should be returned directly.
     *
     * @param response HTTP response which might be compressed.
     * @param stream Response stream to wrap.
     * @return Input stream wrapped if needed.
     */
    InputStream decompress(HttpResponse response, InputStream stream) throws IOException;
}
