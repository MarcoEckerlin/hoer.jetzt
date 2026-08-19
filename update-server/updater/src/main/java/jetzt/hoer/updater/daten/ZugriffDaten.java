package jetzt.hoer.updater.daten;

import jetzt.hoer.updater.modell.Zugriff;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Das Zugriffsprotokoll. */
@Repository
public class ZugriffDaten {

    private final JdbcClient db;

    public ZugriffDaten(JdbcClient db) {
        this.db = db;
    }

    public void merken(String ip, String pfad, boolean erlaubt, String grund) {
        db.sql("INSERT INTO zugriff (zeit, ip, pfad, erlaubt, grund) VALUES (?, ?, ?, ?, ?)")
                .params(Zeiten.text(Instant.now()), ip, pfad, erlaubt ? 1 : 0, grund)
                .update();
    }

    public List<Zugriff> letzte(int wieviele) {
        return db.sql("""
                SELECT id, zeit, ip, pfad, erlaubt, grund
                  FROM zugriff
                 ORDER BY zeit DESC
                 LIMIT ?
                """)
                .param(wieviele)
                .query((rs, zeile) -> new Zugriff(
                        rs.getLong("id"),
                        Zeiten.zeit(rs.getString("zeit")),
                        rs.getString("ip"),
                        rs.getString("pfad"),
                        rs.getInt("erlaubt") == 1,
                        rs.getString("grund")))
                .list();
    }

    public List<Zugriff> letzteAbgelehnte(int wieviele) {
        return letzte(500).stream().filter(z -> !z.erlaubt()).limit(wieviele).toList();
    }

    /**
     * Ein Docker-Pull erzeugt einen Eintrag je Schicht. Das Protokoll waechst
     * damit schneller, als man denkt - nach zwei Wochen ist es fuer die Frage,
     * die man daran stellt, ohnehin unbrauchbar.
     */
    public int aufraeumen(int tage) {
        return db.sql("DELETE FROM zugriff WHERE zeit < ?")
                .param(Zeiten.text(Instant.now().minusSeconds((long) tage * 86400)))
                .update();
    }
}
