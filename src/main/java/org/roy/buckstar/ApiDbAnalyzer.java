package org.roy.buckstar;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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
import org.roy.buckstar.common.Util;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.roy.buckstar.common.GlobalConstants.*;


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
     * Root path to the Java source files
     */
    private static final Path sourcePath = Paths.get(srcPath);

    /**
     * Main entry point of the analyzer.
     * Sets up the Java parser, analyzes the source files, and outputs the results as JSON.
     *
     * @param args Command line arguments (not used)
     * @throws IOException If there's an error reading source files
     */
    public static void main(String[] args) throws IOException {
        PrintStream originalSystemOut = System.out;
        Util.setSystemOutOff(); // Set SYSOUT OFF
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());
        combinedTypeSolver.add(new JavaParserTypeSolver(new File(sourcePath.toString())));
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);

        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setSymbolResolver(symbolSolver);

        SourceRoot sourceRoot = new SourceRoot(sourcePath.resolve(basePackage.replace(".", "/")), parserConfiguration);

        // Parse all entity classes to build the entity-to-table mapping
        sourceRoot.parse(entityPath, (localPath, absolutePath, result) -> {
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

        // Parse all controller classes to extract REST API endpoints and their associated service calls
        sourceRoot.parse(controllerPath, (localPath, absolutePath, result) -> {
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

        // Set SYSOUT ON
        Util.setSystemOutOn(originalSystemOut);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(resultJson));
    }

    /**
     * Processes a REST controller class to extract API endpoints and their associated service calls.
     * For each controller method annotated with a request mapping, this method:
     * 1. Extracts the API endpoint path from the mapping annotation
     * 2. Identifies the service method being called
     * 3. Traces the service method to find all database operations
     * 4. Stores the results in the resultJson map
     *
     * @param clazz      The controller class declaration to process
     * @param typeSolver The type solver used for resolving symbol references in the source code
     */
    private static void processController(ClassOrInterfaceDeclaration clazz, TypeSolver typeSolver) {
        clazz.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<AnnotationExpr> mappingAnnotation = method.getAnnotations().stream()
                    .filter(Util::isRequestMapping)
                    .findFirst();

            mappingAnnotation.ifPresent(annotation -> {
                String path = annotation.toString();
                Optional<MethodCallExpr> serviceCall = method.findFirst(MethodCallExpr.class);

                String serviceMethod = serviceCall.map(MethodCallExpr::getNameAsString).orElse("unknownServiceCall");
                List<Map<String, String>> dbOps = new ArrayList<>();

                serviceCall.ifPresent(expr -> traceServiceMethod(expr, typeSolver, dbOps, new HashSet<>(), sourcePath));

                Map<String, Object> apiInfo = new HashMap<>();
                apiInfo.put("serviceMethod", serviceMethod);
                apiInfo.put("dbOperations", dbOps);

                resultJson.put(path, apiInfo);
            });
        });
    }

    /**
     * Recursively traces a service method call to find all database operations.
     * This method:
     * 1. Resolves the method declaration using the type solver
     * 2. Finds the implementation class (either direct or *Impl class)
     * 3. Parses the implementation source to find repository method calls
     * 4. Identifies database operations (SELECT, INSERT, UPDATE, DELETE)
     * 5. Recursively traces any nested service method calls
     *
     * @param expr       The method call expression to analyze
     * @param typeSolver The type solver for resolving symbol references
     * @param dbOps      List to accumulate discovered database operations
     * @param visited    Set of already processed methods to prevent infinite recursion
     * @param sourcePath The base path for resolving source files
     */
    private static void traceServiceMethod(MethodCallExpr expr, TypeSolver typeSolver, List<Map<String, String>> dbOps, Set<String> visited, Path sourcePath) {
        try {
            // Resolve the Service class from Method Call Expression
            ResolvedMethodDeclaration methodDecl = expr.resolve();
            String methodKey = methodDecl.getQualifiedSignature();
            if (visited.contains(methodKey)) return;
            visited.add(methodKey);

            ResolvedReferenceTypeDeclaration declaringType = methodDecl.declaringType();
            String qualifiedClassName = declaringType.getQualifiedName();

            // Find the path of Service Class Implementation from the name of Service class
            String implDirectory = basePackage.replace(".", "/") + "/" + serviceImplPath;
            Path implPath = Util.resolveImplClassPath(sourcePath, qualifiedClassName, implDirectory);
            Path targetPath = Files.exists(implPath) ? implPath : sourcePath.resolve(qualifiedClassName.replace(".", "/") + ".java");
            String code = Files.readString(targetPath);

            ParserConfiguration serviceParserConfig = new ParserConfiguration();
            serviceParserConfig.setSymbolResolver(new JavaSymbolSolver(typeSolver));
            JavaParser serviceJavaParser = new JavaParser(serviceParserConfig);

            CompilationUnit cu = serviceJavaParser.parse(code).getResult().orElseThrow(
                    () -> new RuntimeException("Failed to parse service method source: " + targetPath)
            );

            System.out.println("Method: " + methodDecl.getName() + "-".repeat(100) + methodDecl.getClassName());
            Map<String, String> declaredRepositoriesAndEntities = Util.getDeclaredRepositoriesAndEntities(cu, sourcePath, entityToTableMap);

            System.out.println("Repositories: " + declaredRepositoriesAndEntities);

            cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(methodDecl.getName()))
                    .forEach(serviceMethod -> serviceMethod.findAll(MethodCallExpr.class).forEach(call -> {
                        try {
                            String calledName = call.getNameAsString().toLowerCase();
                            System.out.println("Found call: " + call + " to: " + calledName);
                            System.out.println("entityToTableMap: " + entityToTableMap);
                            String tableOrQuery = Util.resolveEntityNameQueryFromCall(call, declaredRepositoriesAndEntities, entityToTableMap, sourcePath).orElse("unknown");

                            System.out.println("Table or Query: " + tableOrQuery);

                            if (!"unknown".equals(tableOrQuery) && (tableOrQuery.toUpperCase().indexOf("QUERY[")==0)) {
                                dbOps.add(Map.of("operation", "QUERY", "query", tableOrQuery, "methodCall", call.toString()));
                            } else if (!"unknown".equals(tableOrQuery) && (calledName.contains("save") || calledName.contains("insert"))) {
                                dbOps.add(Map.of("operation", "INSERT", "table", tableOrQuery, "methodCall", call.toString()));
                            } else if (!"unknown".equals(tableOrQuery) && (calledName.contains("delete"))) {
                                dbOps.add(Map.of("operation", "DELETE", "table", tableOrQuery, "methodCall", call.toString()));
                            } else if (!"unknown".equals(tableOrQuery) && (calledName.contains("update"))) {
                                dbOps.add(Map.of("operation", "UPDATE", "table", tableOrQuery, "methodCall", call.toString()));
                            } else if (!"unknown".equals(tableOrQuery) && (calledName.contains("find") || calledName.contains("get"))) {
                                dbOps.add(Map.of("operation", "SELECT", "table", tableOrQuery, "methodCall", call.toString()));
                            } else {
                                traceServiceMethod(call, typeSolver, dbOps, visited, sourcePath); // recursively resolve
                            }
                        } catch (Exception ex) {
                            System.err.println("Nested call failed: " + call + " due to: " + ex.getMessage());
                        }
                    }));
        } catch (Exception e) {
            System.err.println("Failed to resolve method: " + expr + " due to: " + e.getMessage());
        }
    }
}
