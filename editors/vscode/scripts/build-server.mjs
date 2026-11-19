// Builds the language server jar with Gradle and copies it to server/, where
// the extension, and later the packaged extension, looks for it.
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";

const here = (path) => fileURLToPath(new URL(path, import.meta.url));

execFileSync(here("../../../gradlew"), ["-q", "-p", here("../../../"), ":server:shadowJar"], { stdio: "inherit" });
mkdirSync(here("../server"), { recursive: true });
copyFileSync(here("../../../server/build/libs/crml-language-server.jar"), here("../server/crml-language-server.jar"));
