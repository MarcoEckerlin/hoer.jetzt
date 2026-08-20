package jetzt.hoer.updater.web;

import jetzt.hoer.updater.daten.AnmeldungDaten;
import jetzt.hoer.updater.daten.AusweisDaten;
import jetzt.hoer.updater.daten.FreigabeDaten;
import jetzt.hoer.updater.daten.KnotenDaten;
import jetzt.hoer.updater.daten.VerwaltungDaten;
import jetzt.hoer.updater.daten.ZugriffDaten;
import jetzt.hoer.updater.dienst.Knotenverwaltung;
import jetzt.hoer.updater.dienst.Netzbereich;
import jetzt.hoer.updater.dienst.Torwaechter;
import jetzt.hoer.updater.dienst.Zugang;
import jetzt.hoer.updater.modell.Knoten;
import jetzt.hoer.updater.modell.Knotenvorlage;
import jetzt.hoer.updater.modell.Modul;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Die Oberflaeche. Haengt am Pult-Port, der im privaten Netz liegt.
 *
 * Jede Aenderung an den Freigaben verwirft den Zwischenspeicher des
 * Torwaechters - sonst wirkte eine Sperre erst nach dessen Haltbarkeit, und
 * dreissig Sekunden Verzoegerung sind genau dann laestig, wenn man es eilig
 * hat.
 */
@Controller
public class PultController {

    private final FreigabeDaten freigaben;
    private final KnotenDaten knoten;
    private final ZugriffDaten zugriffe;
    private final Torwaechter torwaechter;
    private final AusweisDaten ausweise;
    private final AnmeldungDaten anmeldungen;
    private final VerwaltungDaten protokoll;
    private final Knotenverwaltung verwaltung;
    private final Zugang zugang;
    /**
     * Die Zone, in der die Datumsfelder dieser Oberflaeche gemeint sind.
     *
     * Wer hier ein Ablaufdatum eintippt, meint seinen Tag - nicht den in
     * Greenwich. Fest verdrahtet und nicht aus der Systemzone gelesen: die
     * haengt davon ab, wie der Container gestartet wurde, und ein Zugang, der
     * je nach TZ-Variable zwei Stunden laenger gilt, ist keine gute Idee.
     */
    private static final java.time.ZoneId HIESIGE_ZONE =
            java.time.ZoneId.of("Europe/Berlin");

    /** Der oeffentliche Name - fuer den Aufsetz-Einzeiler auf der Knotenseite. */
    private final String updateHost;

    public PultController(FreigabeDaten freigaben, KnotenDaten knoten,
                          ZugriffDaten zugriffe, Torwaechter torwaechter,
                          AusweisDaten ausweise, AnmeldungDaten anmeldungen,
                          VerwaltungDaten protokoll, Knotenverwaltung verwaltung,
                          Zugang zugang,
                          @org.springframework.beans.factory.annotation.Value("${hj.update-host:repository.hoer.jetzt}") String updateHost) {
        this.updateHost = updateHost;
        this.freigaben = freigaben;
        this.knoten = knoten;
        this.zugriffe = zugriffe;
        this.torwaechter = torwaechter;
        this.ausweise = ausweise;
        this.anmeldungen = anmeldungen;
        this.protokoll = protokoll;
        this.verwaltung = verwaltung;
        this.zugang = zugang;
    }

    // ------------------------------------------------------------ Uebersicht

    @GetMapping("/")
    public String uebersicht(Model modell) {
        Instant jetzt = Instant.now();
        List<Knoten> liste = knoten.alle();

        modell.addAttribute("knoten", liste);
        modell.addAttribute("jetzt", jetzt);
        modell.addAttribute("stumme", liste.stream().filter(k -> k.stumm(jetzt)).count());
        // Wenn nicht ueberall dasselbe laeuft, ist das die erste Frage, die
        // man an diese Seite hat - deshalb steht sie oben und nicht in einer
        // Spalte, die man vergleichen muss.
        modell.addAttribute("versionen",
                liste.stream().map(Knoten::version).filter(v -> v != null && !v.isBlank())
                        .distinct().sorted().toList());
        modell.addAttribute("abgelehnt", zugriffe.letzteAbgelehnte(5));
        return "uebersicht";
    }

    @PostMapping("/knoten/{kennung}/name")
    public String umbenennen(@PathVariable String kennung, @RequestParam String name) {
        knoten.umbenennen(kennung, name.trim());
        return "redirect:/";
    }

