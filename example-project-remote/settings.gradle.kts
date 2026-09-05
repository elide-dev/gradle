plugins {
    id("dev.elide.settings") version "1.1.0"
}

elide {
    runtime {
        mode = dev.elide.gradle.ElideRuntimeMode.MANAGED
        version = "1.5.1+20260903"
    }
}
