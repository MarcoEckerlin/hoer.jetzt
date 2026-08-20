package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.modell.Ausweis;
import jetzt.hoer.updater.modell.Faehigkeit;

import java.util.Locale;
import java.util.Optional;

/**
 * Welche Faehigkeit ein angefragter Pfad verlangt.
 *
 * <p>Eigene Klasse, weil die Zuordnung nicht so schlicht ist, wie sie aussieht.
 * Die Faehigkeiten sind nach Modulen benannt ({@code CORE_UPDATE}), die Pfade
 * folgen aber drei verschiedenen Bauformen:</p>
 *
 * <pre>
 *   /v2/hoerjetzt/core/blobs/sha256:...   Registry - Modul steht an dritter Stelle
 *   /release/core/...                     Auslieferung - Modul an zweiter Stelle
 *   /tresor/lavalink                      Tresor - Profil an zweiter Stelle
 * </pre>
 *
 * <p>Ein blosser Praefixvergleich griffe bei der Registry ins Leere: dort
 * steht zwischen {@code /v2/} und dem Modulnamen noch der Besitzer. Genau
 * dieser Pfad ist aber der interessante - ueber ihn laufen die Abbilder.</p>
 *
 * <p>Grundsatz durchgehend: <strong>was sich nicht zuordnen laesst, ist
 * gesperrt.</strong> Die umgekehrte Vorgabe waere bequemer und genau der
 * Fehler, bei dem ein spaeter hinzugefuegter Pfad ohne Zutun offensteht.</p>
 */
public final class Pfadrechte {

    private Pfadrechte() {
    }

    /**
     * Zwei Pfade, die jeder angemeldete Knoten braucht, unabhaengig von seinen
     * Modulen.
     *
     * <p>{@code /v2/} ohne alles ist die Versionspruefung der Registry -
     * {@code docker login} und jeder {@code pull} fragen sie zuerst. Wer sie
     * sperrt, sperrt alles, auch fuer Knoten, die durchaus etwas holen
     * duerften. {@code /release/aktuell} ist das Manifest: es sagt einem
     * Knoten, welche Fassung er fahren soll - ohne das weiss er nicht einmal,
     * ob es fuer ihn etwas zu tun gibt.</p>
     */
    private static boolean fuerAlle(String pfad) {
        return pfad.equals("/v2/") || pfad.equals("/v2")
                || pfad.startsWith("/release/aktuell")
                // Seinen eigenen oeffentlichen Schluessel darf jeder Knoten
                // hinterlegen. Es an eine Faehigkeit zu binden waere ein
                // Zirkel: ohne Schluessel bekommt er keinen Tresor, und ohne
                // Tresor-Faehigkeit duerfte er keinen Schluessel abgeben.
                || pfad.equals("/schluessel");
    }

    /**
     * @param pfad wie ihn Caddy in {@code X-Forwarded-Uri} weiterreicht
     * @return true, wenn dieser Ausweis den Pfad oeffnet
     */
    public static boolean darf(Ausweis ausweis, String pfad) {
        if (ausweis == null) {
            return false;
        }
        // Der Uebergangsfall: ein noch nicht umgestellter Knoten mit dem
        // gemeinsamen Passwort. Er bekommt alles - wie bisher auch. Sonst
        // fielen beim Neustart dieses Servers alle bestehenden Knoten aus.
        if (ausweis.gemeinsam()) {
            return true;
        }
        String sauber = saeubern(pfad);
        if (sauber.isEmpty()) {
            return false;
        }
        if (fuerAlle(sauber)) {
            return true;
        }
        return noetig(sauber).map(ausweis::darf).orElse(false);
    }

    /**
     * Welche Faehigkeit dieser Pfad verlangt.
     *
     * @return leer, wenn der Pfad zu keiner gehoert - dann ist er gesperrt
     */
    public static Optional<Faehigkeit> noetig(String pfad) {
        String sauber = saeubern(pfad);

        if (sauber.startsWith("/v2/")) {
            return abbildAus(sauber).flatMap(Pfadrechte::updateFuer);
        }
        if (sauber.startsWith("/release/")) {
            return updateFuer(teil(sauber, 2));
        }
        if (sauber.startsWith("/config/")) {
            return configFuer(teil(sauber, 2));
        }
        if (sauber.startsWith("/tresor/")) {
            return secretFuer(teil(sauber, 2));
        }
        if (sauber.startsWith("/melden")) {
            return Optional.of(Faehigkeit.NODE_HEALTH);
        }
        if (sauber.startsWith("/sicherung/")) {
            return Optional.of(Faehigkeit.SICHERUNG_SCHREIBEN);
        }
        return Optional.empty();
    }

