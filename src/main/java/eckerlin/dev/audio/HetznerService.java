package eckerlin.dev.audio;

import eckerlin.dev.utils.Alert;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Server bei Hetzner anlegen und wieder abbauen.
 *
 * <p>Nur das Noetigste der Cloud-API: erzeugen, loeschen, nachsehen. Die
 * eigentliche Installation macht der Server selbst - {@code cloud-init} bekommt
 * ein Startskript mit, das den lavalink-Zweig klont, {@code install.sh}
 * unbeaufsichtigt durchlaeuft und den Agenten einrichtet. Der Knoten meldet
 * sich danach von allein beim Bot an.</p>
 *
 * <h2>Der Token liegt in der Umgebung, nicht in der Datenbank</h2>
 *
 * <p>Ein Hetzner-Token mit Schreibrecht kann Server erzeugen und loeschen - es
 * ist damit naeher an einer Kreditkarte als an einer Einstellung. In der
 * Datenbank waere er ueber jede SQL-Luecke und ueber jede Sicherung
 * abgreifbar, und die Sicherungen liegen anderswo. In der Umgebung ist er
 * genau dort, wo auch das Bot-Token liegt.</p>
 *
 * <h2>Wenn kein Token gesetzt ist, passiert nichts</h2>
 *
 * <p>Kein Fehler, keine Warnung im Minutentakt: das Autoscaling ist dann
 * schlicht aus. Wer es will, setzt den Token - wer nicht, soll nicht
 * gezwungen sein, eine Abschaltvariable zu finden.</p>
 */
@Service
public class HetznerService {

    private static final String BASIS = "https://api.hetzner.cloud/v1";

    private final HttpClient klient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /**
     * Ein Server bei Hetzner.
     *
     * @param privatIp Adresse im privaten Netz, falls eines zugewiesen ist -
     *                 die ist der Adresse aus dem offenen Netz vorzuziehen
     */
    public record Server(long id, String name, String status, String oeffentlichIp, String privatIp) {
        public String besteAdresse() {
            return privatIp != null && !privatIp.isBlank() ? privatIp : oeffentlichIp;
        }
    }

    public boolean eingeschaltet() {
        return !token().isBlank();
    }

    private String token() {
        String wert = System.getenv("HJ_HETZNER_TOKEN");
        return wert == null ? "" : wert.trim();
    }

    // ------------------------------------------------------------------ Lesen

    public List<Server> server() {
        List<Server> liste = new ArrayList<>();
        JSONObject antwort = rufe("GET", "/servers?per_page=50", null);
        if (antwort == null) {
            return liste;
        }
        JSONArray felder = antwort.optJSONArray("servers");
        for (int i = 0; felder != null && i < felder.length(); i++) {
            liste.add(lesen(felder.optJSONObject(i)));
        }
        return liste;
    }

    public Optional<Server> server(long id) {
        JSONObject antwort = rufe("GET", "/servers/" + id, null);
        return antwort == null || antwort.optJSONObject("server") == null
                ? Optional.empty()
                : Optional.of(lesen(antwort.getJSONObject("server")));
    }

    // ------------------------------------------------------------------ Anlegen

