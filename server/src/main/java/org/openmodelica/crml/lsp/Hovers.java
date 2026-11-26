package org.openmodelica.crml.lsp;

import java.util.Map;

import org.antlr.v4.runtime.Token;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.openmodelica.crml.language.scope.BuiltIns;
import org.openmodelica.crml.language.workspace.CrmlResource;
import org.openmodelica.crml.language.workspace.RootHeader;
import org.openmodelica.crml.language.workspace.SourceMap;
import org.openmodelica.crml.model.base.typing.ValueType;
import org.openmodelica.crml.model.metamodel.BuiltinType;
import org.openmodelica.crml.model.metamodel.ComputedValue;
import org.openmodelica.crml.model.metamodel.CustomOperator;
import org.openmodelica.crml.model.metamodel.NamedEntity;
import org.openmodelica.crml.model.metamodel.Sequence;
import org.openmodelica.crml.model.metamodel.TypeReference;
import org.openmodelica.crml.model.metamodel.Value;
import org.openmodelica.crml.model.metamodel.Variable;
import org.openmodelica.crml.model.metamodel.VariableReference;

/**
 * What hovering shows, from the object model of an analysed document:
 * <ul>
 * <li>a variable, or a reference to one: its declared type and documentation;
 * <li>a call to a user operator or template, or its declaration: the
 *     operator's signature and documentation, and the type of the call if
 *     lifting makes it a set;
 * <li>another expression: the type the front end infers for it;
 * <li>a class or the model or library itself: its name and documentation.
 * </ul>
 * The innermost object at the position that has something to show is
 * described; nothing is shown between tokens or in comments.
 */
final class Hovers {
    private Hovers() {
    }

    /** What to show at {@code position}; null if nothing. */
    static Hover at(CrmlResource resource, ParsedDocument document, Position position, boolean markdown) {
        EObject root = resource.getRoot();
        SourceMap map = resource.getSourceMap();
        if (root == null || map == null) return null;
        int index = document.positions.codePointIndex(document.positions.offset(position));
        if (!onToken(document, index)) return null;
        for (EObject object = map.objectAt(index); object != null; object = object.eContainer()) {
            String text = describe(object, resource);
            if (text == null) continue;
            Token start = map.start(object);
            Token stop = map.stop(object);
            Range range = start == null || stop == null ? null
                : document.positions.range(start.getStartIndex(), stop.getStopIndex() + 1);
            return new Hover(content(text, markdown), range);
        }
        return null;
    }

    /** True if a token that is not a comment or whitespace covers the code point index. */
    private static boolean onToken(ParsedDocument document, int index) {
        for (Token token : document.tokens) {
            if (token.getStartIndex() <= index && index <= token.getStopIndex()) {
                return token.getChannel() == Token.DEFAULT_CHANNEL;
            }
        }
        return false;
    }

    /** The Markdown for an object; null if it has nothing to show. */
    static String describe(EObject object, CrmlResource resource) {
        EObject root = resource.getRoot();
        if (object instanceof VariableReference) {
            Variable variable = ((VariableReference) object).getVariable();
            return variable == null || variable.eIsProxy() ? null : variable(variable, root);
        }
        if (object instanceof Variable) return variable((Variable) object, root);
        if (object instanceof ComputedValue) {
            CustomOperator operator = ((ComputedValue) object).getOperator();
            if (operator == null || operator.eIsProxy()) return null;
            String text = operator(operator, root);
            // An operator returns a single value: a set means the call is
            // lifted, applying the operator to each element.
            String call = typeName(((ComputedValue) object).inferType());
            if (call != null && call.endsWith("{}")) text += "\n\nThis call is a set: `" + call + "`";
            return text;
        }
        if (object instanceof CustomOperator) return operator((CustomOperator) object, root);
        if (object instanceof Sequence || object instanceof TypeReference) {
            // An unresolved call (reported as such), or the type of a declaration.
            return null;
        }
        if (object instanceof Value) {
            String type = typeName(((Value) object).inferType());
            return type == null ? null : code(type) + "\n\nType of the expression";
        }
        if (object == root) {
            RootHeader header = resource.getHeader();
            String kind = header == null ? null : header.kind();
            return named((NamedEntity) root, kind == null ? "" : kind + " ", root);
        }
        if (object instanceof org.openmodelica.crml.model.metamodel.Class) {
            return named((NamedEntity) object, "class ", root);
        }
        return null;
    }

    private static String variable(Variable variable, EObject root) {
        String type = typeName(variable.declaredType());
        if (type == null) {
            // A class, which type inference does not cover yet: as written.
            type = ScopeSnapshot.typeName(variable.getDomain());
            if (type != null && Boolean.TRUE.equals(variable.getCollection())) type += " {}";
        }
        String declaration = (type == null ? "" : type + " ") + variable.getName();
        return withSource(code(declaration), ScopeSnapshot.source(variable, root));
    }

    private static String operator(CustomOperator operator, EObject root) {
        return withSource(code(ScopeSnapshot.operator(operator, root).signature()),
            ScopeSnapshot.source(operator, root));
    }

    private static String named(NamedEntity entity, String kind, EObject root) {
        if (entity.getName() == null) return null;
        return withSource(code(kind + entity.getName()), ScopeSnapshot.source(entity, root));
    }

    private static String withSource(String text, ScopeSnapshot.Source source) {
        StringBuilder result = new StringBuilder(text);
        if (source.documentation != null) result.append("\n\n").append(source.documentation);
        if (!source.own && source.root != null) result.append("\n\nFrom ").append(source.root);
        return result.toString();
    }

    /**
     * A type as CRML writes it, e.g. {@code Boolean} or {@code Real {}} for a
     * set; null if unknown.
     */
    static String typeName(ValueType type) {
        if (type == null || type.isUnknown()) return null;
        BuiltinType builtin = type.getBuiltinType();
        String name = null;
        for (Map.Entry<String, BuiltinType> entry : BuiltIns.TYPES.entrySet()) {
            if (entry.getValue() == builtin) name = entry.getKey();
        }
        if (name == null) return null;
        return type.isSet() ? name + " {}" : name;
    }

    private static String code(String crml) {
        return "```crml\n" + crml + "\n```";
    }

    private static MarkupContent content(String markdown, boolean supported) {
        if (supported) return new MarkupContent(MarkupKind.MARKDOWN, markdown);
        return new MarkupContent(MarkupKind.PLAINTEXT,
            markdown.replace("```crml\n", "").replace("\n```", "").replace("`", ""));
    }
}
