package dev.elide.gradle.internal

import org.gradle.api.provider.Provider

/**
 * Transforms the value of the provider lazily.
 *
 * Created to bypass java-interop issues with inability to pass null to an original map. We
 * work around it by avoiding the generation of kotlin's intrinsics on 'as' casts using generics that are by
 * specification are unchecked.
 */
internal fun <TIn : Any, TOut : Any> Provider<TIn>.mapNotNull(
    transform: (TIn) -> TOut?,
): Provider<TOut> {
    return map {
        // @ts-ignore ahhh moment
        transform(it) as TOut
    }
}