    /**
     * Aus {@code /v2/hoerjetzt/core/blobs/sha256:...} wird {@code core}.
     *
     * <p>Die Registry-Spezifikation erlaubt mehrteilige Namen
     * ({@code /v2/a/b/c/manifests/...}). Ein fester Abschnittszaehler traegt
     * deshalb nicht - gesucht wird nach dem Endpunkt, und der Modulname ist
     * der Abschnitt davor.</p>
     *
     * <p><strong>Von vorn</strong>, nicht von hinten. Der Upload-Pfad lautet
     * {@code /v2/<name>/blobs/uploads/<uuid>} und endet damit auf zwei
     * Schluesselwoerter hintereinander. Von hinten gesucht gewinnt
     * {@code uploads}, und als Modul kaeme {@code blobs} heraus - ein Name,
     * den keine Faehigkeit kennt. Der Knoten waere also ausgesperrt, sobald er
     * ein Abbild hochlaedt, und zwar nur an dieser einen Stelle. Von vorn
     * gesucht steht der Name immer unmittelbar vor dem ersten Schluesselwort.</p>
     */
    private static Optional<String> abbildAus(String pfad) {
        String[] teile = pfad.split("/");
        for (int i = 1; i < teile.length; i++) {
            String t = teile[i];
            if (t.equals("blobs") || t.equals("manifests") || t.equals("tags")
                    || t.equals("uploads") || t.equals("referrers")) {
                return i >= 2 ? Optional.of(teile[i - 1]) : Optional.empty();
            }
        }
        // Kein bekannter Endpunkt - etwa "/v2/_catalog", das die gesamte
        // Registry auflistet. Nicht zuzuordnen heisst gesperrt, und beim
        // Katalog ist das ausdruecklich erwuenscht.
        return Optional.empty();
    }

    private static Optional<Faehigkeit> updateFuer(String modul) {
        return switch (normal(modul)) {
            // web gehoert zur Oberflaeche und faehrt mit dem Core-Stack.
            case "core", "web" -> Optional.of(Faehigkeit.CORE_UPDATE);
            case "ki-chat" -> Optional.of(Faehigkeit.KI_CHAT_UPDATE);
            case "lavalink" -> Optional.of(Faehigkeit.LAVALINK_UPDATE);
            case "ki-radio" -> Optional.of(Faehigkeit.KI_RADIO_UPDATE);
            default -> Optional.empty();
        };
    }

    private static Optional<Faehigkeit> configFuer(String modul) {
        return switch (normal(modul)) {
            case "core", "web" -> Optional.of(Faehigkeit.CORE_CONFIG);
            case "ki-chat" -> Optional.of(Faehigkeit.KI_CHAT_CONFIG);
            case "lavalink" -> Optional.of(Faehigkeit.LAVALINK_CONFIG);
            case "ki-radio" -> Optional.of(Faehigkeit.KI_RADIO_CONFIG);
            case "controller" -> Optional.of(Faehigkeit.CONTROLLER_CONFIG);
            default -> Optional.empty();
        };
    }

    private static Optional<Faehigkeit> secretFuer(String profil) {
        return switch (normal(profil)) {
            case "core", "web", "voll" -> Optional.of(Faehigkeit.CORE_SECRET);
            case "lavalink" -> Optional.of(Faehigkeit.LAVALINK_SECRET);
            case "ki-radio" -> Optional.of(Faehigkeit.KI_RADIO_SECRET);
            case "controller" -> Optional.of(Faehigkeit.CONTROLLER_SECRET);
            default -> Optional.empty();
        };
    }

    /**
     * Vereinheitlicht Schreibweisen und faengt den alten Namen ab.
     *
     * <p>{@code ai-radio} steht noch in bestehenden Compose-Dateien und
     * Abbildnamen. Solange die Umbenennung nicht ueberall durch ist, muss der
     * alte Name hier weiterhin auf dieselbe Faehigkeit zeigen - sonst
     * verlieren die laufenden Knoten mitten in der Umstellung den Zugriff auf
     * ihr eigenes Abbild.</p>
     */
    private static String normal(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        // Abbildnamen tragen oft noch eine Marke: "core:2026.08.19.03".
        int doppel = t.indexOf(':');
        if (doppel > 0) {
            t = t.substring(0, doppel);
        }
        return t.equals("ai-radio") ? "ki-radio" : t;
    }

    /** Der n-te Abschnitt eines Pfads, 1-basiert. Leer, wenn es ihn nicht gibt. */
    private static String teil(String pfad, int n) {
        String[] teile = pfad.split("/");
        return teile.length > n ? teile[n] : "";
    }

    /**
     * Abfrageteil abschneiden und einen fehlenden fuehrenden Schraegstrich
     * ergaenzen. Ohne das entschiede {@code /release/aktuell?knoten=x} anders
     * als {@code /release/aktuell} - ein Unterschied, den niemand erwartet
     * und den ein Angreifer als Erstes ausprobiert.
     */
    private static String saeubern(String pfad) {
        if (pfad == null || pfad.isBlank()) {
            return "";
        }
        String p = pfad.trim();
        int frage = p.indexOf('?');
        if (frage >= 0) {
            p = p.substring(0, frage);
        }
        return p.startsWith("/") ? p : "/" + p;
    }
}
