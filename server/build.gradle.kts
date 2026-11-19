plugins {
    id("application")
    id("com.gradleup.shadow") version "9.2.0"
}

val crmlVersion: String by project

dependencies {
    implementation("org.openmodelica.crml:crml-language:$crmlVersion")
    implementation("org.openmodelica.crml:crml-model:$crmlVersion")
    // Parsing for the editor uses the generated lexer and parser directly. The
    // version must be the one crml-language was generated with: the parser
    // checks it on load.
    implementation("org.antlr:antlr4-runtime:4.9.2")

    // LSP4J 1.0 needs Java 11; see the release setting below.
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")

    testImplementation(platform("org.junit:junit-bom:5.10.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The CRML compiler itself runs on Java 8; the language server needs Java 11+,
// because LSP4J 1.0 does. Any JDK that runs Gradle (17+) can build it.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

application {
    mainClass.set("org.openmodelica.crml.lsp.Main")
}

tasks.withType<Jar>().configureEach {
    metaInf {
        from(rootProject.file("OSMC-License.txt"))
        rename("OSMC-License.txt", "LICENSE")
    }
}

tasks.shadowJar {
    archiveBaseName.set("crml-language-server")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
    // LifecycleTest starts the shadow jar as a separate process.
    dependsOn(tasks.shadowJar)
    systemProperty("crml.lsp.jar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// The CRML grammar, crml.g4, from the sources of the crml-language package the
// server is built with, into build/grammar. The VS Code extension generates its
// highlighting lexer from it, so both lexers come from the same grammar.
val grammarSources by configurations.creating {
    isTransitive = false
}

dependencies {
    grammarSources("org.openmodelica.crml:crml-language:$crmlVersion:sources")
}

tasks.register<Sync>("grammar") {
    group = "build"
    description = "Extracts crml.g4 of crml-language $crmlVersion into build/grammar."
    from(grammarSources.elements.map { files -> files.map { zipTree(it) } }) {
        include("**/crml.g4")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("grammar"))
}
