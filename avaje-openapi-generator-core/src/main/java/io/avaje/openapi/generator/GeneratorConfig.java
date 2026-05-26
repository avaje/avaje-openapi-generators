package io.avaje.openapi.generator;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;

/** Configuration for OpenAPI to Avaje Java source generation. */
public record GeneratorConfig(
  Path inputSpec,
  Path outputDirectory,
  String apiPackage,
  String modelPackage,
  GenerationMode mode,
  boolean validationAnnotations,
  ValidationStyle validationStyle,
  boolean jsonAnnotations,
  boolean recordBuilder,
  boolean clientAnnotations,
  boolean failOnUnsupported) {

  public GeneratorConfig {
    requireNonNull(inputSpec, "inputSpec");
    requireNonNull(outputDirectory, "outputDirectory");
    requireNonNull(apiPackage, "apiPackage");
    if (apiPackage.isBlank()) {
      throw new IllegalArgumentException("apiPackage must not be blank");
    }
    modelPackage = modelPackage == null || modelPackage.isBlank() ? apiPackage + ".model" : modelPackage;
    mode = mode == null ? GenerationMode.CONTRACT : mode;
    validationStyle = validationStyle == null ? ValidationStyle.JAKARTA : validationStyle;
  }

  /** Return a builder with required values. */
  public static Builder builder(Path inputSpec, Path outputDirectory, String apiPackage) {
    return new Builder(inputSpec, outputDirectory, apiPackage);
  }

  /** Builder for {@link GeneratorConfig}. */
  public static final class Builder {
    private final Path inputSpec;
    private final Path outputDirectory;
    private final String apiPackage;
    private String modelPackage;
    private GenerationMode mode = GenerationMode.CONTRACT;
    private boolean validationAnnotations = true;
    private ValidationStyle validationStyle = ValidationStyle.JAKARTA;
    private boolean jsonAnnotations = true;
    private boolean recordBuilder;
    private boolean clientAnnotations = true;
    private boolean failOnUnsupported = true;

    private Builder(Path inputSpec, Path outputDirectory, String apiPackage) {
      this.inputSpec = inputSpec;
      this.outputDirectory = outputDirectory;
      this.apiPackage = apiPackage;
    }

    public Builder modelPackage(String modelPackage) {
      this.modelPackage = modelPackage;
      return this;
    }

    public Builder mode(GenerationMode mode) {
      this.mode = mode;
      return this;
    }

    public Builder validationAnnotations(boolean validationAnnotations) {
      this.validationAnnotations = validationAnnotations;
      return this;
    }

    public Builder validationStyle(ValidationStyle validationStyle) {
      this.validationStyle = validationStyle;
      return this;
    }

    public Builder jsonAnnotations(boolean jsonAnnotations) {
      this.jsonAnnotations = jsonAnnotations;
      return this;
    }

    public Builder recordBuilder(boolean recordBuilder) {
      this.recordBuilder = recordBuilder;
      return this;
    }

    public Builder clientAnnotations(boolean clientAnnotations) {
      this.clientAnnotations = clientAnnotations;
      return this;
    }

    public Builder failOnUnsupported(boolean failOnUnsupported) {
      this.failOnUnsupported = failOnUnsupported;
      return this;
    }

    public GeneratorConfig build() {
      return new GeneratorConfig(
        inputSpec,
        outputDirectory,
        apiPackage,
        modelPackage,
        mode,
        validationAnnotations,
        validationStyle,
        jsonAnnotations,
        recordBuilder,
        clientAnnotations,
        failOnUnsupported);
    }
  }
}
