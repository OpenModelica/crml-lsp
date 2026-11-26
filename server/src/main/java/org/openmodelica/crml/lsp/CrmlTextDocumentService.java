package org.openmodelica.crml.lsp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import com.google.gson.JsonPrimitive;

/**
 * Keeps the text of the open documents and answers questions about them.
 * Diagnostics are recomputed shortly after the last change, so that typing
 * does not trigger a full analysis on every keystroke.
 */
public class CrmlTextDocumentService implements TextDocumentService {
    static final long DIAGNOSTICS_DELAY_MS = 200;

    private final Map<String, String> open = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingDiagnostics = new ConcurrentHashMap<>();
    private final ScheduledExecutorService analysis = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "crml-analysis");
        thread.setDaemon(true);
        return thread;
    });
    private volatile LanguageClient client;
    private volatile CompletionEngine.ClientSupport support = CompletionEngine.ClientSupport.FULL;
    /** True if the client shows Markdown in hovers. */
    private volatile boolean hoverMarkdown = true;
    /** The documentation of the items of the last completion, by the id in their data. */
    private volatile Map<String, String> lastDocumentation = new HashMap<>();
    /** Made on the analysis thread, which alone uses it; loading the libraries takes a moment. */
    private Analyzer analyzer;

    void connect(LanguageClient client) {
        this.client = client;
    }

    void support(CompletionEngine.ClientSupport support) {
        this.support = support;
    }

    void hoverMarkdown(boolean hoverMarkdown) {
        this.hoverMarkdown = hoverMarkdown;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        open.put(uri, params.getTextDocument().getText());
        scheduleDiagnostics(uri);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        // Full sync: the last change holds the whole new text.
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        if (changes.isEmpty()) return;
        String uri = params.getTextDocument().getUri();
        open.put(uri, changes.get(changes.size() - 1).getText());
        scheduleDiagnostics(uri);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        open.remove(uri);
        ScheduledFuture<?> pending = pendingDiagnostics.remove(uri);
        if (pending != null) pending.cancel(false);
        analysis.execute(() -> analyzer().close(uri));
        publish(uri, new ArrayList<>());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();
        ParsedDocument document = parsed(params.getTextDocument().getUri());
        if (document != null) {
            for (DocumentSymbol symbol : DocumentSymbols.of(document)) result.add(Either.forRight(symbol));
        }
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Completes on the analysis thread, which alone uses the workspace: the
     * document is analysed as it is now, so what is in scope is up to date.
     */
    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        String uri = params.getTextDocument().getUri();
        return CompletableFuture.supplyAsync(() -> {
            ParsedDocument document = parsed(uri);
            if (document == null) return Either.forRight(new CompletionList(false, new ArrayList<>()));
            Map<String, String> documentation = new HashMap<>();
            List<CompletionItem> items;
            try {
                items = analyzer().complete(uri, document, params.getPosition(), support, documentation);
            } catch (RuntimeException e) {
                System.err.println("Completion in " + uri + " failed; proposing keywords only:");
                e.printStackTrace();
                items = KeywordCompletion.at(document, params.getPosition());
            }
            lastDocumentation = documentation;
            return Either.forRight(new CompletionList(false, items));
        }, analysis);
    }

    /** Adds the documentation of an item of the last completion. */
    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
        Object data = item.getData();
        String id = data instanceof JsonPrimitive ? ((JsonPrimitive) data).getAsString()
            : data instanceof String ? (String) data : null;
        String markdown = id == null ? null : lastDocumentation.get(id);
        if (markdown != null) item.setDocumentation(CompletionEngine.documentation(markdown, support));
        return CompletableFuture.completedFuture(item);
    }

    /** On the analysis thread, as completion: the document is analysed as it is now. */
    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = params.getTextDocument().getUri();
        return CompletableFuture.supplyAsync(() -> {
            ParsedDocument document = parsed(uri);
            if (document == null) return null;
            try {
                return analyzer().hover(uri, document, params.getPosition(), hoverMarkdown);
            } catch (RuntimeException e) {
                System.err.println("Hover in " + uri + " failed:");
                e.printStackTrace();
                return null;
            }
        }, analysis);
    }

    /** The command of the extension that translates a document, which the code lens runs. */
    static final String TRANSLATE_COMMAND = "crml.translateToModelica";

    /**
     * Above the name of a model, a code lens that translates it to Modelica.
     * From the parse tree alone, so it shows at once, also while the model has
     * errors; the translation then says what is wrong.
     */
    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        List<CodeLens> lenses = new ArrayList<>();
        String uri = params.getTextDocument().getUri();
        ParsedDocument document = parsed(uri);
        if (document != null && document.tree.definition_type() != null && document.tree.id() != null
            && "model".equals(document.tree.definition_type().getText())) {
            org.antlr.v4.runtime.Token name = document.tree.id().getStart();
            Command command = new Command("$(play) Translate to Modelica", TRANSLATE_COMMAND, List.of(uri));
            lenses.add(new CodeLens(document.positions.range(name.getStartIndex(), name.getStopIndex() + 1), command, null));
        }
        return CompletableFuture.completedFuture(lenses);
    }

    /**
     * Translates a document to Modelica: the open one, or the text the
     * client sends, e.g. of a file that is not open. Its diagnostics, those
     * of the translation included, are published; a document that is not
     * open is then forgotten again.
     */
    CompletableFuture<Translations.Result> translate(Translations.Params params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.uri;
            String text = params.text != null ? params.text : open.get(uri);
            if (uri == null || text == null) return Translations.Result.refused("There is no CRML text to translate.");
            try {
                Translations.Outcome outcome = analyzer().translate(uri, ParsedDocument.parse(text), params.within);
                publish(uri, outcome.diagnostics);
                return outcome.result;
            } catch (RuntimeException e) {
                System.err.println("Translation of " + uri + " failed:");
                e.printStackTrace();
                return Translations.Result.refused("The translation failed: " + e
                    + ". This is likely a bug of the CRML language server.");
            } finally {
                if (!open.containsKey(uri)) analyzer().close(uri);
            }
        }, analysis);
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        ParsedDocument document = parsed(params.getTextDocument().getUri());
        return CompletableFuture.completedFuture(
            document == null ? new SemanticTokens(new ArrayList<>()) : SemanticTokensProvider.of(document));
    }

    private ParsedDocument parsed(String uri) {
        String text = open.get(uri);
        return text == null ? null : ParsedDocument.parse(text);
    }

    private void scheduleDiagnostics(String uri) {
        ScheduledFuture<?> previous = pendingDiagnostics.put(uri,
            analysis.schedule(() -> diagnose(uri), DIAGNOSTICS_DELAY_MS, TimeUnit.MILLISECONDS));
        if (previous != null) previous.cancel(false);
    }

    private void diagnose(String uri) {
        String text = open.get(uri);
        if (text == null) return;
        try {
            publish(uri, analyzer().diagnose(uri, ParsedDocument.parse(text)));
        } catch (RuntimeException e) {
            System.err.println("Analysis of " + uri + " failed:");
            e.printStackTrace();
        }
    }

    private Analyzer analyzer() {
        if (analyzer == null) analyzer = new Analyzer();
        return analyzer;
    }

    private void publish(String uri, List<Diagnostic> diagnostics) {
        LanguageClient client = this.client;
        if (client != null) client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    }
}