    @PostMapping("/knoten/{kennung}/update")
    public String updateAnfordern(@PathVariable String kennung,
                                  @RequestParam(defaultValue = "true") boolean an,
                                  RedirectAttributes hinweis) {
        knoten.updateAnfordern(kennung, an);
        hinweis.addFlashAttribute("meldung", an
                ? "Vorgemerkt. Der Knoten holt es beim naechsten Herzschlag ab - "
                  + "es geht keine Verbindung von hier zu ihm."
                : "Vormerkung zurueckgenommen.");
        return "redirect:/";
    }

    /**
     * Entfernt einen Knoten - wirklich.
     *
     * <p>Bis dahin loeschte das nur die Zeile aus {@code knoten}. Ausweis,
     * Geheimnis und Module blieben stehen: der Knoten verschwand aus der
     * Liste, konnte sich weiter anmelden und stand beim naechsten Herzschlag
     * wieder da. Der Bestaetigungstext sagte das sogar ausdruecklich - was
     * die Frage aufwirft, wozu der Knopf gut war.</p>
     */
    @PostMapping("/knoten/{kennung}/loeschen")
    public String knotenLoeschen(@PathVariable String kennung,
                                 java.security.Principal wer,
                                 RedirectAttributes hinweis) {
        verwaltung.entfernen(kennung, name(wer));
        hinweis.addFlashAttribute("meldung",
                "Knoten " + kennung + " entfernt - samt Ausweis, Modulen und offenen Token. "
                + "Er kommt nicht von selbst zurueck.");
        return "redirect:/knoten";
    }

    // ------------------------------------------------------- Knotenverwaltung

    @GetMapping("/knoten")
    public String knotenListe(Model modell) {
        List<Knoten> liste = knoten.alle();
        modell.addAttribute("knoten", liste);
        modell.addAttribute("jetzt", Instant.now());
        modell.addAttribute("module", Modul.values());
        modell.addAttribute("vorlagen", Knotenvorlage.values());
        // Je Vorlage der naechste freie Name. Muss vom Server kommen - nur er
        // kennt die vorhandenen Knoten, und im Browser waere die Zaehlung
        // entweder geraten oder eine zweite Abfrage.
        modell.addAttribute("vorschlaege",
                java.util.Arrays.stream(Knotenvorlage.values())
                        .collect(java.util.stream.Collectors.toMap(
                                Enum::name,
                                v -> knoten.naechsteKennung(v.praefix()),
                                (a, b) -> a,
                                java.util.LinkedHashMap::new)));
        // Die Erklaerungen gehen als eigene Karte ins Skript. Sie an das
        // option-Element zu haengen ginge auch - nur muesste der Text dann
        // durch die HTML-Maskierung, und Bindestriche wie Anfuehrungszeichen
        // machen dort mehr Aerger als eine Zeile hier.
        modell.addAttribute("erklaerungen",
                java.util.Arrays.stream(Knotenvorlage.values())
                        .collect(java.util.stream.Collectors.toMap(
                                Enum::name, Knotenvorlage::erklaerung,
                                (a, b) -> a, java.util.LinkedHashMap::new)));
        modell.addAttribute("offene", anmeldungen.offene());
        modell.addAttribute("mitGeheimnis", ausweise.mitEigenemGeheimnis());
        modell.addAttribute("gesamt", liste.size());
        // Je Knoten seine Module - die Vorlage kann nicht selbst abfragen.
        modell.addAttribute("moduleJeKnoten", liste.stream().collect(
                java.util.stream.Collectors.toMap(Knoten::kennung,
                        k -> ausweise.module(k.kennung()))));
        return "knoten";
    }

