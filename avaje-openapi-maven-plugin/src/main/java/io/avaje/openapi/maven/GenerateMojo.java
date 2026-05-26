package io.avaje.openapi.maven;

import io.avaje.openapi.generator.DiagnosticSeverity;
import io.avaje.openapi.generator.GenerationMode;
import io.avaje.openapi.generator.GeneratorConfig;
import io.avaje.openapi.generator.OpenApiGenerator;
import io.avaje.openapi.generator.ValidationStyle;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** Generate Avaje HTTP contracts from an OpenAPI specification. */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public final class GenerateMojo extends AbstractMojo {

  /** OpenAPI YAML or JSON file. */
  @Parameter(required = true)
  private File inputSpec;

  /** Output directory for generated Java source. */
  @Parameter(defaultValue = "${project.build.directory}/generated-sources/avaje-openapi")
  private File outputDirectory;

  /** Package for generated API interfaces. */
  @Parameter(required = true)
  private String apiPackage;

  /** Package for generated DTOs and enums. Defaults to {@code apiPackage + ".model"}. */
  @Parameter
  private String modelPackage;

  /** Generation mode. Only CONTRACT is implemented initially. */
  @Parameter(defaultValue = "CONTRACT")
  private GenerationMode mode;

  /** Generate validation annotations where safe. */
  @Parameter(defaultValue = "true")
  private boolean generateValidationAnnotations;

  /** Validation annotation style to generate when validation annotations are enabled. */
  @Parameter(defaultValue = "JAKARTA")
  private ValidationStyle validationStyle;

  /** Generate Avaje Jsonb annotations on models. */
  @Parameter(defaultValue = "true")
  private boolean generateJsonAnnotations;

  /** Generate Avaje RecordBuilder annotations and builder factory methods on DTO records. */
  @Parameter(defaultValue = "false")
  private boolean generateRecordBuilders;

  /** Generate {@code @Client} annotations on API interfaces. */
  @Parameter(defaultValue = "true")
  private boolean generateClientAnnotations;

  /** Fail the build when an unsupported OpenAPI feature is encountered. */
  @Parameter(defaultValue = "true")
  private boolean failOnUnsupported;

  /** Clean the generated output directory before generating. */
  @Parameter(defaultValue = "true")
  private boolean cleanOutput;

  /** Maven project. */
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Override
  public void execute() throws MojoExecutionException {
    if (inputSpec == null || !inputSpec.isFile()) {
      throw new MojoExecutionException("inputSpec does not exist or is not a file: " + inputSpec);
    }
    if (mode != GenerationMode.CONTRACT) {
      throw new MojoExecutionException("Only CONTRACT generation mode is currently implemented");
    }
    var outputPath = outputDirectory.toPath();
    if (cleanOutput) {
      clean(outputPath);
    }

    var config = GeneratorConfig.builder(inputSpec.toPath(), outputPath, apiPackage)
      .modelPackage(modelPackage)
      .mode(mode)
      .validationAnnotations(generateValidationAnnotations)
      .validationStyle(validationStyle)
      .jsonAnnotations(generateJsonAnnotations)
      .recordBuilder(generateRecordBuilders)
      .clientAnnotations(generateClientAnnotations)
      .failOnUnsupported(failOnUnsupported)
      .build();

    var result = new OpenApiGenerator().generate(config);
    for (var diagnostic : result.diagnostics()) {
      switch (diagnostic.severity()) {
        case INFO -> getLog().info(diagnostic.message());
        case WARN -> getLog().warn(diagnostic.message());
        case ERROR -> getLog().error(diagnostic.message());
      }
    }
    for (var file : result.generatedFiles()) {
      getLog().debug("Generated " + file.path());
    }
    project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
    getLog().info("Generated " + result.generatedFiles().size() + " OpenAPI source files into " + outputDirectory);
    if (result.hasErrors()) {
      throw new MojoExecutionException("OpenAPI generation failed");
    }
  }

  private void clean(Path outputPath) throws MojoExecutionException {
    if (!Files.exists(outputPath)) {
      return;
    }
    try (var paths = Files.walk(outputPath)) {
      var sorted = paths.sorted(Comparator.reverseOrder()).toList();
      for (var path : sorted) {
        Files.delete(path);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to clean generated output directory " + outputPath, e);
    }
  }
}
