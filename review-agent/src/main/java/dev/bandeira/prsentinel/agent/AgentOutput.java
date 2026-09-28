package dev.bandeira.prsentinel.agent;

import dev.bandeira.prsentinel.common.event.Finding;
import java.util.List;

/** Formato que o LLM deve devolver (structured output). */
public record AgentOutput(List<Finding> findings) {
}
