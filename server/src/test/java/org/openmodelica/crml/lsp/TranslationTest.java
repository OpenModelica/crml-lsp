package org.openmodelica.crml.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.Test;
import org.openmodelica.crml.language.workspace.CrmlDiagnostic;
import org.openmodelica.crml.language.workspace.CrmlResource;

class TranslationTest {
    private static final Analyzer ANALYZER = new Analyzer();
    private static final String URI = "file:///test/Speed.crml";

    static final String SPEED = String.join("\n",
        "model Speed is ETL union {",
        "  Real v is external;",
        "  Boolean fast is v > 6.0;",
        "  Boolean c is fast 'implies' (v > 8.0);",
        "};");

    private static Translations.Outcome translate(String text, String within) {
        try {
            return ANALYZER.translate(URI, ParsedDocument.parse(text), within);
        } finally {
            ANALYZER.close(URI);
        }
    }

    @Test
    void translatesAModelWithTheBundledLibraries() {
        Translations.Outcome outcome = translate(SPEED, "");
        assertTrue(outcome.result.translated, outcome.result.message);
        assertNull(outcome.result.message);
        assertEquals("Speed", outcome.result.modelName);
        // Partial: its external variables are inputs that something must give values.
        assertTrue(outcome.result.modelica.startsWith("within ;\npartial model Speed\n"), outcome.result.modelica);
        assertTrue(outcome.result.modelica.contains("end Speed;"), outcome.result.modelica);
        assertTrue(outcome.result.runtimeLibrary.startsWith("package CRMLtoModelica"));
        assertEquals(0, outcome.result.errors, outcome.diagnostics.toString());
        // Every ETL operator is translated, also those the model does not
        // call; some cannot be yet. They are warnings on the model's name.
        for (Diagnostic diagnostic : outcome.diagnostics) {
            assertEquals(DiagnosticSeverity.Warning, diagnostic.getSeverity(), diagnostic.toString());
            assertEquals(new Range(new Position(0, 6), new Position(0, 11)), diagnostic.getRange());
            assertTrue(diagnostic.getMessage().getLeft().startsWith("Modelica translation, in ETL.crml:"), diagnostic.toString());
        }
        assertEquals(outcome.diagnostics.size(), outcome.result.errorsElsewhere);
    }

    @Test
    void placesTheClassInTheGivenPackage() {
        assertTrue(translate(SPEED, " Requirements.Speeds ").result.modelica.startsWith("within Requirements.Speeds;\npartial model Speed\n"));
    }

    @Test
    void refusesLibraries() {
        Translations.Outcome outcome = translate("library L is {\n  Operator [Boolean] 'twice' Boolean b = b and b;\n};", "");
        assertFalse(outcome.result.translated);
        assertEquals("L is a library: only a model can be translated to Modelica.", outcome.result.message);
    }

    @Test
    void refusesModelsWithErrors() {
        Translations.Outcome syntax = translate("model M is {\n  Boolean b is ;\n};", "");
        assertFalse(syntax.result.translated);
        assertEquals("The file has syntax errors; fix them first.", syntax.result.message);
        assertEquals(1, syntax.diagnostics.size());

        Translations.Outcome unresolved = translate("model M is {\n  Boolean b is c;\n};", "");
        assertFalse(unresolved.result.translated);
        assertEquals("The model has errors; fix them first.", unresolved.result.message);
        assertEquals("UNRESOLVED_REFERENCE", unresolved.diagnostics.get(0).getCode().getLeft());
    }

    @Test
    void reportsWhatCouldNotBeTranslatedWhereItIs() {
        // CRMLtoModelica.mo has no implementation of duration: the variable is dropped.
        String text = "model D is {\n  Boolean b is external;\n  Periods P is external;\n  Real d is duration b on P;\n};";
        Translations.Outcome outcome = translate(text, "");
        assertTrue(outcome.result.translated, outcome.result.message);
        assertTrue(outcome.result.errors > 0, outcome.diagnostics.toString());
        List<Diagnostic> translation = outcome.diagnostics.stream()
            .filter(d -> Translations.SOURCE.equals(d.getSource())).collect(Collectors.toList());
        assertFalse(translation.isEmpty(), outcome.diagnostics.toString());
        assertTrue(translation.stream().allMatch(d -> d.getRange().getStart().getLine() == 3), translation.toString());
        assertTrue(translation.get(0).getMessage().getLeft().startsWith("Modelica translation: "), translation.toString());
    }

    @Test
    void leavesNoTranslationErrorsBehindInTheLibraries() {
        translate(SPEED, "");
        translate("model D is ETL union {\n  Boolean b is external;\n  Periods P is external;\n  Real d is duration b on P;\n};", "");
        for (CrmlResource library : ANALYZER.libraries()) {
            for (Resource.Diagnostic error : library.getErrors()) {
                assertFalse(error instanceof CrmlDiagnostic
                    && ((CrmlDiagnostic) error).getKind() == CrmlDiagnostic.Kind.TRANSLATION, error.getMessage());
            }
        }
    }

    // ── Code lens ────────────────────────────────────────────

    @Test
    void offersTheTranslationAboveModelsOnly() throws Exception {
        CrmlTextDocumentService service = new CrmlTextDocumentService();
        service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(URI, "crml", 1, SPEED)));
        List<? extends CodeLens> lenses = service.codeLens(new CodeLensParams(new TextDocumentIdentifier(URI))).get();
        assertEquals(1, lenses.size());
        assertEquals(new Range(new Position(0, 6), new Position(0, 11)), lenses.get(0).getRange());
        assertEquals(CrmlTextDocumentService.TRANSLATE_COMMAND, lenses.get(0).getCommand().getCommand());
        assertEquals(List.of(URI), lenses.get(0).getCommand().getArguments());

        String library = "file:///test/L.crml";
        service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(library, "crml", 1, "library L is {\n};")));
        assertEquals(List.of(), service.codeLens(new CodeLensParams(new TextDocumentIdentifier(library))).get());
    }
}
