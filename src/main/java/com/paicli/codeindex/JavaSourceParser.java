package com.paicli.codeindex;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.nodeTypes.NodeWithMembers;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class JavaSourceParser {

    public static final String CLASS_KIND_CLASS = "CLASS";
    public static final String CLASS_KIND_INTERFACE = "INTERFACE";
    public static final String CLASS_KIND_ENUM = "ENUM";
    public static final String CLASS_KIND_ANNOTATION = "ANNOTATION";
    public static final String CLASS_KIND_RECORD = "RECORD";

    public static final String METHOD_KIND_METHOD = "METHOD";
    public static final String METHOD_KIND_CONSTRUCTOR = "CONSTRUCTOR";

    private static final int MAX_ERROR_EXCERPT_CHARS = 2000;

    private final JavaParser parser;

    public JavaSourceParser() {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.parser = new JavaParser(configuration);
    }

    public JavaSourceParseResult parse(Path workspaceRoot, String relativePath) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path sourcePath = root.resolve(relativePath).normalize();
        if (!sourcePath.startsWith(root)) {
            throw new IllegalArgumentException(
                    "relativePath must stay inside workspace");
        }
        try {
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            return parse(relativePath.replace('\\', '/'), source);
        } catch (IOException e) {
            return JavaSourceParseResult.error(
                    relativePath.replace('\\', '/'),
                    excerpt("Failed to read Java source: " + e.getMessage()));
        }
    }

    public JavaSourceParseResult parse(String relativePath, String source) {
        String normalizedPath = relativePath.replace('\\', '/');
        ParseResult<CompilationUnit> result = parser.parse(
                com.github.javaparser.ParseStart.COMPILATION_UNIT,
                Providers.provider(source));
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return JavaSourceParseResult.error(
                    normalizedPath, excerpt(result.getProblems().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining("\n"))));
        }

        CompilationUnit compilationUnit = result.getResult().orElseThrow();
        String packageName = compilationUnit.getPackageDeclaration()
                .map(packageDeclaration -> packageDeclaration.getNameAsString())
                .orElse(null);
        List<String> imports = compilationUnit.getImports().stream()
                .map(this::formatImport)
                .toList();

        List<ParsedClass> classes = new ArrayList<>();
        List<ParsedMethod> methods = new ArrayList<>();
        List<ParsedField> fields = new ArrayList<>();

        for (ClassOrInterfaceDeclaration declaration :
                compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
            ParsedClass parsedClass = toClass(packageName, declaration,
                    declaration.isInterface() ? CLASS_KIND_INTERFACE : CLASS_KIND_CLASS);
            classes.add(parsedClass);
            extractMembers(declaration, parsedClass, source, methods, fields);
        }
        for (EnumDeclaration declaration :
                compilationUnit.findAll(EnumDeclaration.class)) {
            ParsedClass parsedClass = toEnum(packageName, declaration);
            classes.add(parsedClass);
            extractMembers(declaration, parsedClass, source, methods, fields);
        }
        for (AnnotationDeclaration declaration :
                compilationUnit.findAll(AnnotationDeclaration.class)) {
            classes.add(new ParsedClass(
                    packageName, declaration.getNameAsString(),
                    qualifiedName(packageName, declaration),
                    CLASS_KIND_ANNOTATION, modifiers(declaration), null,
                    List.of(), startLine(declaration), endLine(declaration)));
        }
        for (RecordDeclaration declaration :
                compilationUnit.findAll(RecordDeclaration.class)) {
            ParsedClass parsedClass = toRecord(packageName, declaration);
            classes.add(parsedClass);
            extractMembers(declaration, parsedClass, source, methods, fields);
        }

        classes.sort(Comparator.comparing(
                (ParsedClass parsedClass) -> nullLast(parsedClass.startLine()))
                .thenComparing(ParsedClass::qualifiedName));
        methods.sort(Comparator.comparing(
                (ParsedMethod parsedMethod) -> nullLast(parsedMethod.startLine()))
                .thenComparing(ParsedMethod::qualifiedClassName)
                .thenComparing(ParsedMethod::signature));
        fields.sort(Comparator.comparing(
                (ParsedField parsedField) -> nullLast(parsedField.startLine()))
                .thenComparing(ParsedField::qualifiedClassName)
                .thenComparing(ParsedField::fieldName));

        return JavaSourceParseResult.ok(
                normalizedPath, packageName, imports, classes, methods, fields,
                List.of());
    }

    private ParsedClass toClass(String packageName,
                                ClassOrInterfaceDeclaration declaration,
                                String kind) {
        return new ParsedClass(
                packageName, declaration.getNameAsString(),
                qualifiedName(packageName, declaration), kind,
                modifiers(declaration),
                joinTypes(declaration.getExtendedTypes()),
                declaration.getImplementedTypes().stream()
                        .map(Object::toString).toList(),
                startLine(declaration), endLine(declaration));
    }

    private ParsedClass toEnum(String packageName, EnumDeclaration declaration) {
        return new ParsedClass(
                packageName, declaration.getNameAsString(),
                qualifiedName(packageName, declaration), CLASS_KIND_ENUM,
                modifiers(declaration), null,
                declaration.getImplementedTypes().stream()
                        .map(Object::toString).toList(),
                startLine(declaration), endLine(declaration));
    }

    private ParsedClass toRecord(String packageName,
                                 RecordDeclaration declaration) {
        return new ParsedClass(
                packageName, declaration.getNameAsString(),
                qualifiedName(packageName, declaration), CLASS_KIND_RECORD,
                modifiers(declaration), null,
                declaration.getImplementedTypes().stream()
                        .map(Object::toString).toList(),
                startLine(declaration), endLine(declaration));
    }

    private void extractMembers(Node node, ParsedClass parsedClass, String source,
                                List<ParsedMethod> methods,
                                List<ParsedField> fields) {
        if (!(node instanceof NodeWithMembers<?> membersNode)) {
            return;
        }
        for (BodyDeclaration<?> member : membersNode.getMembers()) {
            if (member instanceof MethodDeclaration methodDeclaration) {
                methods.add(toMethod(parsedClass, methodDeclaration, source));
            } else if (member instanceof ConstructorDeclaration constructor) {
                methods.add(toConstructor(parsedClass, constructor, source));
            } else if (member instanceof FieldDeclaration fieldDeclaration) {
                fields.addAll(toFields(parsedClass, fieldDeclaration));
            }
        }
    }

    private ParsedMethod toMethod(ParsedClass parsedClass,
                                  MethodDeclaration declaration,
                                  String source) {
        String signature = declaration.getTypeAsString() + " "
                + declaration.getNameAsString()
                + "(" + parameterSignature(declaration.getParameters()) + ")";
        if (!declaration.getTypeParameters().isEmpty()) {
            signature = declaration.getTypeParameters().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "<", "> "))
                    + signature;
        }
        String chunk = chunk(source, startLine(declaration), endLine(declaration));
        return new ParsedMethod(
                parsedClass.className(), parsedClass.qualifiedName(),
                declaration.getNameAsString(), METHOD_KIND_METHOD, signature,
                declaration.getTypeAsString(),
                declaration.getParameters().stream()
                        .map(parameter -> parameter.getType().asString()).toList(),
                startLine(declaration), endLine(declaration), chunk, sha256(chunk),
                modifiers(declaration));
    }

    private ParsedMethod toConstructor(ParsedClass parsedClass,
                                       ConstructorDeclaration declaration,
                                       String source) {
        String signature = declaration.getNameAsString()
                + "(" + parameterSignature(declaration.getParameters()) + ")";
        if (!declaration.getTypeParameters().isEmpty()) {
            signature = declaration.getTypeParameters().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "<", "> "))
                    + signature;
        }
        String chunk = chunk(source, startLine(declaration), endLine(declaration));
        return new ParsedMethod(
                parsedClass.className(), parsedClass.qualifiedName(),
                declaration.getNameAsString(), METHOD_KIND_CONSTRUCTOR, signature,
                null, declaration.getParameters().stream()
                .map(parameter -> parameter.getType().asString()).toList(),
                startLine(declaration), endLine(declaration), chunk, sha256(chunk),
                modifiers(declaration));
    }

    private List<ParsedField> toFields(ParsedClass parsedClass,
                                       FieldDeclaration declaration) {
        List<ParsedField> fields = new ArrayList<>();
        for (VariableDeclarator variable : declaration.getVariables()) {
            fields.add(new ParsedField(
                    parsedClass.className(), parsedClass.qualifiedName(),
                    variable.getNameAsString(), variable.getTypeAsString(),
                    modifiers(declaration), startLine(declaration),
                    endLine(declaration)));
        }
        return fields;
    }

    private String formatImport(ImportDeclaration importDeclaration) {
        return (importDeclaration.isStatic() ? "static " : "")
                + importDeclaration.getNameAsString()
                + (importDeclaration.isAsterisk() ? ".*" : "");
    }

    private String joinTypes(Iterable<?> types) {
        List<String> values = new ArrayList<>();
        for (Object type : types) {
            values.add(type.toString());
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    private String parameterSignature(
            List<com.github.javaparser.ast.body.Parameter> parameters) {
        return parameters.stream()
                .map(parameter -> parameter.getType().asString()
                        + " " + parameter.getNameAsString())
                .collect(Collectors.joining(", "));
    }

    private String qualifiedName(String packageName, TypeDeclaration<?> node) {
        List<String> names = new ArrayList<>();
        Optional<Node> current = Optional.of(node);
        while (current.isPresent()) {
            Node value = current.get();
            if (value instanceof TypeDeclaration<?> typeDeclaration) {
                names.addFirst(typeDeclaration.getNameAsString());
            }
            current = value.getParentNode();
        }
        String localName = String.join(".", names);
        return packageName == null || packageName.isBlank()
                ? localName : packageName + "." + localName;
    }

    private List<String> modifiers(NodeWithModifiers<?> node) {
        return node.getModifiers().stream()
                .map(modifier -> modifier.getKeyword().asString())
                .toList();
    }

    private Integer startLine(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(null);
    }

    private Integer endLine(Node node) {
        return node.getRange().map(range -> range.end.line).orElse(null);
    }

    private String chunk(String source, Integer startLine, Integer endLine) {
        if (startLine == null || endLine == null || startLine <= 0
                || endLine < startLine) {
            return "";
        }
        String[] lines = source.split("\\R", -1);
        int startIndex = Math.max(0, startLine - 1);
        int endIndex = Math.min(lines.length - 1, endLine - 1);
        List<String> selected = new ArrayList<>();
        for (int i = startIndex; i <= endIndex; i++) {
            selected.add(lines[i]);
        }
        return String.join("\n", selected);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String excerpt(String value) {
        if (value == null || value.isBlank()) {
            return "Java parse failed";
        }
        return value.length() <= MAX_ERROR_EXCERPT_CHARS
                ? value : value.substring(0, MAX_ERROR_EXCERPT_CHARS);
    }

    private int nullLast(Integer value) {
        return value == null ? Integer.MAX_VALUE : value;
    }
}
