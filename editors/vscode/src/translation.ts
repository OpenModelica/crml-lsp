// The translation of CRML models to Modelica, without the VS Code API so it
// can be tested under plain Node: the protocol of the server's request, the
// names of the files written, and what the user is told.

/** The command that translates: in the editor title, the menus and the code lens of the server. */
export const TRANSLATE_COMMAND = "crml.translateToModelica";

/** The request of the language server; see Translations.java. */
export const TRANSLATE_REQUEST = "crml/translate";

/** The Modelica library the generated code uses, written next to it. */
export const RUNTIME_LIBRARY = "CRMLtoModelica.mo";

export interface TranslateParams {
    uri: string;
    /** The text to translate, unsaved changes included. */
    text: string;
    /** The package the generated class is placed in; empty for the top level. */
    within: string;
}

export interface TranslateResult {
    /** True if there is Modelica to write; else message says why not. */
    translated: boolean;
    message?: string;
    modelName?: string;
    /** The Modelica text, starting with its within clause. */
    modelica?: string;
    /** CRMLtoModelica.mo, as the compiler that translated it has it. */
    runtimeLibrary?: string;
    /** Elements of the model dropped or translated wrongly, and translated otherwise than asked. */
    errors: number;
    warnings: number;
    /** Elements of other files, such as unused operators of a library, that could not be translated. */
    errorsElsewhere: number;
}

/** `a/b/Name.crml` to `Name.mo`, as the compiler's command line names it. */
export function modelicaFileName(crmlPath: string): string {
    const name = crmlPath.slice(crmlPath.lastIndexOf("/") + 1);
    return (name.toLowerCase().endsWith(".crml") ? name.slice(0, -".crml".length) : name) + ".mo";
}

/**
 * The folder the Modelica files go to, as the crml.translation.outputFolder
 * setting says: next to the CRML file if it is empty, the folder itself if
 * it is absolute, else relative to the workspace folder of the CRML file, or
 * to the CRML file's folder outside a workspace.
 */
export type OutputFolder =
    | { kind: "besideSource" }
    | { kind: "absolute", path: string }
    | { kind: "relative", path: string };

export function outputFolder(setting: string | undefined): OutputFolder {
    const path = (setting ?? "").trim();
    if (path === "") return { kind: "besideSource" };
    if (path.startsWith("/") || path.startsWith("\\") || /^[A-Za-z]:[\\/]/.test(path)) return { kind: "absolute", path };
    return { kind: "relative", path };
}

/** True if CRMLtoModelica.mo has to be written: it is missing, or not the one the compiler generated for. */
export function needsRuntimeLibrary(existing: string | undefined, wanted: string): boolean {
    return existing === undefined || existing.replace(/\r\n/g, "\n") !== wanted.replace(/\r\n/g, "\n");
}

/**
 * What to tell about a translation that was written: a warning if elements
 * of the model were lost, else a short note for the status bar.
 */
export function outcomeMessage(fileName: string, result: TranslateResult): { warning: boolean, text: string } {
    if (result.errors > 0) {
        const elements = result.errors === 1 ? "1 element" : `${result.errors} elements`;
        return { warning: true, text: `${fileName} written, but ${elements} of ${result.modelName} could not be translated; see Problems.` };
    }
    const notes: string[] = [];
    if (result.warnings > 0) notes.push(result.warnings === 1 ? "1 warning" : `${result.warnings} warnings`);
    if (result.errorsElsewhere > 0) {
        notes.push(`${result.errorsElsewhere} library ${result.errorsElsewhere === 1 ? "element" : "elements"} not translated`);
    }
    return { warning: false, text: `${fileName} written` + (notes.length ? ` (${notes.join(", ")})` : "") };
}
