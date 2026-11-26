# CRML for Visual Studio Code

Language support for CRML, the Common Requirement Modeling Language.

## Features

- **Highlighting** in the style of the CRML publications: keywords, built-in
  types, user-defined operators such as `'during'`, strings and comments. It
  works as soon as a file opens, without the language server.
- **Errors** as you type: syntax errors on the offending token, and problems
  found while building the model, such as names that cannot be resolved and
  operator calls that no operator in scope matches.
- **Hover** information: the declared type and documentation of a variable,
  the signature and documentation of the operator a call resolves to, and the
  type the compiler infers for an expression, such as `Boolean` or `Real {}`.
- **Outline** of models, classes, types, variables, operators and templates,
  also in the breadcrumbs and through *Go to Symbol in Editor* (`Ctrl+Shift+O`).
- **Completion** with `Ctrl+Space`: what can be written at the cursor, also
  while the model is incomplete.
  - Operators in scope, from the model and the libraries it uses, inserted
    with a tab stop for each operand: `'during' b` inserts `'during' ${1:b}`.
    Where a value starts, operators that take an operand on their left come
    first; after a value, they continue it.
  - Variables, the Boolean values (`true`, `false`, `undecided`,
    `undefined`), classes, built-in forms such as `if … then … else …`, and
    the keywords the grammar allows.
  - After `model M is` or `X union`: the libraries and models to depend on,
    inserted with their `union`.
  - Operators and values of the type being defined are listed first, then
    the model's own before those of libraries.
  - Details show the type and the library; the documentation shows the
    declaration and the comment above it.

  Suggestions do not pop up while typing; ask for them with `Ctrl+Space`.
- **Translate to Modelica**: the ▷ button of the editor title, *Translate to
  Modelica* above `model … is`, the context menus of the editor and of `.crml`
  files in the Explorer (also for several selected files), or *CRML: Translate
  to Modelica* in the command palette.
  - `Name.crml` is translated as the CRML compiler's command line does, but
    with the bundled libraries and the open files in scope, and with unsaved
    changes. `Name.mo` is written next to it, or to
    `crml.translation.outputFolder`, and opens beside the model.
  - `CRMLtoModelica.mo`, the Modelica library the generated code uses, is
    written to the same folder, unless the same version is already there.
  - Only a model without errors is translated. What could not be translated
    is listed in *Problems*: elements of the model on the element; operators
    of a library it uses, which are translated too, as warnings on its name.
- Comment toggling, bracket matching and auto-closing pairs.

## Libraries

The language server comes with the ETL and FORM-L libraries, so a model can use
them with `ETL union` or `FORML union`. These are simplified copies that the
current grammar accepts; the differences are listed at the top of each file
(`server/src/main/resources/org/openmodelica/crml/lsp/libraries` in the
[crml-lsp repository](https://github.com/PELAB-LiU/crml-lsp)).
Note that FORM-L declares itself `FORML`, not `FORM_L`.

Other open files are seen as well: a model can use a library that is open in
the editor.

## Requirements

Everything except highlighting comes from the CRML language server, which runs
on Java 11 or later.

- The platform-specific packages of this extension include a Java runtime and
  need nothing else.
- The universal package uses the Java set in `crml.java.home`, or else the one
  in `JAVA_HOME`, or else `java` on the `PATH`.

If no suitable Java is found, highlighting keeps working, and the
*CRML Language Server* output channel says what was tried.

## Settings

| Setting | Description |
|---|---|
| `crml.java.home` | Folder of the Java 11+ installation to run the language server with. |
| `crml.trace.server` | Logs the messages between VS Code and the language server: `off`, `messages` or `verbose`. |
| `crml.translation.outputFolder` | Folder the Modelica files are written to. Empty: next to the CRML file; a relative path is taken from the workspace folder. |
| `crml.translation.within` | Package the generated Modelica classes are placed in, as their `within` clause. Empty: the top level. |

The command *CRML: Restart Language Server* restarts the server, for example
after changing `crml.java.home`.

## License

OSMC Public License 1.8; see `OSMC-License.txt`. Third-party components are
listed in `THIRD-PARTY-NOTICES.md`.
