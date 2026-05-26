package io.avaje.openapi.generator;

import static java.util.Objects.requireNonNull;

/** A generation diagnostic. */
public record Diagnostic(DiagnosticSeverity severity, String message) {

  public Diagnostic {
    requireNonNull(severity, "severity");
    requireNonNull(message, "message");
  }

  /** Create an informational diagnostic. */
  public static Diagnostic info(String message) {
    return new Diagnostic(DiagnosticSeverity.INFO, message);
  }

  /** Create a warning diagnostic. */
  public static Diagnostic warn(String message) {
    return new Diagnostic(DiagnosticSeverity.WARN, message);
  }

  /** Create an error diagnostic. */
  public static Diagnostic error(String message) {
    return new Diagnostic(DiagnosticSeverity.ERROR, message);
  }
}
