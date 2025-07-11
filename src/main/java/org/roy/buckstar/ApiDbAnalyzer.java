package org.roy.buckstar;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;


/**
 * Analyzes Spring Boot REST API endpoints and their associated database operations.
 * This class parses Java source files to trace the relationship between REST endpoints,
 * service methods, and database operations performed through JPA repositories.
 */
public class ApiDbAnalyzer {

    /**
     * Stores the final analysis results in a JSON-compatible format
     */
    private static final Map<String, Object> resultJson = new LinkedHashMap<>();
    /**
     * Maps JPA entity class names to their corresponding database table names
     */
    private static final Map<String, String> entityToTableMap = new HashMap<>();
    /**
     * Base package to scan for Java source files
     */
    private static final String basePackage = "com.mkyong.book";
    /**
     * Root path to the Java source files
     */
    private static final Path sourcePath = Paths.get("D:/SRC/SPRING-BOOT-MASTER/SPRING-BOOT-MASTER/SPRING-DATA-JPA-MYSQL/SRC/main/java");

    /**
     * Main entry point of the analyzer.
     * Sets up the Java parser, analyzes the source files, and outputs the results as JSON.
     *
     * @param args Command line arguments (not used)
     * @throws IOException If there's an error reading source files
     */
    public static void main(String[] args) throws IOException {
        PrintStream originalSystemOut = System.out;
        setSystemOutOff();
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());
        combinedTypeSolver.add(new JavaParserTypeSolver(new File(sourcePath.toString())));
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);

        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setSymbolResolver(symbolSolver);
        JavaParser javaParser = new JavaParser(parserConfiguration);

        SourceRoot sourceRoot = new SourceRoot(sourcePath.resolve(basePackage.replace(".", "/")), parserConfiguration);

        sourceRoot.parse("entities", (localPath, absolutePath, result) -> {
            if (result.isSuccessful() && result.getResult().isPresent()) {
                CompilationUnit cu = result.getResult().get();
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                    if (clazz.isAnnotationPresent("Entity")) {
                        String entityName = clazz.getNameAsString();
                        AtomicReference<String> tableName = new AtomicReference<>(entityName.toLowerCase());
                        Optional<AnnotationExpr> tableAnnotation = clazz.getAnnotationByName("Table");
                        if (tableAnnotation.isPresent()) {
                            AnnotationExpr anno = tableAnnotation.get();
                            if (anno instanceof NormalAnnotationExpr normalAnno) {
                                normalAnno.getPairs().forEach(p -> {
                                    if (p.getNameAsString().equals("name")) {
                                        tableName.set(p.getValue().toString().replace("\"", ""));
                                    }
                                });
                            } else if (anno instanceof SingleMemberAnnotationExpr singleMemberAnno) {
                                tableName.set(singleMemberAnno.getMemberValue().toString().replace("\"", ""));
                            }
                        }
                        entityToTableMap.put(entityName, tableName.get());
                    }
                });
            }
            return SourceRoot.Callback.Result.DONT_SAVE;
        });

        sourceRoot.parse("controllers", (localPath, absolutePath, result) -> {
            if (result.isSuccessful() && result.getResult().isPresent()) {
                CompilationUnit cu = result.getResult().get();
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                    if (clazz.isAnnotationPresent("RestController")) {
                        processController(clazz, combinedTypeSolver);
                    }
                });
            }
            return SourceRoot.Callback.Result.DONT_SAVE;
        });

        setSystemOutOn(originalSystemOut);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(resultJson));
    }

    /**
     * Processes a REST controller class to extract API endpoints and their associated service calls.
     *
     * @param clazz      The controller class declaration to process
     * @param typeSolver The type solver for resolving symbol references
     */
    private static void processController(ClassOrInterfaceDeclaration clazz, TypeSolver typeSolver) {
        clazz.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<AnnotationExpr> mappingAnnotation = method.getAnnotations().stream()
                    .filter(ApiDbAnalyzer::isRequestMapping)
                    .findFirst();

            mappingAnnotation.ifPresent(annotation -> {
                String path = annotation.toString();
                Optional<MethodCallExpr> serviceCall = method.findFirst(MethodCallExpr.class);

                String serviceMethod = serviceCall.map(MethodCallExpr::getNameAsString).orElse("unknownServiceCall");
                List<Map<String, String>> dbOps = new ArrayList<>();

                serviceCall.ifPresent(expr -> traceServiceMethod(expr, typeSolver, dbOps, new HashSet<>()));

                Map<String, Object> apiInfo = new HashMap<>();
                apiInfo.put("serviceMethod", serviceMethod);
                apiInfo.put("dbOperations", dbOps);

                resultJson.put(path, apiInfo);
            });
        });
    }

    /**
     * Recursively traces a service method call to find all database operations.
     *
     * @param expr       The method call expression to analyze
     * @param typeSolver The type solver for resolving symbol references
     * @param dbOps      List to store discovered database operations
     * @param visited    Set of already processed methods to prevent infinite recursion
     */
    private static void traceServiceMethod(MethodCallExpr expr, TypeSolver typeSolver, List<Map<String, String>> dbOps, Set<String> visited) {
        try {
            ResolvedMethodDeclaration methodDecl = expr.resolve();
            String methodKey = methodDecl.getQualifiedSignature();
            if (visited.contains(methodKey)) return;
            visited.add(methodKey);

            ResolvedReferenceTypeDeclaration declaringType = methodDecl.declaringType();
            String qualifiedClassName = declaringType.getQualifiedName();

            Path implPath = sourcePath.resolve((qualifiedClassName + "Impl").replace(".services", ".serviceimpl").replace(".", "/") + ".java");
            Path targetPath = Files.exists(implPath) ? implPath : sourcePath.resolve(qualifiedClassName.replace(".", "/") + ".java");
            String code = Files.readString(targetPath);

            ParserConfiguration serviceParserConfig = new ParserConfiguration();
            serviceParserConfig.setSymbolResolver(new JavaSymbolSolver((CombinedTypeSolver) typeSolver));
            JavaParser serviceJavaParser = new JavaParser(serviceParserConfig);

            CompilationUnit cu = serviceJavaParser.parse(code).getResult().orElseThrow(
                    () -> new RuntimeException("Failed to parse service method source: " + targetPath)
            );

            System.out.println("Method: " + methodDecl.getName() + "-".repeat(100) + methodDecl.getClassName());
            Map<String, String> declaredRepositoriesAndEntities = getDeclaredRepositoriesAndEntities(cu);

            System.out.println("Repositories: " + declaredRepositoriesAndEntities);

            cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(methodDecl.getName()))
                    .forEach(serviceMethod -> {
                        serviceMethod.findAll(MethodCallExpr.class).forEach(call -> {
                            try {
                                String calledName = call.getNameAsString().toLowerCase();
                                System.out.println("Found call: " + call + " to: " + calledName);
                                String tableName = resolveEntityNameFromCall(call, declaredRepositoriesAndEntities).map(entityToTableMap::get).orElse("unknown");
                                System.out.println("Resolved to Table: " + tableName);
                                if (!"unknown".equals(tableName) && (calledName.contains("save") || calledName.contains("insert"))) {
                                    dbOps.add(Map.of("operation", "INSERT", "table", tableName, "methdCall", call.toString()));
                                } else if (!"unknown".equals(tableName) && (calledName.contains("delete"))) {
                                    dbOps.add(Map.of("operation", "DELETE", "table", tableName, "methodCall", call.toString()));
                                } else if (!"unknown".equals(tableName) && (calledName.contains("update"))) {
                                    dbOps.add(Map.of("operation", "UPDATE", "table", tableName, "methodCall", call.toString()));
                                } else if (!"unknown".equals(tableName) && (calledName.contains("find") || calledName.contains("get"))) {
                                    dbOps.add(Map.of("operation", "SELECT", "table", tableName, "methodCall", call.toString()));
                                } else {
                                    traceServiceMethod(call, typeSolver, dbOps, visited); // recursively resolve
                                }
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                System.err.println("Nested call failed: " + call + " due to: " + ex.getMessage());
                            }
                        });
                    });
        } catch (Exception e) {
            System.err.println("Failed to resolve method: " + expr + " due to: " + e.getMessage());
        }
    }

    /**
     * Extracts repository fields and their associated entity types from a compilation unit.
     *
     * @param cu The compilation unit to analyze
     * @return Map of repository variable names to their entity type names
     */
    private static Map<String, String> getDeclaredRepositoriesAndEntities(CompilationUnit cu) {
        Map<String, String> result = new HashMap<>();
        cu.findAll(FieldDeclaration.class)
                .stream()
                .forEach(field -> {
                    try {
                        String fieldType = field.resolve().getType().describe();
                        for (VariableDeclarator var : field.getVariables()) {
                            result.put(var.getNameAsString(), getRepositoryTable(fieldType));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
        return result;
    }

    /**
     * Resolves the entity type from a repository class name.
     *
     * @param repositoryClass Fully qualified name of the repository class
     * @return The entity class name or "NOTFOUND" if resolution fails
     */
    private static String getRepositoryTable(String repositoryClass) {
        // repositoryClass is the fully qualified class name of the JPA Repository
        // This method should return a map with entity class name as key and table name as value
        try {
            Path repoPath = sourcePath.resolve(repositoryClass.replace(".", "/") + ".java");
            if (!Files.exists(repoPath)) {
                System.err.println("Repository source not found: " + repoPath);
                return "NOTFOUND";
            }
            String code = Files.readString(repoPath);
            CompilationUnit cu = new JavaParser().parse(code).getResult().orElse(null);
            if (cu == null) return "NOTFOUND";

            Optional<ClassOrInterfaceDeclaration> repoClass = cu.findFirst(ClassOrInterfaceDeclaration.class);
            if (repoClass.isPresent()) {
                // Find the entity type argument in the repository interface
                if (repoClass.get().getExtendedTypes().isNonEmpty()) {

                    System.out.println("Found repository: " + repoClass.get().getFullyQualifiedName()
                            + " with entity: "
                            + repoClass.get().getExtendedTypes().get(0).getTypeArguments().flatMap(args -> args.stream().findFirst()).get());

                    String generic = repoClass.get().getExtendedTypes().get(0).getTypeArguments()
                            .flatMap(args -> args.stream().findFirst())
                            .map(Object::toString)
                            .orElse(null);

                    System.out.println("Generic: " + generic);

                    if (generic != null && entityToTableMap.containsKey(generic)) {
                        return generic;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to analyze repository: " + repositoryClass + " due to: " + e.getMessage());
        }
        return "NOTFOUND";
    }

    /**
     * Resolves the entity name from a repository method call.
     *
     * @param call                            The method call to analyze
     * @param declaredRepositoriesAndEntities Map of known repositories and their entities
     * @return Optional containing the entity name if found
     */
    private static Optional<String> resolveEntityNameFromCall(MethodCallExpr call, Map<String, String> declaredRepositoriesAndEntities) {
        String calledName = call.getNameAsString().toLowerCase();
        String entityTable =null;
        // Resolve from Repository
        try {
            entityTable = call.resolve().getQualifiedSignature();
        } catch (Exception ex) {
            AtomicReference<String> scope = new AtomicReference<>("");
            call.getScope().ifPresent(s -> scope.set(s.toString()));
            System.out.println("Found from CU with scope: " + call.getNameAsString() + " " + scope.get() + " " +declaredRepositoriesAndEntities.get(scope.get()));
            System.out.println("Declared Repositories and Entities: " + declaredRepositoriesAndEntities);
            System.out.println("calledName: " + calledName);
            entityTable = declaredRepositoriesAndEntities.get(scope.get());
            System.out.println("Resolved to Entity Table: " + entityTable);

            System.out.println("Resolved from Repository: " + scope.get());

            if (entityToTableMap.containsKey(entityTable)) {
                return Optional.of(entityTable);
            }

        }

        // Entity
        return call.getArguments().stream()
                .flatMap(arg -> {
                    try {
                        System.out.println("Resolved from Argument: " + arg);
                        String typeName = arg.calculateResolvedType().describe();
                        String simpleName = typeName.substring(typeName.lastIndexOf('.') + 1);
                        if (entityToTableMap.containsKey(simpleName)) {
                            return Stream.of(simpleName);
                        }
                    } catch (Exception e) {
                        System.err.println("Warning: Failed to resolve argument type for: " + arg + " due to: " + e.getMessage());
                    }
                    return Stream.empty();
                })
                .findFirst();
    }

    /**
     * Checks if an annotation is a Spring MVC request mapping annotation.
     *
     * @param annotation The annotation to check
     * @return true if the annotation is a request mapping annotation
     */
    private static boolean isRequestMapping(AnnotationExpr annotation) {
        return annotation.getNameAsString().matches("(GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping)");
    }

    /**
     * Disables System.out output by redirecting it to a no-op stream.
     */
    public static void setSystemOutOff(){
        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                // Do Nothing
            }
        }));
    }

    /**
     * Restores System.out to the original PrintStream.
     *
     * @param originalSystemOut The original System.out PrintStream to restore
     */
    public static void setSystemOutOn(PrintStream originalSystemOut){
        System.setOut(originalSystemOut);
    }
}
