package io.avaje.openapi.generator;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

/** A generation diagnostic. */
public final class Diagnostic {

  private final DiagnosticSeverity severity;
  private final String message;

  public Diagnostic(DiagnosticSeverity severity, String message) {
    this.severity = requireNonNull(severity, "severity");
    this.message = requireNonNull(message, "message");
  }

  public DiagnosticSeverity severity() {
    return severity;
  }

  public String message() {
    return message;
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

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Diagnostic)) {
      return false;
    }
    var that = (Diagnostic) other;
    return severity == that.severity && message.equals(that.message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(severity, message);
  }

  @Override
  public String toString() {
    return "Diagnostic[severity=" + severity + ", message=" + message + "]";
  }
}