    /**
     * Legt einen Knoten an und zeigt den Bootstrap-Token.
     *
     * <p>Der Token steht danach nirgends mehr - gespeichert ist nur sein Hash.
     * Deshalb geht er als Flash-Attribut mit und wird auf der Zielseite gross
     * angezeigt: wer ihn hier verpasst, muss einen neuen erzeugen. Das ist
     * unbequem und der Punkt.</p>
     */
    /**
     * Der Aufsetzbefehl, fertig zum Kopieren.
     *
     * <p>Wird hier gebaut und nicht in der Vorlage. Der erste Versuch stand
     * als Ausdruck im {@code th:text} und scheiterte am senkrechten Strich in
     * {@code | bash -s --}: Thymeleaf deutet {@code |} als Beginn einer
     * Literalersetzung, mitten im Ausdruck. Die Seite antwortete daraufhin
     * mit 500 - nicht nur diese Zeile, die ganze Seite.</p>
     *
     * <p>In Java ist es ohnehin der bessere Ort: hier laesst es sich pruefen,
     * und die Vorlage bleibt eine Vorlage.</p>
     */
    // Sichtbar fuer die Probe: der Befehl ist das Ergebnis der ganzen Seite,
    // und er ist schon einmal still zerbrochen.
    String aufsetzBefehl(String kennung, String token,
                                 String rolle, List<Modul> module) {
        StringBuilder b = new StringBuilder()
                .append("curl -fsSL https://").append(updateHost)
                .append("/bootstrap | bash -s -- --rolle ").append(rolle)
                .append(" --kennung ").append(kennung)
                .append(" --token ").append(token);
        if (module != null && !module.isEmpty()) {
            b.append(" --modules ")
             .append(module.stream().map(Enum::name)
                     .collect(java.util.stream.Collectors.joining(",")));
        }
        return b.toString();
    }

    @PostMapping("/knoten")
    public String knotenAnlegen(@RequestParam(defaultValue = "") String kennung,
                                @RequestParam(defaultValue = "") String name,
                                @RequestParam(defaultValue = "") String vorlage,
                                @RequestParam(defaultValue = "") List<String> module,
                                java.security.Principal wer,
                                RedirectAttributes hinweis) {

        // Die Vorlage bestimmt die Module. Einzeln angekreuzte gelten nur,
        // wenn keine gewaehlt wurde - das laesst den Weg fuer eine
        // Sonderkombination offen, ohne dass beide Wege sich widersprechen
        // koennen.
        java.util.Optional<Knotenvorlage> gewaehlteVorlage = Knotenvorlage.aus(vorlage);
        List<Modul> gewaehlt = gewaehlteVorlage
                .map(Knotenvorlage::module)
                .orElseGet(() -> module.stream()
                        .map(Modul::aus)
                        .flatMap(java.util.Optional::stream)
                        .toList());

        if (gewaehlt.isEmpty()) {
            hinweis.addFlashAttribute("fehler",
                    "Ohne Modul bekaeme der Knoten keine Rechte - eine Vorlage waehlen.");
            return "redirect:/knoten";
        }

        // Leer gelassene Kennung: den Vorschlag nehmen.
        //
        // Der wird hier neu gebildet und nicht aus dem Formular uebernommen.
        // Zwischen dem Aufbau der Seite und dem Absenden kann ein zweiter
        // Knoten entstanden sein - dann zeigte das Formular noch lavalink-10,
        // waehrend es den schon gibt, und das Anlegen scheiterte mit einer
        // Meldung, die niemand erwartet.
        if (kennung.isBlank() && gewaehlteVorlage.isPresent()) {
            kennung = knoten.naechsteKennung(gewaehlteVorlage.get().praefix());
        }
        try {
            var hilfe = verwaltung.anlegen(kennung, name, gewaehlt, name(wer));
            hinweis.addFlashAttribute("neuerToken", hilfe.token());
            hinweis.addFlashAttribute("neueKennung", hilfe.kennung());
            hinweis.addFlashAttribute("aufsetzBefehl", aufsetzBefehl(
                    hilfe.kennung(), hilfe.token(),
                    gewaehlteVorlage.map(v -> v == Knotenvorlage.CONTROLLER ? "controller" : "node")
                            .orElse("node"),
                    gewaehlt));
        } catch (IllegalArgumentException falsch) {
            hinweis.addFlashAttribute("fehler", falsch.getMessage());
        }
        return "redirect:/knoten";
    }

    @PostMapping("/knoten/{kennung}/token")
    public String tokenErneuern(@PathVariable String kennung,
                                java.security.Principal wer,
                                RedirectAttributes hinweis) {
        try {
            String frisch = verwaltung.neuerToken(kennung, name(wer));
            hinweis.addFlashAttribute("neuerToken", frisch);
            hinweis.addFlashAttribute("neueKennung", kennung);
            // Beim Erneuern ist die Rolle nicht bekannt - die Module stehen
            // beim Knoten und nicht im Aufruf. "node" passt fuer alles ausser
            // dem Controller, und der wird selten neu getokent.
            hinweis.addFlashAttribute("aufsetzBefehl",
                    aufsetzBefehl(kennung, frisch, "node", ausweise.module(kennung)));
        } catch (IllegalArgumentException falsch) {
            hinweis.addFlashAttribute("fehler", falsch.getMessage());
        }
        return "redirect:/knoten";
    }

