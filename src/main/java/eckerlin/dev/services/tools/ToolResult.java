package eckerlin.dev.services.tools;

/**
 * Ergebnis eines Werkzeugaufrufs.
 *
 * <p>{@code failed} unterscheidet einen fachlichen Fehlschlag (Titel nicht
 * gefunden, keine Berechtigung) von einem Protokollfehler. MCP bildet das auf
 * {@code isError} im Ergebnis ab; im Chat wird der Text einfach ausgegeben.
 */
public record ToolResult(String message, boolean failed) {

    public static ToolResult ok(String message) {
        return new ToolResult(message, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }
}
