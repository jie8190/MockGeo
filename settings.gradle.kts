pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MockGeo"

include(":core:model")
include(":core:engine")
include(":core:route")
include(":core:data")
include(":app")
include(":module")
include(":probe")
