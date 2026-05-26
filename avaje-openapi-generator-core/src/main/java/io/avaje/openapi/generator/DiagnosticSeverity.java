package io.avaje.openapi.generator;

/** Diagnostic severity emitted during generation. */
public enum DiagnosticSeverity {
  /** Informational message. */
  INFO,
  /** Warning that does not stop generation. */
  WARN,
  /** Error that should stop generation. */
  ERROR
}
