import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import {
    modelicaFileName, needsRuntimeLibrary, outcomeMessage, outputFolder, TRANSLATE_COMMAND, TranslateResult,
} from "../src/translation.js";

const manifest = JSON.parse(readFileSync(join(__dirname, "..", "package.json"), "utf8"));

test("names the Modelica file after the CRML file, as the compiler's command line does", () => {
    assert.equal(modelicaFileName("/work/req/Speed.crml"), "Speed.mo");
    assert.equal(modelicaFileName("/work/req/Speed.CRML"), "Speed.mo");
    assert.equal(modelicaFileName("Speed"), "Speed.mo");
});

test("reads crml.translation.outputFolder", () => {
    assert.deepEqual(outputFolder(""), { kind: "besideSource" });
    assert.deepEqual(outputFolder(undefined), { kind: "besideSource" });
    assert.deepEqual(outputFolder("  "), { kind: "besideSource" });
    assert.deepEqual(outputFolder("generated/modelica"), { kind: "relative", path: "generated/modelica" });
    assert.deepEqual(outputFolder("/tmp/out"), { kind: "absolute", path: "/tmp/out" });
    assert.deepEqual(outputFolder("C:\\out"), { kind: "absolute", path: "C:\\out" });
    assert.deepEqual(outputFolder("c:/out"), { kind: "absolute", path: "c:/out" });
});

test("writes CRMLtoModelica.mo when it is missing or another version", () => {
    assert.equal(needsRuntimeLibrary(undefined, "package CRMLtoModelica\nend CRMLtoModelica;\n"), true);
    assert.equal(needsRuntimeLibrary("package CRMLtoModelica\nend CRMLtoModelica;\n", "package CRMLtoModelica\nend CRMLtoModelica;\n"), false);
    assert.equal(needsRuntimeLibrary("package CRMLtoModelica\r\nend CRMLtoModelica;\r\n", "package CRMLtoModelica\nend CRMLtoModelica;\n"), false,
        "line endings a checkout changed do not count");
    assert.equal(needsRuntimeLibrary("package CRMLtoModelica\n  // older\nend CRMLtoModelica;\n", "package CRMLtoModelica\nend CRMLtoModelica;\n"), true);
});

test("warns only when elements of the model itself were not translated", () => {
    const result: TranslateResult = { translated: true, modelName: "Speed", errors: 0, warnings: 0, errorsElsewhere: 0 };
    assert.deepEqual(outcomeMessage("Speed.mo", result), { warning: false, text: "Speed.mo written" });
    assert.deepEqual(outcomeMessage("Speed.mo", { ...result, errorsElsewhere: 4 }),
        { warning: false, text: "Speed.mo written (4 library elements not translated)" });
    assert.deepEqual(outcomeMessage("Speed.mo", { ...result, warnings: 1, errorsElsewhere: 1 }),
        { warning: false, text: "Speed.mo written (1 warning, 1 library element not translated)" });
    assert.deepEqual(outcomeMessage("Speed.mo", { ...result, errors: 2 }),
        { warning: true, text: "Speed.mo written, but 2 elements of Speed could not be translated; see Problems." });
});

test("offers the translation in the editor title, the context menus and the command palette", () => {
    const command = manifest.contributes.commands.find((c: { command: string }) => c.command === TRANSLATE_COMMAND);
    assert.ok(command, "the command is declared");
    assert.equal(command.icon, "$(play)", "the play button of the editor title");
    const menus = manifest.contributes.menus;
    assert.deepEqual(menus["editor/title/run"], [{ command: TRANSLATE_COMMAND, when: "resourceLangId == crml", group: "navigation" }]);
    assert.equal(menus["explorer/context"][0].when, "resourceExtname == .crml");
    assert.equal(menus["editor/context"][0].when, "resourceLangId == crml");
    assert.equal(menus["commandPalette"][0].when, "editorLangId == crml");
    for (const setting of ["crml.translation.outputFolder", "crml.translation.within"]) {
        assert.equal(manifest.contributes.configuration.properties[setting].default, "", setting);
    }
});
