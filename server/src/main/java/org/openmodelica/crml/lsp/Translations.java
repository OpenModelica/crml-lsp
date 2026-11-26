package org.openmodelica.crml.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.Token;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import org.openmodelica.crml.language.workspace.CrmlResource;
import org.openmodelica.crml.language.workspace.SourceMap;
import org.openmodelica.crml.language.workspace.SourcePosition;

/**
 * The translation of a CRML model to Modelica, as the {@code crml/translate}
 * request gives it: what the client writes, and the problems of the
 * translation as diagnostics of the CRML document.
 */
final class Translations {
    /** The name of the request, which the extension sends. */
    static final String METHOD = "crml/translate";
    /** Where the compiler jar keeps the Modelica library the generated code uses. */
    static final String RUNTIME_LIBRARY = "modelica_libraries/CRMLtoModelica.mo";
    /** The source of the diagnostics of a translation, beside the front end's. */
    static final String SOURCE = "crml → Modelica";

    private static String runtimeLibrary;

    private Translations() {
    }

    /** The parameters of {@code crml/translate}. */
    public static final class Params {
        /** The document to translate. */
        public String uri;
        /** Its text, e.g. with unsaved changes; null for the text of the open document. */
        public String text;
        /** The package the generated class is placed in, as the 'within' clause; empty or null for the top level. */
        public String within;
    }

    /** The result of {@code crml/translate}. */
    public static final class Result {
        /** True if there is Modelica to write; else {@link #message} says why not. */
        public boolean translated;
        /** Why the document was not translated; null if it was. */
        public String message;
        /** The name of the CRML model, which is also that of the generated Modelica class. */
        public String modelName;
        /** The Modelica text of the model, starting with its 'within' clause. */
        public String modelica;
        /** CRMLtoModelica.mo, the library the generated code uses. */
        public String runtimeLibrary;
        /** How many elements of the model were dropped or translated wrongly (ERROR), and translated otherwise than asked (WARNING). */
        public int errors;
        public int warnings;
        /** How many elements of other files, such as operators of a library, were dropped or translated wrongly. */
        public int errorsElsewhere;

        static Result refused(String message) {
            Result result = new Result();
            result.message = message;
            return result;
        }
    }

    /** A result, and the diagnostics to publish for the document with it. */
    static final class Outcome {
        final Result result;
        final List<Diagnostic> diagnostics;

        Outcome(Result result, List<Diagnostic> diagnostics) {
            this.result = result;
            this.diagnostics = diagnostics;
        }
    }

    /** The Modelica file text as the compiler's command line writes it. */
    static String modelicaFile(String within, String modelica) {
        return "within " + (within == null ? "" : within.trim()) + ";\n" + modelica + "\n";
    }

    /**
     * True if a problem is about the model itself, rather than another file:
     * the translation also translates every operator of the libraries the
     * model uses, called or not.
     */
    static boolean inDocument(org.openmodelica.crml.compiler.crmlcv2.Diagnostic problem, CrmlResource resource) {
        EObject source = problem.crmlSource();
        return source == null || source.eResource() == resource;
    }

    /**
     * A problem of the translation as a diagnostic of the document. One in the
     * document is on the element it is about. One in another file, such as an
     * operator of a library, is on the name of the model, says where it is,
     * and is at most a warning: what the model uses may well be complete.
     */
    static Diagnostic diagnostic(org.openmodelica.crml.compiler.crmlcv2.Diagnostic problem,
                                 CrmlResource resource, ParsedDocument document) {
        EObject source = problem.crmlSource();
        Range range = null;
        String message = "Modelica translation: " + problem.message();
        DiagnosticSeverity severity = severity(problem.severity());
        if (inDocument(problem, resource)) {
            SourceMap map = resource.getSourceMap();
            Token start = source == null || map == null ? null : map.start(source);
            Token stop = source == null || map == null ? null : map.stop(source);
            if (start != null && stop != null) {
                range = document.positions.range(start.getStartIndex(), stop.getStopIndex() + 1);
            }
        } else {
            SourcePosition position = SourcePosition.of(source);
            if (position != null) {
                String file = position.getLocation().substring(position.getLocation().lastIndexOf('/') + 1);
                message = "Modelica translation, in " + file + ":" + position.getLine() + ": " + problem.message();
            }
            if (severity == DiagnosticSeverity.Error) severity = DiagnosticSeverity.Warning;
        }
        if (range == null) range = Diagnostics.nameRange(document);
        Diagnostic diagnostic = new Diagnostic(range, message, severity, SOURCE);
        diagnostic.setCode(problem.category().name());
        return diagnostic;
    }

    static List<Diagnostic> diagnostics(List<org.openmodelica.crml.compiler.crmlcv2.Diagnostic> problems,
                                        CrmlResource resource, ParsedDocument document) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (org.openmodelica.crml.compiler.crmlcv2.Diagnostic problem : problems) {
            diagnostics.add(diagnostic(problem, resource, document));
        }
        return diagnostics;
    }

    private static DiagnosticSeverity severity(org.openmodelica.crml.compiler.crmlcv2.Diagnostic.Severity severity) {
        switch (severity) {
            case ERROR: return DiagnosticSeverity.Error;
            case WARNING: return DiagnosticSeverity.Warning;
            default: return DiagnosticSeverity.Information;
        }
    }

    /** CRMLtoModelica.mo, from the compiler jar. */
    static synchronized String runtimeLibrary() {
        if (runtimeLibrary == null) {
            try (InputStream in = Translations.class.getClassLoader().getResourceAsStream(RUNTIME_LIBRARY)) {
                if (in == null) throw new IllegalStateException(RUNTIME_LIBRARY + " is missing from the compiler");
                runtimeLibrary = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return runtimeLibrary;
    }
}
