package dev.bandeira.prsentinel.agent.application;

import dev.bandeira.prsentinel.agent.application.port.FindingSource;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import dev.bandeira.prsentinel.common.event.Finding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fake do LLM. Responde por arquivo e pode ser mandado falhar — em qualquer chamada ou só na de um
 * arquivo específico, que é como se exercita a falha no meio do diff.
 */
final class FakeFindingSource implements FindingSource {

  private final Map<String, List<Finding>> responses = new HashMap<>();
  private final Map<String, RuntimeException> failures = new HashMap<>();
  private final List<DiffChunk> analyzed = new ArrayList<>();
  private RuntimeException failure;

  void respondWith(String file, Finding... findings) {
    responses.put(file, List.of(findings));
  }

  void failWith(RuntimeException exception) {
    this.failure = exception;
  }

  void failOn(String file, RuntimeException exception) {
    failures.put(file, exception);
  }

  List<DiffChunk> analyzed() {
    return List.copyOf(analyzed);
  }

  @Override
  public List<Finding> findIn(DiffChunk chunk) {
    analyzed.add(chunk);
    if (failure != null) {
      throw failure;
    }
    RuntimeException perFile = failures.get(chunk.file());
    if (perFile != null) {
      throw perFile;
    }
    return responses.getOrDefault(chunk.file(), List.of());
  }
}
