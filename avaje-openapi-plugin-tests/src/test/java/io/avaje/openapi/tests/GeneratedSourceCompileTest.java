package io.avaje.openapi.tests;

import static org.assertj.core.api.Assertions.assertThat;

import io.avaje.openapi.generator.DiagnosticSeverity;
import io.avaje.openapi.generator.GeneratorConfig;
import io.avaje.openapi.generator.OpenApiGenerator;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedSourceCompileTest {

  @TempDir
  Path tempDir;

  @Test
  void generatedSourcesCompileWithAvajeApiDependencies() throws Exception {
    var sourceDir = tempDir.resolve("generated");
    var classesDir = tempDir.resolve("classes");
    Files.createDirectories(classesDir);
    var config = GeneratorConfig.builder(resourcePath("openapi/pets.yaml"), sourceDir, "org.example.api").build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();
    assertThat(compile(sourceDir, classesDir)).isEqualTo(0);
  }

  private static int compile(Path sourceDir, Path classesDir) throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).describedAs("JDK compiler").isNotNull();
    try (var files = Files.walk(sourceDir);
         var fileManager = compiler.getStandardFileManager(null, null, null)) {
      var sources = files.filter(path -> path.toString().endsWith(".java")).toList();
      var units = fileManager.getJavaFileObjectsFromPaths(sources);
      var options = java.util.List.of(
        "--release", "21",
        "-classpath", System.getProperty("java.class.path"),
        "-d", classesDir.toString());
      return compiler.getTask(null, fileManager, null, options, null, units).call() ? 0 : 1;
    }
  }

  private static Path resourcePath(String name) throws URISyntaxException {
    return Path.of(GeneratedSourceCompileTest.class.getClassLoader().getResource(name).toURI());
  }
}