    /**
     * Legt einen Server an, der sich selbst zu einem Audio-Knoten macht.
     *
     * @param name   Rechnername - er wird zugleich der Knotenname, denn der
     *               Agent nimmt {@code hostname -s}
     * @param stufe  free oder premium
     * @return der angelegte Server, oder leer bei einem Fehler
     */
    public Optional<Server> anlegen(String name, String stufe) {
        if (!eingeschaltet()) {
            return Optional.empty();
        }

        JSONObject anfrage = new JSONObject()
                .put("name", name)
                .put("server_type", umgebung("HJ_AUTOSCALE_TYPE", "cx33"))
                .put("image", umgebung("HJ_AUTOSCALE_IMAGE", "debian-12"))
                .put("location", umgebung("HJ_AUTOSCALE_LOCATION", "hel1"))
                .put("start_after_create", true)
                .put("user_data", startskript(name, stufe))
                .put("labels", new JSONObject()
                        .put("hoerjetzt", "knoten")
                        .put("stufe", stufe));

        // Ohne SSH-Schluessel schickt Hetzner ein Root-Passwort per Mail. Das
        // funktioniert, ist aber ein Passwort mehr in einem Postfach - und der
        // Knoten braucht ohnehin keinen Menschen, der sich anmeldet.
        JSONArray schluessel = liste(umgebung("HJ_AUTOSCALE_SSH_KEYS", ""));
        if (!schluessel.isEmpty()) {
            anfrage.put("ssh_keys", schluessel);
        }

        // Ein privates Netz ist der Grund, warum der Lavalink-Port nicht im
        // offenen Netz haengen muss. Fehlt es, laeuft alles trotzdem - dann
        // aber mit dem Passwort als einzigem Schutz.
        // Netze und Firewalls nimmt Hetzner nur als Zahl - anders als bei den
        // SSH-Schluesseln, wo auch der Name geht. Wer "intern" eintrug, bekam
        // deshalb bisher einen Server ganz ohne privates Netz, und der Grund
        // stand nur in einer Antwort, die niemand gelesen hat.
        JSONArray netze = kennungen("networks", umgebung("HJ_AUTOSCALE_NETWORK", ""));
        if (!netze.isEmpty()) {
            anfrage.put("networks", netze);
        }
        JSONArray firewalls = kennungen("firewalls", umgebung("HJ_AUTOSCALE_FIREWALL", ""));
        if (!firewalls.isEmpty()) {
            JSONArray gebaut = new JSONArray();
            for (int i = 0; i < firewalls.length(); i++) {
                gebaut.put(new JSONObject().put("firewall", firewalls.get(i)));
            }
            anfrage.put("firewalls", gebaut);
        }

        JSONObject antwort = rufe("POST", "/servers", anfrage);
        if (antwort == null || antwort.optJSONObject("server") == null) {
            return Optional.empty();
        }

        Server erzeugt = lesen(antwort.getJSONObject("server"));
        Alert.send("INFO", "AUDIO", "Hetzner-Server %s (%d) angelegt.".formatted(erzeugt.name(), erzeugt.id()));
        amLastverteiler(erzeugt.id(), true);
        return Optional.of(erzeugt);
    }

    /**
     * Haengt einen Server an den Lastverteiler oder nimmt ihn wieder ab.
     *
     * <h2>Was das bringt - und was nicht</h2>
     *
     * <p>Der Lastverteiler verteilt <b>Web-Verkehr</b> (443 nach 8080). Ein
     * Knoten, der nur Lavalink faehrt, hat auf 8080 nichts. Hetzner prueft das
     * und schickt ihm folgerichtig keinen Verkehr - der Eintrag schadet also
     * nicht, nuetzt aber auch nichts: der Knoten steht dauerhaft als
     * "unhealthy" in der Uebersicht.</p>
     *
     * <p>Sinn ergibt er erst, wenn ein Autoscale-Knoten auch core und web
     * mitbringt. Bis dahin ist das hier Vorarbeit - und der Grund, warum es
     * ueber {@code HJ_AUTOSCALE_LOADBALANCER} abschaltbar bleibt.</p>
     *
     * <p>Ueber die private Adresse: der Verteiler haengt selbst im Netz, und
     * Verkehr, der es nicht verlaesst, kostet nichts und ist nicht mitlesbar.</p>
     */
    private void amLastverteiler(long serverId, boolean anhaengen) {
        String bezeichnung = umgebung("HJ_AUTOSCALE_LOADBALANCER", "");
        if (bezeichnung.isBlank()) {
            return;
        }
        JSONArray verteiler = kennungen("load_balancers", bezeichnung);
        if (verteiler.isEmpty()) {
            Alert.send("WARN", "AUDIO", "Lastverteiler \"" + bezeichnung + "\" nicht gefunden.");
            return;
        }

        long id = verteiler.getLong(0);
        String aktion = anhaengen ? "add_target" : "remove_target";
        JSONObject koerper = new JSONObject()
                .put("type", "server")
                .put("server", new JSONObject().put("id", serverId))
                .put("use_private_ip", true);

        JSONObject antwort = rufe("POST", "/load_balancers/" + id + "/actions/" + aktion, koerper);
        Alert.send(antwort != null ? "INFO" : "WARN", "AUDIO",
                "Server %d %s Lastverteiler %s: %s".formatted(
                        serverId,
                        anhaengen ? "an" : "von",
                        bezeichnung,
                        antwort != null ? "erledigt" : "fehlgeschlagen"));
    }

