package io.avaje.openapi.generator;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;

/** The result of generating Java source from an OpenAPI contract. */
public final class GenerationResult {

  private final List<GeneratedFile> generatedFiles;
  private final List<Diagnostic> diagnostics;

  public GenerationResult(List<GeneratedFile> generatedFiles, List<Diagnostic> diagnostics) {
    this.generatedFiles = List.copyOf(requireNonNull(generatedFiles, "generatedFiles"));
    this.diagnostics = List.copyOf(requireNonNull(diagnostics, "diagnostics"));
  }

  public List<GeneratedFile> generatedFiles() {
    return generatedFiles;
  }

  public List<Diagnostic> diagnostics() {
    return diagnostics;
  }

  /** Return true if any error diagnostics were emitted. */
  public boolean hasErrors() {
    return diagnostics.stream().anyMatch(it -> it.severity() == DiagnosticSeverity.ERROR);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof GenerationResult)) {
      return false;
    }
    var that = (GenerationResult) other;
    return generatedFiles.equals(that.generatedFiles) && diagnostics.equals(that.diagnostics);
  }

  @Override
  public int hashCode() {
    return Objects.hash(generatedFiles, diagnostics);
  }

  @Override
  public String toString() {
    return "GenerationResult[generatedFiles=" + generatedFiles + ", diagnostics=" + diagnostics + "]";
  }
}
