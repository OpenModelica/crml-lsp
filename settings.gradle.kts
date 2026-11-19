// Plugins (shadow) from the Gradle Plugin Portal, their dependencies straight
// from Maven Central: the portal only redirects to Central for those, and a
// failed redirect shows up as "Could not find" for every one of them.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "crml-lsp"

include("server")

// A checkout of PELAB-LiU/CRML, if there is one, replaces the CRML packages:
// crml-language and crml-model are then built from its sources, as a
// composite build. It is looked for in CRML/ (e.g. a git submodule), or in the
// folder the crmlDir property names, relative to this one (-PcrmlDir=../CRML,
// or crmlDir in gradle.properties). Without it, the packages of version
// crmlVersion come from GitHub Packages, as below.
val crmlDir = file(providers.gradleProperty("crmlDir").getOrElse("CRML"))
val crmlCheckout = crmlDir.resolve("settings.gradle.kts").isFile
if (crmlCheckout) {
    logger.lifecycle("Building the CRML compiler from the checkout in $crmlDir")
    includeBuild(crmlDir) {
        dependencySubstitution {
            substitute(module("org.openmodelica.crml:crml-language")).using(project(":language"))
            substitute(module("org.openmodelica.crml:crml-model")).using(project(":model"))
        }
    }
}
// For server/build.gradle.kts, which takes crml.g4 from the checkout then.
gradle.extra["crmlCheckout"] = if (crmlCheckout) crmlDir else null

// The CRML compiler (crml-language, crml-model, ...) comes from the GitHub
// Packages Maven repository of PELAB-LiU/CRML, which needs a token with
// read:packages even to read public packages: the gpr.user and gpr.key
// properties (e.g. in ~/.gradle/gradle.properties), or else the GITHUB_ACTOR
// and GITHUB_TOKEN environment variables. Only org.openmodelica.crml is looked
// up there; everything else comes from Maven Central.
val gprUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR"))
val gprKey = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN"))

if (!crmlCheckout && (!gprUser.isPresent || !gprKey.isPresent)) {
    logger.warn(
        "No credentials for the CRML packages on GitHub Packages. Set gpr.user and gpr.key in " +
        "~/.gradle/gradle.properties, or GITHUB_ACTOR and GITHUB_TOKEN, with a classic token " +
        "that has read:packages; see README.md."
    )
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            name = "CRML"
            url = uri("https://maven.pkg.github.com/PELAB-LiU/CRML")
            credentials {
                username = gprUser.orNull
                password = gprKey.orNull
            }
            content {
                includeGroup("org.openmodelica.crml")
            }
        }
    }
}
