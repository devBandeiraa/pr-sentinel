package dev.bandeira.prsentinel.difffetcher.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fatiamento do patch de um arquivo.
 *
 * <p>É o teste mais importante do módulo: o que sai daqui é exatamente o que vai no prompt dos três
 * agentes. Chunk cortado no meio de uma linha, ou sem o cabeçalho {@code @@} do hunk, entrega ao
 * agente um trecho sem referência de posição — e aí o {@code line} do achado aponta para o lugar
 * errado quando a fase 6 for comentar inline no PR (ADR-007).
 */
class DiffChunkerTest {

  private static final String HUNK_1 =
      """
      @@ -1,3 +1,4 @@
       contexto a
      -removida b
      +adicionada b
      +adicionada c""";

  private static final String HUNK_2 =
      """
      @@ -20,2 +21,3 @@
       contexto d
      +adicionada e""";

  @Test
  void patchQueCabeNoLimiteViraUmChunkSo() {
    DiffChunker chunker = new DiffChunker(1_000);

    List<DiffChunk> chunks = chunker.chunk(file(HUNK_1 + "\n" + HUNK_2));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).patch()).isEqualTo(HUNK_1 + "\n" + HUNK_2);
    assertThat(chunks.get(0).chunkIndex()).isZero();
  }

  @Test
  void preencheArquivoELinguagemEmTodoChunk() {
    DiffChunker chunker = new DiffChunker(60);

    List<DiffChunk> chunks = chunker.chunk(file(HUNK_1 + "\n" + HUNK_2));

    assertThat(chunks).isNotEmpty();
    assertThat(chunks)
        .allSatisfy(
            chunk -> {
              assertThat(chunk.file()).isEqualTo("src/main/java/App.java");
              assertThat(chunk.language()).isEqualTo("java");
            });
  }

  @Test
  void fechaOChunkNaFronteiraDoHunkQuandoOsDoisNaoCabemJuntos() {
    // Os dois hunks somados passam do limite, mas cada um cabe sozinho.
    DiffChunker chunker = new DiffChunker(HUNK_1.length() + 10);

    List<DiffChunk> chunks = chunker.chunk(file(HUNK_1 + "\n" + HUNK_2));

    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0).patch()).isEqualTo(HUNK_1);
    assertThat(chunks.get(1).patch()).isEqualTo(HUNK_2);
  }

  @Test
  void todoChunkComecaComCabecalhoDeHunk() {
    DiffChunker chunker = new DiffChunker(40);

    List<DiffChunk> chunks = chunker.chunk(file(HUNK_1 + "\n" + HUNK_2));

    assertThat(chunks)
        .allSatisfy(chunk -> assertThat(chunk.patch()).startsWith("@@ -"))
        .hasSizeGreaterThan(1);
  }

  @Test
  void hunkGrandeDemaisEhQuebradoRepetindoOCabecalho() {
    String header = "@@ -1,100 +1,100 @@";
    String body =
        """
        +linha 1
        +linha 2
        +linha 3
        +linha 4
        +linha 5""";
    // Cabe o cabeçalho e umas duas linhas por chunk, não as cinco.
    DiffChunker chunker = new DiffChunker(header.length() + 20);

    List<DiffChunk> chunks = chunker.chunk(file(header + "\n" + body));

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.patch()).startsWith(header));

    // Nenhuma linha pode ter sido cortada no meio: todas as linhas de conteúdo dos chunks,
    // na ordem, têm de reconstituir exatamente o corpo original.
    List<String> contentLines =
        chunks.stream().flatMap(chunk -> chunk.patch().lines().skip(1)).toList();
    assertThat(String.join("\n", contentLines)).isEqualTo(body);
  }

  @Test
  void linhaMaiorQueOLimiteViraUmChunkMesmoEstourando() {
    // Linha minificada de 500 caracteres com limite de 50: não há como caber. Estourar é melhor
    // que cortar no meio e mandar um trecho sem sentido para o LLM.
    String header = "@@ -1 +1 @@";
    String longLine = "+" + "x".repeat(500);
    DiffChunker chunker = new DiffChunker(50);

    List<DiffChunk> chunks = chunker.chunk(file(header + "\n" + longLine));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).patch()).isEqualTo(header + "\n" + longLine);
  }

  @Test
  void chunkIndexEhSequencialEComecaEmZero() {
    DiffChunker chunker = new DiffChunker(40);

    List<DiffChunk> chunks = chunker.chunk(file(HUNK_1 + "\n" + HUNK_2 + "\n" + HUNK_1));

    assertThat(chunks.stream().map(DiffChunk::chunkIndex).toList())
        .containsExactlyElementsOf(
            java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
  }

  @Test
  void limiteExatoAindaCabeNoMesmoChunk() {
    String patch = HUNK_1 + "\n" + HUNK_2;
    DiffChunker chunker = new DiffChunker(patch.length());

    assertThat(chunker.chunk(file(patch))).hasSize(1);
  }

  @Test
  void patchSemCabecalhoDeHunkViraUmChunkUnico() {
    // Não deveria acontecer vindo da API do GitHub, mas se vier, o conteúdo não pode ser perdido.
    DiffChunker chunker = new DiffChunker(1_000);
    String patch = "linha sem cabeçalho\noutra linha";

    List<DiffChunk> chunks = chunker.chunk(file(patch));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).patch()).isEqualTo(patch);
  }

  @Test
  void arquivoSemPatchNaoGeraChunk() {
    DiffChunker chunker = new DiffChunker(1_000);

    assertThat(chunker.chunk(new ChangedFile("a.java", FileStatus.ADDED, null))).isEmpty();
    assertThat(chunker.chunk(new ChangedFile("a.java", FileStatus.ADDED, "   "))).isEmpty();
  }

  @Test
  void limiteNaoPositivoEhRejeitadoNaConstrucao() {
    assertThatThrownBy(() -> new DiffChunker(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DiffChunker(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  private static ChangedFile file(String patch) {
    return new ChangedFile("src/main/java/App.java", FileStatus.MODIFIED, patch);
  }
}