    @PostMapping("/knoten/{kennung}/modul")
    public String modulUmschalten(@PathVariable String kennung,
                                  @RequestParam String modul,
                                  @RequestParam(defaultValue = "true") boolean an,
                                  java.security.Principal wer,
                                  RedirectAttributes hinweis) {
        java.util.Optional<Modul> m = Modul.aus(modul);
        if (m.isEmpty()) {
            hinweis.addFlashAttribute("fehler", "Unbekanntes Modul: " + modul);
            return "redirect:/knoten";
        }
        if (an) {
            ausweise.modulSetzen(kennung, m.get());
        } else {
            ausweise.modulEntfernen(kennung, m.get());
        }
        // Ohne das truege der Zwischenspeicher bis zu dreissig Sekunden lang
        // noch die alten Rechte - beim Entzug also genau die, die man gerade
        // wegnehmen wollte.
        zugang.verwerfen();
        protokoll.merken(name(wer), an ? "Modul hinzugefuegt" : "Modul entfernt",
                kennung, m.get().name(), "");
        return "redirect:/knoten";
    }

    @PostMapping("/knoten/{kennung}/sperren")
    public String knotenSperren(@PathVariable String kennung,
                                @RequestParam(defaultValue = "") String grund,
                                @RequestParam(defaultValue = "true") boolean an,
                                java.security.Principal wer,
                                RedirectAttributes hinweis) {
        if (an) {
            verwaltung.sperren(kennung, grund, name(wer));
            hinweis.addFlashAttribute("meldung", "Gesperrt - wirkt sofort.");
        } else {
            verwaltung.entsperren(kennung, name(wer));
            hinweis.addFlashAttribute("meldung", "Sperre aufgehoben.");
        }
        return "redirect:/knoten";
    }

    @PostMapping("/knoten/{kennung}/wartung")
    public String wartung(@PathVariable String kennung,
                          @RequestParam(defaultValue = "") String grund,
                          @RequestParam(defaultValue = "true") boolean an,
                          java.security.Principal wer,
                          RedirectAttributes hinweis) {
        verwaltung.wartung(kennung, an, grund, name(wer));
        hinweis.addFlashAttribute("meldung", an
                ? "In Wartung. Der Knoten nimmt keine neuen Aufgaben mehr an, "
                  + "bezieht aber weiter Updates und Konfiguration."
                : "Wartung beendet.");
        return "redirect:/knoten";
    }

    @PostMapping("/knoten/{kennung}/geheimnis")
    public String geheimnisTauschen(@PathVariable String kennung,
                                    java.security.Principal wer,
                                    RedirectAttributes hinweis) {
        try {
            hinweis.addFlashAttribute("neuesGeheimnis",
                    verwaltung.geheimnisTauschen(kennung, name(wer)));
            hinweis.addFlashAttribute("neueKennung", kennung);
        } catch (IllegalArgumentException falsch) {
            hinweis.addFlashAttribute("fehler", falsch.getMessage());
        }
        return "redirect:/knoten";
    }

    /**
     * Wer die Handlung veranlasst hat. Ohne Anmeldung - etwa beim Zugriff aus
     * dem privaten Netz ohne Sitzung - bleibt es bei einem Platzhalter statt
     * einer leeren Zeile: ein Protokolleintrag ohne Urheber ist eine Frage,
     * die man spaeter nicht mehr beantworten kann.
     */
    private static String name(java.security.Principal wer) {
        return wer == null ? "(unbekannt)" : wer.getName();
    }

    // ------------------------------------------------------------- Freigaben

    @GetMapping("/freigaben")
    public String freigaben(Model modell) {
        modell.addAttribute("freigaben", freigaben.alle());
        modell.addAttribute("jetzt", Instant.now());
        modell.addAttribute("gemerkt", torwaechter.gemerkt());
        // Die haeufigste Freischaltung ist die Adresse, die es gerade
        // vergeblich versucht hat. Sie hier anzubieten spart das Abtippen -
        // und das Abtippen ist die Stelle, an der man sich vertut.
        modell.addAttribute("abgelehnt", zugriffe.letzteAbgelehnte(10));
        return "freigaben";
    }

