package eckerlin.dev.web.dto;

/**
 * Ein Discord-Server und der Knoten, auf dem seine Wiedergabe laeuft.
 *
 * @param passtZurStufe false, wenn der Server eigentlich auf eine andere Stufe
 *                      gehoert - etwa weil er waehrend eines Ausfalls
 *                      ausgewichen ist und noch nicht zurueckgezogen wurde.
 */
public record AudioNodeGuildView(
        String guildId,
        String name,
        String stufe,
        boolean passtZurStufe,
        boolean spielt,
        String titel
) {
}
