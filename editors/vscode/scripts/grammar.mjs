// Extracts crml.g4 from the crml-language package the language server is
// built with (gradle.properties: crmlVersion) into server/build/grammar, so
// that the highlighting lexer comes from the same grammar as the server's.
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const here = (path) => fileURLToPath(new URL(path, import.meta.url));

execFileSync(here(process.platform === "win32" ? "../../../gradlew.bat" : "../../../gradlew"),
    ["-q", "-p", here("../../../"), ":server:grammar"],
    { stdio: "inherit", shell: process.platform === "win32" });
