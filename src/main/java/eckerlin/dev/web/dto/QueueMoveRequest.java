package eckerlin.dev.web.dto;

public record QueueMoveRequest(
        Integer fromIndex,
        Integer toIndex
) {
}
