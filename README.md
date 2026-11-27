# [EXPERIMENTAL] CRML language server and VS Code extension

Editor support for CRML, the Common Requirement Modeling Language:

- `server/`: the CRML language server (Java 11+, LSP4J, stdio). It is built on
  the CRML compiler, used as the Maven packages `org.openmodelica.crml:crml-*`
  published by [PELAB-LiU/CRML](https://github.com/PELAB-LiU/CRML).
- `editors/vscode/`: the VS Code extension, with lexer-based highlighting and a
  client for the server. See its [README](editors/vscode/README.md) for the
  features.

## Requirements

- A JDK 17 or later to build (the server itself runs on Java 11+).
- Node.js 24 for the extension.
- Credentials for GitHub Packages, see below.

## Credentials for the CRML packages

GitHub Packages asks for a token even to read public packages, and its Maven
registry accepts only a **classic** personal access token.

1. GitHub → *Settings* → *Developer settings* → *Personal access tokens* →
   *Tokens (classic)* → *Generate new token*, with the `read:packages` scope.
   If the organization uses SAML single sign-on, authorize the token for it
   (*Configure SSO*).
2. Either put it in `~/.gradle/gradle.properties`:

   ```properties
   gpr.user=<your GitHub user name>
   gpr.key=<the token>
   ```

   or set the environment variables `GITHUB_ACTOR` and `GITHUB_TOKEN`.

Without them, Gradle warns at the start of every build and fails when it first
needs a CRML package.

In GitHub Actions, the workflow uses the secret `CRML_PACKAGES_TOKEN` if it is
set, and the run's own token otherwise; the latter can read the packages of
PELAB-LiU/CRML only while they are public.

## Building

```sh
./gradlew build                    # the server, its tests and server/build/libs/crml-language-server.jar

cd editors/vscode
npm ci
npm run build:server               # copies the server jar into the extension
npm test                           # generates the lexer, then runs the tests
npm run package                    # dist-vsix/crml-<version>.vsix, using the user's Java
npm run package -- --target linux-x64   # with a Java runtime bundled
```

The package is published as `openmodelica.crml`. To package it for another
publisher, for example your own, set `CRML_PUBLISHER`:
`CRML_PUBLISHER=<publisher> npm run package`.

To develop the extension, open the repository or `editors/vscode` in VS Code
and start *Run CRML extension* (F5).

## Building with a CRML checkout

To build against the CRML compiler's sources instead of its packages, for
example to try compiler changes in the editor, put a checkout of
PELAB-LiU/CRML in `CRML/` (a clone or a git submodule), or point the
`crmlDir` property at one:

```sh
./gradlew build -PcrmlDir=../CRML
export ORG_GRADLE_PROJECT_crmlDir=../CRML   # also for the npm scripts
```

Gradle then builds `crml-language` and `crml-model` from that checkout as a
composite build, the extension's lexer comes from its `crml.g4`, and no GitHub
Packages credentials are needed. Without a checkout, the packages of
`crmlVersion` are used, as described below.

## The CRML compiler version

`crmlVersion` in [gradle.properties](gradle.properties) is the version of the
CRML packages the server is built with. The highlighting lexer of the extension
is generated from `crml.g4` in the sources of that same `crml-language`
version (`./gradlew :server:grammar`, run by `npm run generate`), so the server
and the extension always read CRML the same way.

To move to a new compiler release, change `crmlVersion`, or try one first with
`./gradlew build -PcrmlVersion=<version>`. The compiler's releases are the
`maven-<version>` tags of PELAB-LiU/CRML; its branches publish snapshots such
as `0.0.2
-elephant-SNAPSHOT`.

## License

OSMC Public License 1.8; see [OSMC-License.txt](OSMC-License.txt). Third-party
components are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
