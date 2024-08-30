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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

@Mojo(name = "ResGenPlug", defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class EntityFieldScannerMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project.basedir}/src/main/java")
  private File sourceDirectory;

  @Parameter(defaultValue = "${project.basedir}/src/main/resources/strings")
  private File resourcesDirectory;

  private static final String ENTITY_SUPERCLASS = "Entity";
  private static final String FLD_SUPERCLASS = "Fld";
  private Map<String, String> classHierarchy;
  private File[] propertyFiles;
  private Map<String, Properties> properties;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      properties = new HashMap<>();
      propertyFiles = resourcesDirectory.listFiles((dir, name) -> name.endsWith(".properties"));
      if (propertyFiles == null) {
        getLog().info("No resources found");
        return;
      }
      for (File propertyFile : propertyFiles) {
        Properties properties = new Properties();
        properties.load(propertyFile.toURI().toURL().openStream());
        this.properties.put(propertyFile.getName(), properties);
      }

      SourceRoot sourceRoot = new SourceRoot(Paths.get(sourceDirectory.toURI()));
      classHierarchy = new HashMap<>();

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
      try (FileWriter writer = new FileWriter(propertyFile, false)) {
        properties.get(propertyFile.getName()).store(writer, null);
      } catch (IOException e) {
        getLog().error(e);
        throw new RuntimeException(e);
      }
    }
  }

  private void processClass(ClassOrInterfaceDeclaration classDeclaration) {
    getLog().info("Found class: " + classDeclaration.getNameAsString());
    if (isAncestor(classDeclaration.getNameAsString(), ENTITY_SUPERCLASS, classHierarchy)) {
      getLog().info("Found entity: " + classDeclaration.getFullyQualifiedName().orElse(""));
      String className = classDeclaration.getFullyQualifiedName().orElse("");
      Set<String> fieldNames = new HashSet<>();

      classDeclaration.findAll(FieldDeclaration.class).forEach(field -> {
        for (VariableDeclarator variable : field.getVariables()) {
          getLog().info("Found field: " + variable.getNameAsString());
          if (isAncestor(variable.getTypeAsString(), FLD_SUPERCLASS, classHierarchy)) {
            getLog().info("Found FLD: " + variable.getNameAsString());
            fieldNames.add(variable.getNameAsString());
          }
        }
      });

      updatePropertiesFile(className, fieldNames);
    }
  }

  private static class ClassVisitor extends VoidVisitorAdapter<Map<String, String>> {
    @Override
    public void visit(ClassOrInterfaceDeclaration cid, Map<String, String> classHierarchy) {
      super.visit(cid, classHierarchy);
      String className = cid.getNameAsString();
      cid.getExtendedTypes().forEach(extendedType -> {
        String superClassName = extendedType.getNameAsString();
        classHierarchy.put(className, superClassName);
      });
      cid.getImplementedTypes().forEach(implementedType -> {
        String interfaceName = implementedType.getNameAsString();
        classHierarchy.put(className, interfaceName);
      });
    }
  }

  private static boolean isAncestor(String className, String potentialAncestor, Map<String, String> classHierarchy) {
    String currentClass = className;
    while (classHierarchy.containsKey(currentClass)) {
      String superClass = classHierarchy.get(currentClass);
      if (superClass.equals(potentialAncestor)) {
        return true;
      }
      currentClass = superClass;
    }
    return false;
  }

  private void updatePropertiesFile(String className, Set<String> fieldNames) {

    for (File propertyFile : propertyFiles) {
      Properties props = properties.get(propertyFile.getName());
      for (String fieldName : fieldNames) {
        String key = className + "." + fieldName;
        if (!props.containsKey(key))
          props.setProperty(key, "");

        if (!props.containsKey(className))
          props.setProperty(className, "");

        String packageName = className.replaceAll(".\\w*$", "");
        if (!props.containsKey(packageName))
          props.setProperty(packageName, "");

      }
    }
  }
}