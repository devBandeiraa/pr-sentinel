package dev.bandeira.prsentinel.common.event;

/** Um problema encontrado por um agente. Formato validado na saída do LLM. */
public record Finding(
        String file,
        int line,
        Severity severity,
        String title,
        String explanation,
        String suggestion,
        double confidence
) {
}
