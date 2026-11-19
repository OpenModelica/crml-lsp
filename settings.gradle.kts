rootProject.name = "crml-lsp"

include("server")

// The CRML compiler (crml-language, crml-model, ...) comes from the GitHub
// Packages Maven repository of PELAB-LiU/CRML, which needs a token with
// read:packages even to read public packages: the gpr.user and gpr.key
// properties (e.g. in ~/.gradle/gradle.properties), or else the GITHUB_ACTOR
// and GITHUB_TOKEN environment variables. Only org.openmodelica.crml is looked
// up there; everything else comes from Maven Central.
val gprUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR"))
val gprKey = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN"))

if (!gprUser.isPresent || !gprKey.isPresent) {
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