    /**
     * Loest Namen in Hetzner-Kennungen auf.
     *
     * <p>Zahlen bleiben Zahlen, alles andere wird als Name nachgeschlagen -
     * ohne Zwischenspeicher: das passiert nur beim Anlegen eines Servers, und
     * ein Zwischenspeicher waere genau dann veraltet, wenn jemand im
     * Hetzner-Fenster etwas umbenennt.</p>
     *
     * @param art Ressource in der API, etwa {@code networks} oder {@code firewalls}
     */
    private JSONArray kennungen(String art, String wert) {
        JSONArray ergebnis = new JSONArray();
        if (wert == null || wert.isBlank()) {
            return ergebnis;
        }

        for (String teil : wert.split("[,;\\s]+")) {
            String sauber = teil.trim();
            if (sauber.isEmpty()) {
                continue;
            }
            try {
                ergebnis.put(Long.parseLong(sauber));
                continue;
            } catch (NumberFormatException keineZahl) {
                // Dann eben ueber den Namen.
            }

            JSONObject antwort = rufe("GET", "/" + art + "?name="
                    + URLEncoder.encode(sauber, StandardCharsets.UTF_8), null);
            JSONArray treffer = antwort == null ? null : antwort.optJSONArray(art);
            if (treffer == null || treffer.isEmpty()) {
                Alert.send("WARN", "AUDIO",
                        "In Hetzner gibt es unter " + art + " nichts mit dem Namen \"" + sauber + "\".");
                continue;
            }
            ergebnis.put(treffer.getJSONObject(0).getLong("id"));
        }
        return ergebnis;
    }

    public boolean loeschen(long id) {
        if (!eingeschaltet()) {
            return false;
        }
        // Erst abhaengen, dann loeschen. Hetzner raeumt das Ziel beim Loeschen
        // zwar selbst weg, aber in der Zwischenzeit steht dort ein Server, den
        // es nicht mehr gibt - und schlaegt der DELETE fehl, bleibt sonst ein
        // toter Eintrag stehen, der nur noch Alarme erzeugt.
        amLastverteiler(id, false);
        JSONObject antwort = rufe("DELETE", "/servers/" + id, null);
        boolean geklappt = antwort != null;
        Alert.send(geklappt ? "INFO" : "WARN", "AUDIO",
                "Hetzner-Server %d %s.".formatted(id, geklappt ? "geloescht" : "liess sich nicht loeschen"));
        return geklappt;
    }

    // ------------------------------------------------------------------ intern

    /**
     * Das cloud-init-Skript, mit dem sich der frische Server selbst einrichtet.
     *
     * <p>Bewusst ohne Rueckkanal: der Bot wartet nicht darauf, dass die
     * Installation fertig wird. Sie dauert ein paar Minuten, und der Knoten
     * meldet sich am Ende von selbst an - das ist das Signal. Ein Bot, der
     * waehrenddessen blockiert, waere fuer alle anderen Server stumm.</p>
     */
    private String startskript(String name, String stufe) {
        String repo = umgebung("HJ_AUTOSCALE_REPO", "https://github.com/MarcoEckerlin/hoer.jetzt.git");
        String coreUrl = umgebung("HJ_WEB_BASE_URL", "");
        String nodeToken = System.getenv("HJ_NODE_TOKEN");
        String agentToken = System.getenv("HJ_AGENT_TOKEN");
        String lavalinkPasswort = umgebung("HJ_LAVALINK_PASSWORD", "");

        String skript = """
                #!/bin/bash
                set -euxo pipefail
                exec > >(tee /var/log/hoerjetzt-erstinstallation.log) 2>&1

                hostnamectl set-hostname '%s'

                export DEBIAN_FRONTEND=noninteractive
                apt-get update
                apt-get install -y git curl ca-certificates python3

                git clone -b lavalink %s /opt/hoerjetzt-node
                cd /opt/hoerjetzt-node

                # install.sh fragt nach - hier fragt niemand. Deshalb wird der
                # Container direkt gestartet und danach nur noch der Agent
                # eingerichtet; der uebernimmt die Anmeldung beim Bot.
                curl -fsSL https://get.docker.com | sh
                systemctl enable --now docker

                docker build -t hoerjetzt-lavalink:latest /opt/hoerjetzt-node

                PRIVAT="$(ip -4 -o addr show 2>/dev/null | awk '{print $4}' | cut -d/ -f1 \\
                    | grep -E '^(10\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.|192\\.168\\.)' | head -n1 || true)"
                BIND="${PRIVAT:-0.0.0.0}"

                docker run -d --name hoerjetzt-lavalink-1 --restart unless-stopped \\
                    -p "${BIND}:2333:2333" \\
                    -e LAVALINK_SERVER_PASSWORD='%s' \\
                    -e LAVALINK_TIER='%s' \\
                    -e LAVALINK_QUALITAET=hoch \\
                    -e LAVALINK_PORT=2333 \\
                    hoerjetzt-lavalink:latest

                HJ_NODE_CONTAINER=hoerjetzt-lavalink-1 \\
                HJ_NODE_ADDRESS="http://${BIND}:2333" \\
                HJ_LAVALINK_PASSWORD='%s' \\
                HJ_NODE_TIER='%s' \\
                HJ_AGENT_TOKEN='%s' \\
                HJ_CORE_URL='%s' \\
                HJ_NODE_TOKEN='%s' \\
                HJ_AUTOSCALED=true \\
                    bash /opt/hoerjetzt-node/agent/einrichten.sh
                """.formatted(
                name, repo,
                lavalinkPasswort, stufe,
                lavalinkPasswort, stufe,
                agentToken == null ? "" : agentToken.trim(),
                coreUrl,
                nodeToken == null ? "" : nodeToken.trim()
        );

        // Hetzner nimmt user_data als Klartext an; Base64 waere hier falsch.
        // Der Wert wird aber protokolliert, deshalb steht er nirgends im Log
        // dieses Dienstes.
        return skript;
    }

