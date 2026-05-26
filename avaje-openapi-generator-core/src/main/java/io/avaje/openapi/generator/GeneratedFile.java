package io.avaje.openapi.generator;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;

/** A Java source file generated from an OpenAPI contract. */
public record GeneratedFile(Path path, String content) {

  public GeneratedFile {
    requireNonNull(path, "path");
    requireNonNull(content, "content");
  }
}
