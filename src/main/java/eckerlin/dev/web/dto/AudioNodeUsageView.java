package eckerlin.dev.web.dto;

import java.util.List;

/**
 * Ein Audio-Knoten aus Sicht der Bot-Verwaltung.
 *
 * @param erreichbar   ob der Knoten gerade antwortet
 * @param strafpunkte  Bewertung der Bibliothek: je hoeher, desto belasteter.
 *                     Danach entscheidet der Bot, wohin ein neuer Server geht.
 * @param cpuLast      Systemlast des Knotens, 0..1
 * @param server       welche Discord-Server gerade auf diesem Knoten liegen
 */
public record AudioNodeUsageView(
        String name,
        String adresse,
        String stufe,
        boolean erreichbar,
        int obergrenze,
        int spielend,
        int gesamt,
        double cpuLast,
        long laufzeitSekunden,
        int strafpunkte,
        List<AudioNodeGuildView> server
) {
}
