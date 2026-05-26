package io.avaje.openapi.generator;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.Objects;

/** A Java source file generated from an OpenAPI contract. */
public final class GeneratedFile {

  private final Path path;
  private final String content;

  public GeneratedFile(Path path, String content) {
    this.path = requireNonNull(path, "path");
    this.content = requireNonNull(content, "content");
  }

  public Path path() {
    return path;
  }

  public String content() {
    return content;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof GeneratedFile)) {
      return false;
    }
    var that = (GeneratedFile) other;
    return path.equals(that.path) && content.equals(that.content);
  }

  @Override
  public int hashCode() {
    return Objects.hash(path, content);
  }

  @Override
  public String toString() {
    return "GeneratedFile[path=" + path + ", content=" + content + "]";
  }
}
