package dev.bandeira.prsentinel.difffetcher.domain;

import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import java.util.ArrayList;
import java.util.List;

/**
 * Fatia o patch de um arquivo em pedaços que caibam no contexto do LLM.
 *
 * <p>O corte acontece em fronteira de hunk: cada hunk de um diff unificado começa com {@code @@
 * -a,b +c,d @@} e é esse cabeçalho que diz a que linhas do arquivo aquele trecho corresponde. Hunk
 * que sozinho estoura o limite é quebrado por linha, e cada pedaço recebe o cabeçalho repetido —
 * sem isso o agente perde a referência de posição e o {@code line} do achado aponta para o lugar
 * errado quando a fase 6 for comentar inline no PR (ADR-007).
 */
public final class DiffChunker {

  private static final String HUNK_MARKER = "@@";

  private final int maxChunkChars;

  public DiffChunker(int maxChunkChars) {
    if (maxChunkChars <= 0) {
      throw new IllegalArgumentException("maxChunkChars precisa ser positivo: " + maxChunkChars);
    }
    this.maxChunkChars = maxChunkChars;
  }

  public List<DiffChunk> chunk(ChangedFile file) {
    if (!file.hasPatch()) {
      return List.of();
    }

    List<String> pieces = split(file.patch());
    String language = Language.of(file.path());

    List<DiffChunk> chunks = new ArrayList<>(pieces.size());
    for (int index = 0; index < pieces.size(); index++) {
      chunks.add(new DiffChunk(file.path(), language, index, pieces.get(index)));
    }
    return List.copyOf(chunks);
  }

  private List<String> split(String patch) {
    List<String> pieces = new ArrayList<>();
    StringBuilder current = new StringBuilder();

    for (String hunk : hunksOf(patch)) {
      if (hunk.length() > maxChunkChars) {
        flush(current, pieces);
        pieces.addAll(breakApart(hunk));
        continue;
      }
      if (current.isEmpty()) {
        current.append(hunk);
      } else if (current.length() + 1 + hunk.length() <= maxChunkChars) {
        current.append('\n').append(hunk);
      } else {
        flush(current, pieces);
        current.append(hunk);
      }
    }

    flush(current, pieces);
    return pieces;
  }

  /**
   * Agrupa as linhas do patch em hunks, um por marcador {@code @@}.
   *
   * <p>O que vier antes do primeiro marcador — que a API do GitHub não manda, mas que um diff cru
   * teria — vira um grupo próprio. Patch sem marcador nenhum vira um grupo único. Nos dois casos o
   * conteúdo segue inteiro para o agente em vez de ser descartado.
   */
  private static List<String> hunksOf(String patch) {
    // split com limite negativo preserva linhas vazias no fim: assim juntar os pedaços de volta
    // reproduz o patch original caractere por caractere.
    String[] lines = patch.split("\n", -1);

    List<String> hunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : lines) {
      if (line.startsWith(HUNK_MARKER) && !current.isEmpty()) {
        hunks.add(current.toString());
        current.setLength(0);
      }
      if (!current.isEmpty()) {
        current.append('\n');
      }
      current.append(line);
    }
    if (!current.isEmpty()) {
      hunks.add(current.toString());
    }
    return hunks;
  }

  /** Quebra um hunk grande demais por linha, repetindo o cabeçalho em cada pedaço. */
  private List<String> breakApart(String hunk) {
    String[] lines = hunk.split("\n", -1);
    boolean hasHeader = lines[0].startsWith(HUNK_MARKER);
    String header = hasHeader ? lines[0] : "";
    int firstBodyLine = hasHeader ? 1 : 0;

    List<String> pieces = new ArrayList<>();
    StringBuilder current = new StringBuilder(header);
    boolean hasBody = false;

    for (int i = firstBodyLine; i < lines.length; i++) {
      String line = lines[i];
      int lengthWithLine = current.length() + (current.isEmpty() ? 0 : 1) + line.length();

      // Fecha o pedaço antes de estourar, mas nunca fecha um pedaço vazio: linha que não cabe
      // nem sozinha sai num chunk só dela, estourando o limite. Cortar no meio da linha
      // entregaria ao LLM um trecho sem sentido, que é pior do que um prompt grande.
      if (hasBody && lengthWithLine > maxChunkChars) {
        pieces.add(current.toString());
        current = new StringBuilder(header);
        hasBody = false;
      }

      if (!current.isEmpty()) {
        current.append('\n');
      }
      current.append(line);
      hasBody = true;
    }

    if (hasBody || !current.isEmpty()) {
      pieces.add(current.toString());
    }
    return pieces;
  }

  private static void flush(StringBuilder current, List<String> pieces) {
    if (!current.isEmpty()) {
      pieces.add(current.toString());
      current.setLength(0);
    }
  }
}
