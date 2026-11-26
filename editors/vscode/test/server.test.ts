import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { createMessageConnection, StreamMessageReader, StreamMessageWriter } from "vscode-jsonrpc/node";
import { findJava, javaCandidates } from "../src/java.js";
import { TRANSLATE_COMMAND, TRANSLATE_REQUEST, TranslateResult } from "../src/translation.js";

const EXTENSION = join(__dirname, "..");
const JAR = join(EXTENSION, "server/crml-language-server.jar"); // copied by npm run build:server

test("starts the server jar with the Java the extension would pick", { skip: !existsSync(JAR) && "no server jar; run npm run build:server" }, async () => {
    const java = await findJava(javaCandidates(undefined, EXTENSION, process.env));
    const server = spawn(java.executable, ["-jar", JAR], { stdio: ["pipe", "pipe", "inherit"] });
    const exited = new Promise<number | null>(resolve => server.on("exit", resolve));

    const connection = createMessageConnection(new StreamMessageReader(server.stdout), new StreamMessageWriter(server.stdin));
    connection.listen();
    const result = await connection.sendRequest<{ serverInfo: { name: string } }>("initialize",
        { processId: process.pid, rootUri: null, capabilities: {} });
    assert.equal(result.serverInfo.name, "CRML Language Server");
    await connection.sendNotification("initialized", {});
    await connection.sendRequest("shutdown");
    await connection.sendNotification("exit");

    assert.equal(await exited, 0);
    connection.dispose();
});

test("completes with the capabilities VS Code announces", { skip: !existsSync(JAR) && "no server jar; run npm run build:server" }, async () => {
    const java = await findJava(javaCandidates(undefined, EXTENSION, process.env));
    const server = spawn(java.executable, ["-jar", JAR], { stdio: ["pipe", "pipe", "inherit"] });
    const exited = new Promise<number | null>(resolve => server.on("exit", resolve));
    const connection = createMessageConnection(new StreamMessageReader(server.stdout), new StreamMessageWriter(server.stdin));
    connection.listen();

    const result = await connection.sendRequest<{ capabilities: { completionProvider: { resolveProvider: boolean, triggerCharacters?: string[] } } }>(
        "initialize", {
            processId: process.pid, rootUri: null, initializationOptions: { clientHighlighting: true },
            capabilities: { textDocument: { completion: { completionItem: {
                snippetSupport: true, labelDetailsSupport: true, documentationFormat: ["markdown", "plaintext"],
                resolveSupport: { properties: ["documentation"] },
            } } } },
        });
    assert.equal(result.capabilities.completionProvider.resolveProvider, true);
    assert.equal(result.capabilities.completionProvider.triggerCharacters, undefined, "completion is asked for with Ctrl+Space only");
    await connection.sendNotification("initialized", {});

    const uri = "file:///test/C.crml";
    // As typed in VS Code: the closing quote was inserted by the editor.
    const text = "model M is FORML union {\n  Boolean b is external;\n  Periods P is external;\n  Boolean r is P 'ens'\n};";
    await connection.sendNotification("textDocument/didOpen", { textDocument: { uri, languageId: "crml", version: 1, text } });
    type Item = { label: string, insertTextFormat: number, textEdit: { newText: string, range: unknown }, documentation?: { kind: string, value: string } };
    const list = await connection.sendRequest<{ items: Item[] }>("textDocument/completion",
        { textDocument: { uri }, position: { line: 3, character: 21 } });
    const ensure = list.items.find(item => item.label === "P 'ensure' b");
    assert.ok(ensure, "the operator from FORM-L is proposed");
    assert.equal(ensure.insertTextFormat, 2, "as a snippet");
    assert.equal(ensure.textEdit.newText, "'ensure' ${1:b}");
    assert.deepEqual(ensure.textEdit.range, { start: { line: 3, character: 17 }, end: { line: 3, character: 22 } });

    const resolved = await connection.sendRequest<Item>("completionItem/resolve", ensure);
    assert.equal(resolved.documentation?.kind, "markdown");
    assert.match(resolved.documentation?.value ?? "", /Operator \[ Boolean \] Periods P 'ensure' Boolean b/);

    await connection.sendRequest("shutdown");
    await connection.sendNotification("exit");
    assert.equal(await exited, 0);
    connection.dispose();
});

test("translates a model to Modelica with crml/translate, and offers it above the model", { skip: !existsSync(JAR) && "no server jar; run npm run build:server" }, async () => {
    const java = await findJava(javaCandidates(undefined, EXTENSION, process.env));
    const server = spawn(java.executable, ["-jar", JAR], { stdio: ["pipe", "pipe", "inherit"] });
    const exited = new Promise<number | null>(resolve => server.on("exit", resolve));
    const connection = createMessageConnection(new StreamMessageReader(server.stdout), new StreamMessageWriter(server.stdin));
    connection.listen();

    const result = await connection.sendRequest<{ capabilities: { codeLensProvider?: object } }>("initialize",
        { processId: process.pid, rootUri: null, capabilities: {} });
    assert.ok(result.capabilities.codeLensProvider, "code lenses are offered");
    await connection.sendNotification("initialized", {});

    const uri = "file:///test/Speed.crml";
    const text = "model Speed is ETL union {\n  Real v is external;\n  Boolean fast is v > 6.0;\n};";
    await connection.sendNotification("textDocument/didOpen", { textDocument: { uri, languageId: "crml", version: 1, text } });
    type Lens = { range: unknown, command: { title: string, command: string, arguments: unknown[] } };
    const lenses = await connection.sendRequest<Lens[]>("textDocument/codeLens", { textDocument: { uri } });
    assert.equal(lenses.length, 1);
    assert.equal(lenses[0].command.command, TRANSLATE_COMMAND, "the lens runs the extension's command");
    assert.deepEqual(lenses[0].command.arguments, [uri]);

    const translation = await connection.sendRequest<TranslateResult>(TRANSLATE_REQUEST, { uri, text, within: "" });
    assert.equal(translation.translated, true, translation.message);
    assert.match(translation.modelica ?? "", /^within ;\npartial model Speed\n/);
    assert.match(translation.runtimeLibrary ?? "", /^package CRMLtoModelica/);
    assert.equal(translation.errors, 0);

    const library = await connection.sendRequest<TranslateResult>(TRANSLATE_REQUEST,
        { uri: "file:///test/L.crml", text: "library L is {\n};", within: "" });
    assert.equal(library.translated, false);
    assert.equal(library.message, "L is a library: only a model can be translated to Modelica.");

    await connection.sendRequest("shutdown");
    await connection.sendNotification("exit");
    assert.equal(await exited, 0);
    connection.dispose();
});
