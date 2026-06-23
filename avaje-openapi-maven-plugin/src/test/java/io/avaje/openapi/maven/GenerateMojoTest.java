package io.avaje.openapi.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.avaje.openapi.generator.DateTimeType;
import io.avaje.openapi.generator.GenerationMode;
import io.avaje.openapi.generator.ValidationStyle;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateMojoTest {

  @TempDir
  Path tempDir;

  @Test
  void executeGeneratesSourcesAndAddsCompileRoot() throws Exception {
    var output = tempDir.resolve("generated");
    var project = new MavenProject();
    var mojo = new GenerateMojo();
    set(mojo, "inputSpec", resourcePath("openapi/pets.yaml").toFile());
    set(mojo, "outputDirectory", output.toFile());
    set(mojo, "apiPackage", "org.example.api");
    set(mojo, "mode", GenerationMode.CONTRACT);
    set(mojo, "generateValidationAnnotations", true);
    set(mojo, "validationStyle", ValidationStyle.AVAJE);
    set(mojo, "generateJsonAnnotations", true);
    set(mojo, "generateRecordBuilders", true);
    set(mojo, "generateClientAnnotations", true);
    set(mojo, "failOnUnsupported", true);
    set(mojo, "generateModels", true);
    set(mojo, "dateTimeType", DateTimeType.OFFSET_DATE_TIME);
    set(mojo, "cleanOutput", true);
    set(mojo, "project", project);

    mojo.execute();

    assertThat(output.resolve("org/example/api/PetsApi.java")).exists();
    assertThat(output.resolve("org/example/api/model/Pet.java"))
      .content()
      .contains("@RecordBuilder")
      .contains("import io.avaje.validation.constraints.NotNull;")
      .contains("public static PetBuilder builder()");
    assertThat(project.getCompileSourceRoots()).contains(output.toAbsolutePath().toString());
  }

  private static void set(Object target, String fieldName, Object value) throws Exception {
    Field field = GenerateMojo.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Path resourcePath(String name) throws URISyntaxException {
    return Path.of(GenerateMojoTest.class.getClassLoader().getResource(name).toURI());
  }
}
