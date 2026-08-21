package jetzt.hoer.updater.daten;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Die zentral gepflegten Vorgaben. */
@Repository
public class VoreinstellungDaten {

    private final JdbcClient db;

    public VoreinstellungDaten(JdbcClient db) {
        this.db = db;
    }

    /** Alles, was gesetzt ist. Schluessel auf Wert. */
    public Map<String, String> alle() {
        Map<String, String> werte = new LinkedHashMap<>();
        db.sql("SELECT schluessel, wert FROM voreinstellung ORDER BY schluessel")
                .query((rs, zeile) -> Map.entry(rs.getString("schluessel"), rs.getString("wert")))
                .list()
                .forEach(e -> werte.put(e.getKey(), e.getValue()));
        return werte;
    }

    /**
     * Einen Wert setzen - oder loeschen, wenn er leer ist.
     *
     * <p>Leer und "nicht gesetzt" sind dasselbe, und das mit Absicht: eine
     * leere Zeile in der {@code .env} ueberschreibt die Vorgabe der
     * Compose-Datei mit nichts, statt sie stehen zu lassen. Wer ein Feld
     * leert, will die Vorgabe zurueck - nicht einen leeren Wert.</p>
     */
    public void setzen(String schluessel, String wert, String wer) {
        if (wert == null || wert.isBlank()) {
            db.sql("DELETE FROM voreinstellung WHERE schluessel = ?")
                    .params(schluessel)
                    .update();
            return;
        }
        db.sql("""
                INSERT INTO voreinstellung (schluessel, wert, geaendert, wer)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(schluessel) DO UPDATE SET
                    wert      = excluded.wert,
                    geaendert = excluded.geaendert,
                    wer       = excluded.wer
                """)
                .params(schluessel, wert, Zeiten.text(Instant.now()), wer)
                .update();
    }
}
