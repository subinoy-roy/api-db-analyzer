package org.roy.buckstar.common;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.roy.buckstar.common.GlobalConstants.basePackage;
import static org.roy.buckstar.common.GlobalConstants.repositoryPath;


/**
 * Utility class providing helper methods for analyzing Java source code and repository structures.
 * Contains methods for:
 * - Extracting repository fields and their associated entity types
 * - Resolving entity names from repository classes and method calls
 * - Checking Spring MVC request mapping annotations
 * - Managing System.out redirection
 */
public class Util {
    /**
     * Extracts repository fields and their associated entity types from a compilation unit.
     *
     * @param cu               The compilation unit to analyze for repository declarations
     * @param sourcePath       The root path of the source code files
     * @param entityToTableMap Map containing entity class names to their corresponding table names
     * @return Map containing repository variable names as keys and their corresponding entity type names as values
     */
    public static Map<String, String> getDeclaredRepositoriesAndEntities(CompilationUnit cu, Path sourcePath, Map<String, String> entityToTableMap) {
        Map<String, String> result = new HashMap<>();
        cu.findAll(FieldDeclaration.class)
                .forEach(field -> {
                    try {
                        String fieldType = field.resolve().getType().describe();
                        for (VariableDeclarator var : field.getVariables()) {
                            result.put(var.getNameAsString(), getRepositoryTable(fieldType, entityToTableMap, sourcePath));
                        }
                    } catch (Exception e) {
                        System.out.println("Warning: Failed to resolve field type for: " + field + " due to: " + e.getMessage());
                    }
                });
        return result;
    }

    /**
     * Resolves the entity type from a repository class name by analyzing the source code.
     *
     * @param repositoryClass  Fully qualified name of the repository class to analyze
     * @param entityToTableMap Map containing entity class names to their corresponding table names
     * @param sourcePath       The root path of the source code files
     * @return The entity class name if found, or "NOTFOUND" if resolution fails
     */
    public static String getRepositoryTable(String repositoryClass, Map<String, String> entityToTableMap, Path sourcePath) {
        // repositoryClass is the fully qualified class name of the JPA Repository
        // This method should return a map with entity class name as key and table name as value
        try {
            System.out.println("-------------------------> repositoryClass ---------------------->" + repositoryClass);
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
                            + repoClass.get().getExtendedTypes().get(0).getTypeArguments()
                            .flatMap(args -> args.stream().findFirst()).orElse(null));

                    String generic = repoClass.get().getExtendedTypes().get(0).getTypeArguments()
                            .flatMap(args -> args.stream().findFirst())
                            .map(Object::toString)
                            .orElse(null);

                    System.out.println("Generic: " + generic);
                    System.out.println(entityToTableMap);
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
    public static Optional<String> resolveEntityNameQueryFromCall(MethodCallExpr call, Map<String, String> declaredRepositoriesAndEntities, Map<String, String> entityToTableMap, Path sourcePath) {
        String calledName = call.getNameAsString().toLowerCase();
        String entityTable;
        // Try to find and resolve from defined Repository Methods
        try {
            String query;
            String callQualifiedName = call.resolve().declaringType().getQualifiedName();
            System.out.println("Signature: " + call.resolve().getQualifiedSignature());
            entityTable = getRepositoryTable(callQualifiedName, entityToTableMap, sourcePath);
            System.out.println("Resolved to Entity Table: " + entityTable);
            try {
                System.out.println("call: " + call.getName() + " callQualifiedName: " + callQualifiedName);
                query = extractQueryFromQualifiedSignature(call, sourcePath.toString());
                System.out.println("extractQueryFromQualifiedSignature: [" + query + "]");
                if("".equals(query) || query==null)
                    query = "NOTFOUND";
            } catch (Exception e) {
                System.err.println("Failed to extract query from call: " + call.getNameAsString() + " due to: " + e.getMessage());
                query = "NOTFOUND";
            }

            // In case Repository method found, but no @Query/@NativeQuery found, go with the table name found in Interface declaration
            if (query.equals("NOTFOUND") && entityToTableMap.containsKey(entityTable)) {
                return Optional.of(entityTable);
            } else if (query.equals("NOTFOUND")) {
                return Optional.empty();
            } else {
                return Optional.of(query);
            }
        } catch (Exception ex) { // No Method declared -> Resolve from JPA Interface Declaration
            AtomicReference<String> scope = new AtomicReference<>("");
            call.getScope().ifPresent(s -> scope.set(s.toString()));
            System.out.println("Found from CU with scope: " + call.getNameAsString() + " " + scope.get() + " " +declaredRepositoriesAndEntities.get(scope.get()));
            System.out.println("Declared Repositories and Entities: " + declaredRepositoriesAndEntities);
            System.out.println("calledName: " + calledName);
            entityTable = declaredRepositoriesAndEntities.get(scope.get());
            System.out.println("Resolved to Entity Table: " + entityTable);

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
    public static boolean isRequestMapping(AnnotationExpr annotation) {
        return annotation.getNameAsString().matches("(GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping)");
    }

    /**
     * Disables System.out output by redirecting it to a no-op stream.
     */
    public static void setSystemOutOff(){
        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
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

    /**
     * Extracts the @Query or @NativeQuery annotation value from a repository method.
     *
     * @param call           The method call expression to analyze
     * @param sourceRootPath The root path of the source code files
     * @return The query string if found, or "NOTFOUND" if no query annotation is present
     * @throws Exception If there is an error reading or parsing the source file
     */
    public static String extractQueryFromQualifiedSignature(MethodCallExpr call, String sourceRootPath) throws Exception {
        String callTypeQualifiedName = call.resolve().declaringType().getQualifiedName();
        if (callTypeQualifiedName.indexOf(basePackage+"."+repositoryPath+".") == 0) {
            String code = Files.readString(Paths.get(sourceRootPath, callTypeQualifiedName.replace(".", "/") + ".java"));
            CompilationUnit cu = new JavaParser().parse(code).getResult().orElse(null);
            if (cu == null) return "NOTFOUND";

            AtomicReference<String> query = new AtomicReference<>("");

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> clazz.findAll(MethodDeclaration.class)
                    .stream().filter(methodDeclaration -> methodDeclaration.getNameAsString().equals(call.getNameAsString()))
                    .forEach(method -> {
                System.out.println("Method: " + method.getNameAsString());
                method.getAnnotations().forEach(anno -> {
                    if (anno.getNameAsString().equals("Query") || anno.getNameAsString().equals("NativeQuery")) {
                        System.out.println("Found annotation: " + anno);
                        query.set("QUERY[" + anno.asSingleMemberAnnotationExpr().getMemberValue().toString() + "]");
                    }
                });
            }));
            return query.get();
        }
        return "NOTFOUND";
    }
}
