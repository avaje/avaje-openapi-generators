package io.avaje.openapi.generator;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;

/** Configuration for OpenAPI to Avaje Java source generation. */
public final class GeneratorConfig {

  private final Path inputSpec;
  private final Path outputDirectory;
  private final String apiPackage;
  private final String modelPackage;
  private final GenerationMode mode;
  private final boolean validationAnnotations;
  private final ValidationStyle validationStyle;
  private final boolean jsonAnnotations;
  private final boolean recordBuilder;
  private final boolean clientAnnotations;
  private final boolean failOnUnsupported;
  private final boolean generateModels;

  public GeneratorConfig(
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
    boolean failOnUnsupported,
    boolean generateModels) {

    this.inputSpec = requireNonNull(inputSpec, "inputSpec");
    this.outputDirectory = requireNonNull(outputDirectory, "outputDirectory");
    this.apiPackage = requireNonNull(apiPackage, "apiPackage");
    if (this.apiPackage.isBlank()) {
      throw new IllegalArgumentException("apiPackage must not be blank");
    }
    this.modelPackage = modelPackage == null || modelPackage.isBlank() ? apiPackage + ".model" : modelPackage;
    this.mode = mode == null ? GenerationMode.CONTRACT : mode;
    this.validationAnnotations = validationAnnotations;
    this.validationStyle = validationStyle == null ? ValidationStyle.JAKARTA : validationStyle;
    this.jsonAnnotations = jsonAnnotations;
    this.recordBuilder = recordBuilder;
    this.clientAnnotations = clientAnnotations;
    this.failOnUnsupported = failOnUnsupported;
    this.generateModels = generateModels;
  }

  public Path inputSpec() {
    return inputSpec;
  }

  public Path outputDirectory() {
    return outputDirectory;
  }

  public String apiPackage() {
    return apiPackage;
  }

  public String modelPackage() {
    return modelPackage;
  }

  public GenerationMode mode() {
    return mode;
  }

  public boolean validationAnnotations() {
    return validationAnnotations;
  }

  public ValidationStyle validationStyle() {
    return validationStyle;
  }

  public boolean jsonAnnotations() {
    return jsonAnnotations;
  }

  public boolean recordBuilder() {
    return recordBuilder;
  }

  public boolean clientAnnotations() {
    return clientAnnotations;
  }

  public boolean failOnUnsupported() {
    return failOnUnsupported;
  }

  /**
   * When {@code false}, DTO model records/enums are not generated; only the API
   * interfaces are generated. The interfaces still reference {@code modelPackage}
   * types, which are expected to be provided by an existing (hand-written) module
   * on the classpath. Defaults to {@code true}.
   */
  public boolean generateModels() {
    return generateModels;
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
    private boolean generateModels = true;

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

    public Builder generateModels(boolean generateModels) {
      this.generateModels = generateModels;
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
        failOnUnsupported,
        generateModels);
    }
  }
}
