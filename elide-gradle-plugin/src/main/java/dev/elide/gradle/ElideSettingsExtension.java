package dev.elide.gradle;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;

import javax.inject.Inject;

/** Root DSL exposed by the Elide settings plugin. */
public class ElideSettingsExtension {
    private final ElideRuntimeSettings runtime;

    @Inject
    public ElideSettingsExtension(ObjectFactory objects) {
        runtime = objects.newInstance(ElideRuntimeSettings.class);
    }

    public ElideRuntimeSettings getRuntime() {
        return runtime;
    }

    public void runtime(Action<? super ElideRuntimeSettings> action) {
        action.execute(runtime);
    }
}
