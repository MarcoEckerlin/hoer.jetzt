package eckerlin.dev.web.dto;

/**
 * Anlegen oder Aendern eines Webradio-Senders.
 *
 * @param id {@code null} legt einen neuen Sender an. Zu welchem Server er
 *           gehoert, steht im Pfad und nicht hier - so kann kein Aufrufer
 *           einen Sender in einen fremden Server schreiben.
 */
public record RadioSenderRequest(
        Integer id,
        String name,
        String url,
        String logoUrl
) {
}
