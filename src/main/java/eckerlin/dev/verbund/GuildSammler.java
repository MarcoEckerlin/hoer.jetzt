package eckerlin.dev.verbund;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eckerlin.dev.utils.Alert;
import eckerlin.dev.web.dto.AdminGuildView;
import eckerlin.dev.web.dto.DashboardGuildView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fuegt die Serverlisten aller Nodes zusammen.
 *
 * <h2>Warum die Liste anders behandelt wird als der Rest</h2>
 *
 * <p>Bei einer Anfrage zu <em>einem</em> Server genuegt Weiterleiten: er liegt
 * auf genau einer Node ({@link GuildWeiterleitung}). Die Liste dagegen ist die
 * Summe aller Nodes - weiterleiten wuerde hier nur die eine Haelfte durch die
 * andere ersetzen. Der Benutzer saehe weiterhin die Haelfte seiner Server,
 * nur eine andere.</p>
 *
 * <p>Faellt eine Node aus, fehlen ihre Server in der Liste - aber die
 * uebrigen sind da. Das ist die richtige Reihenfolge: eine unvollstaendige
 * Liste ist unangenehm, eine Fehlermeldung statt der Liste macht das Panel
 * unbenutzbar.</p>
 */
@Service
public class GuildSammler {

    private static final String MARKE = "X-hoerjetzt-weitergeleitet";
    private static final int VERBINDUNGS_TIMEOUT_MS = 3000;
    private static final int LESE_TIMEOUT_MS = 8000;

    private final KnotenVerzeichnis verzeichnis;
    private final EigeneNode eigene;
    private final ObjectMapper leser = new ObjectMapper();

    public GuildSammler(KnotenVerzeichnis verzeichnis, EigeneNode eigene) {
        this.verzeichnis = verzeichnis;
        this.eigene = eigene;
    }

    /**
     * Die Serverliste des Verwaltungsbereichs.
     *
     * <p>Dieselbe Aufgabe wie bei der Dashboard-Liste, nur mit mehr Feldern je
     * Server. Sie war zunaechst bewusst nicht zusammengefuehrt - mit der
     * Begruendung, eine Freigabe werde dort erteilt, wo der Server laeuft. Das
     * ist als Regel richtig und als Oberflaeche falsch: wer den Verwaltungs-
     * bereich oeffnet, sieht je nach Lastverteiler vier oder drei Server und
     * hat keine Moeglichkeit zu erkennen, dass es sieben sind.</p>
     */
    public List<AdminGuildView> ergaenzenVerwaltung(List<AdminGuildView> eigeneListe, HttpServletRequest anfrage) {
        return zusammenfuehren(
                eigeneListe,
                anfrage,
                "/api/admin/management/guilds",
                new TypeReference<List<AdminGuildView>>() { },
                AdminGuildView::id,
                AdminGuildView::name);
    }

    /**
     * @param eigeneListe was diese Node selbst kennt
     * @param anfrage     die urspruengliche Anfrage - fuer das Sitzungs-Cookie
     */
    public List<DashboardGuildView> ergaenzen(List<DashboardGuildView> eigeneListe, HttpServletRequest anfrage) {
        return zusammenfuehren(
                eigeneListe,
                anfrage,
                "/api/dashboard/guilds",
                new TypeReference<List<DashboardGuildView>>() { },
                DashboardGuildView::id,
                DashboardGuildView::name);
    }

    /**
     * Der gemeinsame Ablauf: eigene Liste, Listen der anderen Nodes, nach
     * Kennung zusammengefuehrt und nach Namen sortiert.
     *
     * @param kennung liest die Server-ID aus einem Eintrag - sie entscheidet
     *                ueber Doppelte
     * @param name    liest den Anzeigenamen fuer die Sortierung
     */
    private <T> List<T> zusammenfuehren(
            List<T> eigeneListe,
            HttpServletRequest anfrage,
            String pfad,
            TypeReference<List<T>> art,
            java.util.function.Function<T, String> kennung,
            java.util.function.Function<T, String> name
    ) {
        // Schon weitergeleitet: dann fragt gerade eine andere Node bei uns an
        // und will genau unseren Teil, nicht wieder die Summe.
        if (anfrage.getHeader(MARKE) != null) {
            return eigeneListe;
        }

        Map<String, KnotenVerzeichnis.Knoten> andere = new LinkedHashMap<>(verzeichnis.alle());
        andere.remove(eigene.name());
        if (andere.isEmpty()) {
            return eigeneListe;
        }

        // Nach Kennung zusammenfuehren. Doppelte kann es geben, solange die
        // Shards noch nicht aufgeteilt sind - dann kennen beide Nodes alles.
        Map<String, T> zusammen = new LinkedHashMap<>();
        eigeneListe.forEach(eintrag -> zusammen.put(kennung.apply(eintrag), eintrag));

        for (KnotenVerzeichnis.Knoten knoten : andere.values()) {
            for (T eintrag : holen(knoten, anfrage, pfad, art)) {
                zusammen.putIfAbsent(kennung.apply(eintrag), eintrag);
            }
        }

        List<T> ergebnis = new ArrayList<>(zusammen.values());
        ergebnis.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                name.apply(a) == null ? "" : name.apply(a),
                name.apply(b) == null ? "" : name.apply(b)));
        return ergebnis;
    }

    private <T> List<T> holen(KnotenVerzeichnis.Knoten knoten, HttpServletRequest anfrage,
                              String pfad, TypeReference<List<T>> art) {
        try {
            URI adresse = URI.create(knoten.basis() + pfad);
            HttpURLConnection verbindung = (HttpURLConnection) adresse.toURL().openConnection();
            verbindung.setRequestMethod("GET");
            verbindung.setConnectTimeout(VERBINDUNGS_TIMEOUT_MS);
            verbindung.setReadTimeout(LESE_TIMEOUT_MS);
            verbindung.setRequestProperty("Accept", "application/json");
            verbindung.setRequestProperty(MARKE, eigene.name());

            // Ohne das Cookie waere die Anfrage dort nicht angemeldet und
            // lieferte 401 - die Sitzung liegt in der gemeinsamen Datenbank.
            String cookie = anfrage.getHeader("Cookie");
            if (cookie != null) {
                verbindung.setRequestProperty("Cookie", cookie);
            }

            try {
                if (verbindung.getResponseCode() != 200) {
                    return List.of();
                }
                try (InputStream quelle = verbindung.getInputStream()) {
                    return leser.readValue(quelle, art);
                }
            } finally {
                verbindung.disconnect();
            }
        } catch (Exception fehler) {
            Alert.send("WARN", "VERBUND",
                    "Serverliste von " + knoten.name() + " nicht erreichbar: " + fehler.getMessage());
            return List.of();
        }
    }
}
