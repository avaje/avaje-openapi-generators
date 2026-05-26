package io.avaje.openapi.generator;

/** Validation annotation style to generate for DTO records. */
public enum ValidationStyle {
  /** Generate {@code jakarta.validation.constraints.*} annotations. */
  JAKARTA("jakarta.validation.constraints"),

  /** Generate {@code io.avaje.validation.constraints.*} annotations. */
  AVAJE("io.avaje.validation.constraints");

  private final String constraintsPackage;

  ValidationStyle(String constraintsPackage) {
    this.constraintsPackage = constraintsPackage;
  }

  /** Return the package that contains constraint annotations. */
  public String constraintsPackage() {
    return constraintsPackage;
  }
}
