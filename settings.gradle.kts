rootProject.name = "elide-gradle"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include("elide-gradle-plugin")
include("elide-gradle-catalog")

includeBuild("example-project")
