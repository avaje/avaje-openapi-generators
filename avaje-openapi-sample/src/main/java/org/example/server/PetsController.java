package org.example.server;

import io.avaje.http.api.Controller;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.example.api.PetsApi;
import org.example.api.model.CreatePetRequest;
import org.example.api.model.Pet;

@Controller
public final class PetsController implements PetsApi {

  private static final OffsetDateTime EPOCH = OffsetDateTime.parse("1970-01-01T00:00:00Z");

  @Override
  public List<Pet> listPets(Integer limit) {
    return List.of(new Pet(1L, "Dora", EPOCH));
  }

  @Override
  public Stream<Pet> streamPets() {
    return Stream.of(new Pet(1L, "Dora", EPOCH), new Pet(2L, "Boots", EPOCH));
  }

  @Override
  public Pet createPet(CreatePetRequest createPetRequest) {
    return new Pet(2L, createPetRequest.name(), EPOCH);
  }

  @Override
  public Pet getPet(Long id, boolean useMaster) {
    return new Pet(id, "Dora", EPOCH);
  }
}
