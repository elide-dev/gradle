/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.configurationcache.serialization.codecs

import org.gradle.configurationcache.serialization.BindingsBuilder
import org.gradle.configurationcache.serialization.Codec
import org.gradle.configurationcache.serialization.ReadContext
import org.gradle.configurationcache.serialization.WriteContext
import java.time.Duration


internal
fun BindingsBuilder.javaTimeTypes() {
    bind(DurationCodec)
}


private
object DurationCodec : Codec<Duration> {

    override suspend fun WriteContext.encode(value: Duration) {
        // Do not use the ISO-8601 format for serialization
        // to work around https://bugs.openjdk.java.net/browse/JDK-8054978
        // on Java 8
        writeLong(value.seconds)
        writeSmallInt(value.nano)
    }

    override suspend fun ReadContext.decode(): Duration =
        Duration.ofSeconds(readLong(), readSmallInt().toLong())
}
