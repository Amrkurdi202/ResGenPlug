import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.utils.SourceRoot;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;

@Mojo(name = "ResGenPlug", defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class EntityFieldScannerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}/src/main/java")
    private File sourceDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/main/resources/strings")
    private File resourcesDirectory;

    private static final String ENTITY_SUPERCLASS = "Entity";
    private static final String RES_SUPERCLASS = "Res";

    private static final String[] POTENTIAL_ANCESTORS = {RES_SUPERCLASS,
            ENTITY_SUPERCLASS};

    private Map<String, Set<String>> classHierarchy;
    private File[] propertyFiles;
    private Map<String, Properties> properties;

    @Override
    public void execute() throws MojoExecutionException {
        classHierarchy = new HashMap<>();
        for (int i = 0; i <2 ; i++) {
            try {
                properties = new HashMap<>();
                propertyFiles = resourcesDirectory.listFiles((dir, name) -> name.endsWith(".properties"));
                if (propertyFiles == null) {
                    getLog().info("No resources found");
                    return;
                }
                for (File propertyFile : propertyFiles) {
                    Properties properties = new Properties();
                    InputStream inputStream = propertyFile.toURI().toURL().openStream();
                    try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                        properties.load(reader);
                    }
                    this.properties.put(propertyFile.getName(), properties);
                }

                SourceRoot sourceRoot = new SourceRoot(Paths.get(sourceDirectory.toURI()));


                List<ParseResult<CompilationUnit>> compilationUnits = sourceRoot.tryToParse();

                for (ParseResult<CompilationUnit> parseResult : compilationUnits) {
                    Optional<CompilationUnit> result = parseResult.getResult();
                    result.ifPresent(compilationUnit -> {
                        compilationUnit.accept(new ClassVisitor(), classHierarchy);
                    });
                }
                for (ParseResult<CompilationUnit> parseResult : compilationUnits) {
                    Optional<CompilationUnit> result = parseResult.getResult();
                    result.ifPresent(compilationUnit -> {
                        compilationUnit.findAll(ClassOrInterfaceDeclaration.class).forEach(this::processClass);
                    });
                }

            } catch (IOException e) {
                throw new MojoExecutionException("Error parsing source files", e);
            }
            for (File propertyFile : propertyFiles) {
                try (FileWriter writer = new FileWriter(propertyFile, StandardCharsets.UTF_8, false)) {
                    properties.get(propertyFile.getName()).store(writer, null);
                } catch (IOException e) {
                    getLog().error(e);
                    throw new RuntimeException(e);
                }
            }
        }
        System.out.println(classHierarchy);
    }

    private void processClass(ClassOrInterfaceDeclaration classDeclaration) {
        getLog().info("Found class: " + classDeclaration.getNameAsString());
        if (isAncestor(classDeclaration.getNameAsString(), ENTITY_SUPERCLASS, classHierarchy)) {
            getLog().info("Found entity: " + classDeclaration.getFullyQualifiedName().orElse(""));
            String className = classDeclaration.getFullyQualifiedName().orElse("");
            Set<String> fieldNames = new HashSet<>();

            classDeclaration.findAll(FieldDeclaration.class).forEach(field -> {
                for (VariableDeclarator variable : field.getVariables()) {
                    String typeAsString = variable.getTypeAsString();
                    int endIndex = typeAsString.lastIndexOf('<');
                    if (endIndex != -1) {
                        typeAsString = typeAsString.substring(0, endIndex);
                        getLog().info("Found generic type: " + typeAsString);
                    }
                    typeAsString = typeAsString.replaceAll("\\s", "");
                    if (isAncestorOfAny(typeAsString, classHierarchy)) {
                        getLog().info("Found field: " + variable.getNameAsString());
                        fieldNames.add(variable.getNameAsString());
                    }
                }
            });

            updatePropertiesFile(className, fieldNames);
        }
    }

    private static class ClassVisitor extends VoidVisitorAdapter<Map<String, Set<String>>> {
        @Override
        public void visit(ClassOrInterfaceDeclaration cid, Map<String, Set<String>> classHierarchy) {
            super.visit(cid, classHierarchy);
            String className = cid.getNameAsString();
            Set<String> supers = classHierarchy.computeIfAbsent(className, k -> new HashSet<>());
            cid.getExtendedTypes().forEach(extendedType -> {
                String superClassName = extendedType.getNameAsString();
                supers.add(superClassName);
            });
            cid.getImplementedTypes().forEach(implementedType -> {
                String interfaceName = implementedType.getNameAsString();
                supers.add(interfaceName);
            });
        }
    }

    private boolean isAncestorOfAny(String className, Map<String, Set<String>> classHierarchy) {
        for (String ancestor : EntityFieldScannerMojo.POTENTIAL_ANCESTORS) {
            if (isAncestor(className, ancestor, classHierarchy)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAncestor(String className, String potentialAncestor, Map<String, Set<String>> classHierarchy) {
        if (classHierarchy.containsKey(className)) {
            Set<String> superClasses = classHierarchy.get(className);
            for (String superClass : superClasses) {
                if (superClass.equals(potentialAncestor)) {
                    return true;
                }
            }
            for (String superClass : superClasses) {
                return isAncestor(superClass, potentialAncestor, classHierarchy);
            }
        }
        return false;
    }

    private void updatePropertiesFile(String className, Set<String> fieldNames) {

        for (File propertyFile : propertyFiles) {
            Properties props = properties.get(propertyFile.getName());

            if (!props.containsKey(className))
                props.setProperty(className, "");

            String packageName = className.replaceAll(".\\w*$", "");
            if (!props.containsKey(packageName))
                props.setProperty(packageName, "");


            for (String fieldName : fieldNames) {
                String key = className + "." + fieldName;
                if (!props.containsKey(key))
                    props.setProperty(key, "");
            }
        }
    }
}