    @PostMapping("/freigaben")
    public String anlegen(@RequestParam String bereich,
                          @RequestParam(defaultValue = "") String name,
                          @RequestParam(defaultValue = "") String notiz,
                          @RequestParam(defaultValue = "") String laeuftAb,
                          RedirectAttributes hinweis) {
        try {
            // Ueber Netzbereich, nicht roh: das ergaenzt eine einzelne Adresse
            // zu /32 bzw. /128 und nullt die Wirtsbits aus. Sonst stuende
            // "10.1.2.3/8" in der Liste und passte auf sich selbst nicht.
            String normal = Netzbereich.aus(bereich).toString();

            Instant ablauf = null;
            if (!laeuftAb.isBlank()) {
                // Ende des gewaehlten Tages in DEUTSCHER Zeit, nicht in UTC.
                //
                // Hier tippt ein Mensch ein Datum, und er meint seinen Tag.
                // "gilt bis 25.08." heisst: am 26. um 00:00 Uhr ist Schluss,
                // Berliner Zeit.
                //
                // Vorher stand hier atStartOfDay(ZoneOffset.UTC). Im Sommer
                // liegt Deutschland zwei Stunden davor, die Freigabe galt also
                // bis 02:00 Uhr am Folgetag. Zwei Stunden zu lang - und bei
                // einer Zugangsfreigabe ist "zu lang" die falsche Richtung,
                // in die man sich irrt.
                ablauf = LocalDate.parse(laeuftAb.trim())
                        .plusDays(1).atStartOfDay(HIESIGE_ZONE).toInstant();
            }

            freigaben.anlegen(normal, name.trim(), notiz.trim(), ablauf);
            torwaechter.verwerfen();
            hinweis.addFlashAttribute("meldung", normal + " ist freigeschaltet.");
        } catch (IllegalArgumentException e) {
            hinweis.addFlashAttribute("fehler", "Das ist keine gueltige Adresse: " + e.getMessage());
        } catch (java.time.format.DateTimeParseException e) {
            hinweis.addFlashAttribute("fehler", "Das Ablaufdatum ist unlesbar - erwartet wird JJJJ-MM-TT.");
        }
        return "redirect:/freigaben";
    }

    @PostMapping("/freigaben/{id}/sperren")
    public String sperren(@PathVariable long id) {
        freigaben.sperren(id);
        torwaechter.verwerfen();
        return "redirect:/freigaben";
    }

    @PostMapping("/freigaben/{id}/freigeben")
    public String wiederFreigeben(@PathVariable long id) {
        freigaben.freigeben(id);
        torwaechter.verwerfen();
        return "redirect:/freigaben";
    }

    @PostMapping("/freigaben/{id}/loeschen")
    public String freigabeLoeschen(@PathVariable long id) {
        freigaben.loeschen(id);
        torwaechter.verwerfen();
        return "redirect:/freigaben";
    }

    // ------------------------------------------------------------ Protokoll

    @GetMapping("/protokoll")
    public String protokoll(Model modell) {
        modell.addAttribute("zugriffe", zugriffe.letzte(200));
        modell.addAttribute("jetzt", Instant.now());
        return "protokoll";
    }

    // ------------------------------------------------------------- Anmelden

    @GetMapping("/anmelden")
    public String anmelden() {
        return "anmelden";
    }

    /**
     * "vor 3 Minuten" statt eines Zeitstempels. Bei der Frage, ob ein Knoten
     * noch da ist, will man eine Dauer wissen und keine Uhrzeit umrechnen.
     */
    @ModelAttribute("seit")
    public java.util.function.BiFunction<Instant, Instant, String> seit() {
        return (zeit, jetzt) -> {
            if (zeit == null) return "nie";
            long sekunden = Duration.between(zeit, jetzt).getSeconds();
            if (sekunden < 0) return "gerade eben";
            if (sekunden < 90) return "vor " + sekunden + " s";
            long minuten = sekunden / 60;
            if (minuten < 90) return "vor " + minuten + " min";
            long stunden = minuten / 60;
            if (stunden < 48) return "vor " + stunden + " h";
            return "vor " + (stunden / 24) + " Tagen";
        };
    }
}
