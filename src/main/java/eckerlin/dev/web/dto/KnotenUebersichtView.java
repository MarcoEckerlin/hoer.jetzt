package eckerlin.dev.web.dto;

import java.util.List;

/**
 * Ein Audio-Knoten fuer die Betriebsansicht - Tabelle und Lavalink in einem.
 *
 * <p>Die bisherige {@link AudioNodeUsageView} kommt allein aus der
 * Lavalink-Bibliothek und kennt deshalb nur, was gerade verbunden ist. Fuer
 * die Ansicht fehlt darin genau das, wonach man im Betrieb sucht: woher der
 * Knoten stammt, ob ein Agent auf ihm laeuft (und damit, ob Neustart und
 * Update ueberhaupt angeboten werden koennen) und ob ein gerade erzeugter
 * Server noch im Anmarsch ist.</p>
 *
 * @param zustand    verbunden, anmarsch, still - was die Ampel zeigt
 * @param herkunft   manuell, selbst oder auto
 * @param hatAgent   ob Neustart und Update moeglich sind
 */
public record KnotenUebersichtView(
        String name,
        String adresse,
        String stufe,
        String herkunft,
        String zustand,
        boolean erreichbar,
        boolean hatAgent,
        Long hetznerId,
        String zuletztGesehen,
        int obergrenze,
        int spielend,
        int gesamt,
        double cpuLast,
        long laufzeitSekunden,
        int strafpunkte,
        List<AudioNodeGuildView> server
) {
}
