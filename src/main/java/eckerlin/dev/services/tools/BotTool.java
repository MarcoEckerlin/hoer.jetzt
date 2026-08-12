package eckerlin.dev.services.tools;

import org.json.JSONObject;

/**
 * Beschreibung einer aufrufbaren Bot-Funktion.
 *
 * <p>Dieselbe Definition wird an zwei Stellen verwendet: als Function-Call-
 * Beschreibung fuer das Sprachmodell im Discord-Chat und als Werkzeug im
 * MCP-Endpunkt. Deshalb ist {@code inputSchema} bewusst als JSON-Schema
 * gehalten - beide Protokolle erwarten genau dieses Format.
 *
 * @param name        eindeutiger Bezeichner, wird von beiden Protokollen genutzt
 * @param description was die Funktion tut - das Modell entscheidet allein danach
 * @param inputSchema JSON-Schema der Parameter
 * @param readOnly    reine Abfrage ohne Seiteneffekt
 */
public record BotTool(
        String name,
        String description,
        JSONObject inputSchema,
        boolean readOnly
) {

    /**
     * Erzeugt ein Schema ohne Parameter.
     */
    public static JSONObject noParameters() {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject())
                .put("required", new org.json.JSONArray());
    }
}