    private Server lesen(JSONObject feld) {
        if (feld == null) {
            return new Server(0, "", "unbekannt", "", "");
        }
        String oeffentlich = feld.optJSONObject("public_net") == null ? ""
                : feld.getJSONObject("public_net").optJSONObject("ipv4") == null ? ""
                : feld.getJSONObject("public_net").getJSONObject("ipv4").optString("ip", "");

        String privat = "";
        JSONArray privatnetze = feld.optJSONArray("private_net");
        if (privatnetze != null && !privatnetze.isEmpty()) {
            privat = privatnetze.getJSONObject(0).optString("ip", "");
        }

        return new Server(
                feld.optLong("id"),
                feld.optString("name", ""),
                feld.optString("status", "unbekannt"),
                oeffentlich,
                privat
        );
    }

    private JSONObject rufe(String verfahren, String pfad, JSONObject koerper) {
        String token = token();
        if (token.isBlank()) {
            return null;
        }

        HttpRequest.Builder bau = HttpRequest.newBuilder(URI.create(BASIS + pfad))
                .timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");

        switch (verfahren) {
            case "POST" -> bau.POST(HttpRequest.BodyPublishers.ofString(koerper.toString()));
            case "DELETE" -> bau.DELETE();
            default -> bau.GET();
        }

        try {
            HttpResponse<String> antwort = klient.send(bau.build(), HttpResponse.BodyHandlers.ofString());
            String text = antwort.body() == null ? "" : antwort.body();

            if (antwort.statusCode() >= 400) {
                // Die Fehlermeldung von Hetzner ist brauchbar ("invalid input
                // in field 'server_type'") - sie gehoert ins Log, sonst sucht
                // man an der falschen Stelle.
                Alert.send("WARN", "AUDIO", "Hetzner %s %s: HTTP %d %s".formatted(
                        verfahren, pfad, antwort.statusCode(), kurz(text)));
                return null;
            }
            return text.startsWith("{") ? new JSONObject(text) : new JSONObject();
        } catch (java.io.IOException fehler) {
            Alert.send("WARN", "AUDIO", "Hetzner nicht erreichbar: " + fehler.getMessage());
            return null;
        } catch (InterruptedException fehler) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException fehler) {
            Alert.send("WARN", "AUDIO", "Hetzner antwortete unerwartet: " + fehler.getMessage());
            return null;
        }
    }

    private JSONArray liste(String wert) {
        JSONArray felder = new JSONArray();
        if (wert == null || wert.isBlank()) {
            return felder;
        }
        for (String teil : wert.split("[,;\\s]+")) {
            String sauber = teil.trim();
            if (sauber.isEmpty()) {
                continue;
            }
            // Hetzner nimmt sowohl IDs als auch Namen. Zahlen als Zahl zu
            // schicken ist die eindeutigere Variante.
            try {
                felder.put(Long.parseLong(sauber));
            } catch (NumberFormatException fehler) {
                felder.put(sauber);
            }
        }
        return felder;
    }

    private String umgebung(String name, String vorgabe) {
        String wert = System.getenv(name);
        return wert == null || wert.isBlank() ? vorgabe : wert.trim();
    }

    private String kurz(String text) {
        String sauber = text == null ? "" : text.strip();
        return sauber.length() > 300 ? sauber.substring(0, 300) + "…" : sauber;
    }
}
