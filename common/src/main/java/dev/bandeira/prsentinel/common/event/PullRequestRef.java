package dev.bandeira.prsentinel.common.event;

/** Identifica o PR em todos os eventos. */
public record PullRequestRef(
        long installationId,
        String owner,
        String repo,
        int number,
        String headSha
) {
    public String fullName() {
        return owner + "/" + repo + "#" + number;
    }
}
