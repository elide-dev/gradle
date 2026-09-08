pluginManagement {
    includeBuild("..")
}

plugins {
    id("dev.elide.settings")
}

elide {
    runtime {
        mode = dev.elide.gradle.ElideRuntimeMode.MANAGED
        version = "1.5.1+20260903"
    }
}
