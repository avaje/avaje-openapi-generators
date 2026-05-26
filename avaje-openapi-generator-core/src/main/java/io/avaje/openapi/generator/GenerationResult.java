package io.avaje.openapi.generator;

import static java.util.Objects.requireNonNull;

import java.util.List;

/** The result of generating Java source from an OpenAPI contract. */
public record GenerationResult(List<GeneratedFile> generatedFiles, List<Diagnostic> diagnostics) {

  public GenerationResult {
    generatedFiles = List.copyOf(requireNonNull(generatedFiles, "generatedFiles"));
    diagnostics = List.copyOf(requireNonNull(diagnostics, "diagnostics"));
  }

  /** Return true if any error diagnostics were emitted. */
  public boolean hasErrors() {
    return diagnostics.stream().anyMatch(it -> it.severity() == DiagnosticSeverity.ERROR);
  }
}
