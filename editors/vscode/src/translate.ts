import * as vscode from "vscode";
import type { LanguageClient } from "vscode-languageclient/node";
import {
    modelicaFileName, needsRuntimeLibrary, outcomeMessage, outputFolder, RUNTIME_LIBRARY,
    TRANSLATE_COMMAND, TRANSLATE_REQUEST, TranslateParams, TranslateResult,
} from "./translation.js";

const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8");

/**
 * Registers the command that translates CRML models to Modelica: the play
 * button of the editor title, the editor and Explorer context menus, the
 * command palette and the code lens above a model all run it. The language
 * server translates, with the bundled libraries and the open documents in
 * scope; the command writes Name.mo and CRMLtoModelica.mo and opens Name.mo.
 */
export function registerTranslation(context: vscode.ExtensionContext, client: () => LanguageClient | undefined,
                                    output: vscode.LogOutputChannel): void {
    context.subscriptions.push(vscode.commands.registerCommand(TRANSLATE_COMMAND,
        async (target?: vscode.Uri | string, selection?: vscode.Uri[]) => {
            const server = client();
            if (!server) {
                vscode.window.showErrorMessage("The CRML language server is not running, so nothing can be translated. "
                    + "See the CRML Language Server output for why.");
                return;
            }
            const sources = targets(server, target, selection);
            if (sources.length === 0) {
                vscode.window.showInformationMessage("Open a CRML model, or select .crml files in the Explorer, to translate it to Modelica.");
                return;
            }
            await vscode.window.withProgress({ location: vscode.ProgressLocation.Window, title: "Translating to Modelica" }, async () => {
                for (const source of sources) {
                    await translate(server, source, sources.length === 1, output);
                }
            });
        }));
}

/** The .crml files to translate: the Explorer selection, the file clicked, or the active editor's. */
function targets(server: LanguageClient, target: vscode.Uri | string | undefined, selection: vscode.Uri[] | undefined): vscode.Uri[] {
    if (Array.isArray(selection) && selection.length > 0) {
        return selection.filter(uri => uri.path.toLowerCase().endsWith(".crml"));
    }
    if (typeof target === "string") return [server.protocol2CodeConverter.asUri(target)];
    if (target instanceof vscode.Uri) return [target];
    const editor = vscode.window.activeTextEditor;
    return editor && editor.document.languageId === "crml" ? [editor.document.uri] : [];
}

async function translate(server: LanguageClient, source: vscode.Uri, open: boolean, output: vscode.LogOutputChannel): Promise<void> {
    const name = source.path.slice(source.path.lastIndexOf("/") + 1);
    if (source.scheme === "untitled") {
        vscode.window.showErrorMessage(`Save ${name} first: the Modelica file is written next to it.`);
        return;
    }
    const settings = vscode.workspace.getConfiguration("crml.translation", source);
    const params: TranslateParams = {
        uri: server.code2ProtocolConverter.asUri(source),
        text: await text(source),
        within: settings.get<string>("within") ?? "",
    };

    let result: TranslateResult;
    try {
        result = await server.sendRequest<TranslateResult>(TRANSLATE_REQUEST, params);
    } catch (error) {
        output.error(`Translating ${source.fsPath} failed: ${(error as Error).message}`);
        vscode.window.showErrorMessage(`Translating ${name} failed: ${(error as Error).message}`);
        return;
    }
    if (!result.translated || result.modelica === undefined) {
        output.info(`${source.fsPath} not translated: ${result.message}`);
        const choice = await vscode.window.showErrorMessage(`${name} cannot be translated to Modelica. ${result.message ?? ""}`,
            "Show Problems");
        if (choice) vscode.commands.executeCommand("workbench.actions.view.problems");
        return;
    }

    const folder = target(source, settings.get<string>("outputFolder"));
    await vscode.workspace.fs.createDirectory(folder);
    const modelica = vscode.Uri.joinPath(folder, modelicaFileName(source.path));
    await vscode.workspace.fs.writeFile(modelica, encoder.encode(result.modelica));
    output.info(`Translated ${source.fsPath} to ${modelica.fsPath}`);
    if (result.runtimeLibrary !== undefined) await writeRuntimeLibrary(folder, result.runtimeLibrary, output);

    const fileName = modelicaFileName(source.path);
    const outcome = outcomeMessage(fileName, result);
    if (open) {
        await vscode.window.showTextDocument(modelica, { viewColumn: vscode.ViewColumn.Beside, preview: false, preserveFocus: true });
    }
    if (outcome.warning) {
        const choice = await vscode.window.showWarningMessage(outcome.text, "Show Problems");
        if (choice) vscode.commands.executeCommand("workbench.actions.view.problems");
    } else {
        vscode.window.setStatusBarMessage(`$(check) ${outcome.text}`, 5000);
    }
}

/** The text to translate: of the open document, unsaved changes included, else of the file. */
async function text(source: vscode.Uri): Promise<string> {
    const document = vscode.workspace.textDocuments.find(d => d.uri.toString() === source.toString());
    return document ? document.getText() : decoder.decode(await vscode.workspace.fs.readFile(source));
}

/** The folder the Modelica files go to; see crml.translation.outputFolder. */
function target(source: vscode.Uri, setting: string | undefined): vscode.Uri {
    const beside = vscode.Uri.joinPath(source, "..");
    const folder = outputFolder(setting);
    switch (folder.kind) {
        case "besideSource": return beside;
        case "absolute": return vscode.Uri.file(folder.path);
        case "relative": return vscode.Uri.joinPath(vscode.workspace.getWorkspaceFolder(source)?.uri ?? beside, folder.path);
    }
}

/** Writes CRMLtoModelica.mo next to the generated code, unless the same one is there. */
async function writeRuntimeLibrary(folder: vscode.Uri, library: string, output: vscode.LogOutputChannel): Promise<void> {
    const file = vscode.Uri.joinPath(folder, RUNTIME_LIBRARY);
    let existing: string | undefined;
    try {
        existing = decoder.decode(await vscode.workspace.fs.readFile(file));
    } catch {
        existing = undefined;
    }
    if (needsRuntimeLibrary(existing, library)) {
        await vscode.workspace.fs.writeFile(file, encoder.encode(library));
        output.info(`${existing === undefined ? "Wrote" : "Updated"} ${file.fsPath}`);
    }
}
