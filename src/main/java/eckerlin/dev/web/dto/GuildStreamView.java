package eckerlin.dev.web.dto;

/**
 * Technische Sicht auf die laufende Wiedergabe eines Servers.
 *
 * <p>Absichtlich hinter einem Recht: welcher Knoten bedient, wie ausgelastet er
 * ist und ob der Server auf der richtigen Stufe liegt, geht die Allgemeinheit
 * nichts an - es beantwortet aber die erste Frage, wenn es hakt.
 *
 * @param passtZurStufe false, wenn der Server auf eine andere Stufe gehoert -
 *                      etwa weil er waehrend eines Ausfalls ausgewichen ist
 */
public record GuildStreamView(
        boolean verbunden,
        String knoten,
        String knotenStufe,
        boolean knotenErreichbar,
        int wiedergabenAufKnoten,
        String serverStufe,
        boolean passtZurStufe
) {
}
