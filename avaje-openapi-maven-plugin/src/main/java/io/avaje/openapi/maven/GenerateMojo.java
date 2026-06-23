package io.avaje.openapi.maven;

import io.avaje.openapi.generator.DateTimeType;
import io.avaje.openapi.generator.DiagnosticSeverity;
import io.avaje.openapi.generator.GenerationMode;
import io.avaje.openapi.generator.GeneratorConfig;
import io.avaje.openapi.generator.JsonStyle;
import io.avaje.openapi.generator.OpenApiGenerator;
import io.avaje.openapi.generator.OverloadPolicy;
import io.avaje.openapi.generator.ValidationStyle;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
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

  /** JSON serialisation library style for {@code readOnly}/{@code writeOnly} annotations. */
  @Parameter(defaultValue = "AVAJE")
  private JsonStyle jsonStyle;

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

  /**
   * Generate DTO model records and enums. When {@code false} only the API
   * interfaces are generated and the referenced {@code modelPackage} types are
   * expected to be provided by an existing module on the classpath.
   */
  @Parameter(defaultValue = "true")
  private boolean generateModels;

  /**
   * The {@code java.time} type generated for {@code format: date-time} properties.
   * One of {@code INSTANT}, {@code OFFSET_DATE_TIME} (default), {@code LOCAL_DATE_TIME}
   * or {@code ZONED_DATE_TIME}. Individual properties can override this via an extended
   * {@code format} value or the {@code x-java-type} vendor extension.
   */
  @Parameter(defaultValue = "OFFSET_DATE_TIME")
  private DateTimeType dateTimeType;

  /**
   * Generate convenience {@code default} method overloads that omit a trailing run of
   * omittable parameters and delegate to the full method. Defaults to {@code false}.
   */
  @Parameter(defaultValue = "false")
  private boolean generateOverloads;

  /**
   * Policy deciding which trailing parameters are omittable when {@code generateOverloads}
   * is enabled. One of {@code EXPLICIT}, {@code NULLABLE_ONLY} (default) or
   * {@code ALL_OPTIONAL}. A per-parameter {@code x-overload} vendor extension overrides
   * the policy for that parameter.
   */
  @Parameter(defaultValue = "NULLABLE_ONLY")
  private OverloadPolicy overloadPolicy;

  /**
   * Fully-qualified {@code @Nullable} annotation applied to optional parameters and
   * {@code nullable: true} model fields. Defaults to
   * {@code org.jspecify.annotations.Nullable}. Set to blank to disable {@code @Nullable}
   * generation.
   */
  @Parameter(defaultValue = "org.jspecify.annotations.Nullable")
  private String nullableAnnotation;

  /**
   * Global type mappings keyed by schema {@code format} (e.g. {@code uuid},
   * {@code date-time}) or {@code type} (e.g. {@code string}), with fully-qualified
   * Java type names as values. A {@code format} key takes precedence over a
   * {@code type} key, and a per-property {@code x-java-type} extension takes
   * precedence over both.
   *
   * <pre>{@code
   * <typeMappings>
   *   <uuid>com.example.MyUuid</uuid>
   *   <date-time>java.time.Instant</date-time>
   * </typeMappings>
   * }</pre>
   */
  @Parameter
  private Map<String, String> typeMappings;

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
      .jsonStyle(jsonStyle)
      .recordBuilder(generateRecordBuilders)
      .clientAnnotations(generateClientAnnotations)
      .failOnUnsupported(failOnUnsupported)
      .generateModels(generateModels)
      .dateTimeType(dateTimeType)
      .generateOverloads(generateOverloads)
      .overloadPolicy(overloadPolicy)
      .nullableAnnotation(nullableAnnotation)
      .typeMappings(typeMappings)
      .build();

    var result = new OpenApiGenerator().generate(config);
    for (var diagnostic : result.diagnostics()) {
      switch (diagnostic.severity()) {
        case INFO:
          getLog().info(diagnostic.message());
          break;
        case WARN:
          getLog().warn(diagnostic.message());
          break;
        case ERROR:
          getLog().error(diagnostic.message());
          break;
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
      var sorted = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
      for (var path : sorted) {
        Files.delete(path);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to clean generated output directory " + outputPath, e);
    }
  }
}
