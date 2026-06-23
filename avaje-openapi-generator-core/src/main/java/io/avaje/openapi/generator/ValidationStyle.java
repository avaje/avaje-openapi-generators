package io.avaje.openapi.generator;

/** Validation annotation style to generate for DTO records. */
public enum ValidationStyle {
  /** Generate {@code jakarta.validation.constraints.*} annotations. */
  JAKARTA("jakarta.validation.constraints", "jakarta.validation.Valid"),

  /** Generate {@code io.avaje.validation.constraints.*} annotations. */
  AVAJE("io.avaje.validation.constraints", "io.avaje.validation.constraints.Valid");

  private final String constraintsPackage;
  private final String validClass;

  ValidationStyle(String constraintsPackage, String validClass) {
    this.constraintsPackage = constraintsPackage;
    this.validClass = validClass;
  }

  /** Return the package that contains constraint annotations. */
  public String constraintsPackage() {
    return constraintsPackage;
  }

  /**
   * Return the fully-qualified {@code @Valid} annotation. Note Jakarta places it in
   * the root {@code jakarta.validation} package rather than the constraints package.
   */
  public String validClass() {
    return validClass;
  }
}
