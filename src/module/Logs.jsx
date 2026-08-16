import React from "react";
import { Auswahl, Feld, Schalter } from "../teile/felder.jsx";
import { Modulseite, useModul } from "./rahmen.jsx";

/**
 * Server-Protokoll: was der Bot in einen Kanal mitschreibt.
 *
 * <p>Gruppiert statt als eine Liste von fuenfzehn Haken. Wer „alles ueber
 * Moderation" will, soll drei Haken an einer Stelle finden und nicht drei
 * ueber die ganze Seite verteilt.</p>
 */
const GRUPPEN = [
    {
        titel: "Mitglieder",
        punkte: [
            ["memberJoin", "Beitritte", "Wer den Server betritt."],
            ["memberLeave", "Austritte", "Wer geht — auch nach Kick oder Bann."],
            ["roleUpdates", "Rollenänderungen", "Wer welche Rolle bekommt oder verliert."],
            ["nicknameUpdates", "Namensänderungen", null]
        ]
    },
    {
        titel: "Moderation",
        punkte: [
            ["moderation", "Moderationsbefehle", "Was über den Bot moderiert wurde."],
            ["timeouts", "Auszeiten", null],
            ["kicks", "Kicks", null],
            ["bans", "Bänne", null],
            ["messageDeletes", "Gelöschte Nachrichten", "Inhalt wird mitgeschrieben — das ist der Sinn, aber auch der Grund, den Kanal nicht öffentlich zu machen."],
            ["voiceModeration", "Sprach-Moderation", "Stumm, taub, verschieben, trennen."]
        ]
    },
    {
        titel: "Sprache und Musik",
        punkte: [
            ["voiceJoin", "Sprachkanal betreten", null],
            ["voiceLeave", "Sprachkanal verlassen", null],
            ["music", "Wiedergabe", "Was gespielt, übersprungen und gestoppt wurde."]
        ]
    },
    {
        titel: "Sonstiges",
        punkte: [["commands", "Befehle", "Wer welchen Slash-Befehl benutzt hat."]]
    }
];

export default function Logs({ guildId, konfig, neuLaden }) {
    const l = konfig.discordLogs || {};
    const anfang = { enabled: !!l.enabled, textChannelId: l.textChannelId || null };
    GRUPPEN.forEach((g) => g.punkte.forEach(([name]) => { anfang[name] = !!l[name]; }));

    const m = useModul({
        anfang,
        pfad: `/api/dashboard/guilds/${guildId}/modules/discord-logs`,
        neuLaden
    });
    const e = m.entwurf;

    function alle(gruppe, an) {
        const neu = { ...e };
        gruppe.punkte.forEach(([name]) => { neu[name] = an; });
        m.setEntwurf(neu);
    }

    return (
        <Modulseite
            titel="Server-Protokoll"
            hilfe="Der Bot schreibt mit, was auf dem Server passiert."
            hinweis={l.notice}
        >
            <section className="karte-flach">
                <Schalter an={e.enabled} setzen={(x) => m.setzeFeld("enabled", x)} titel="Modul aktiv" />
                <Feld
                    titel="Kanal"
                    hilfe="Ohne Kanal wird nichts geschrieben. Ein Kanal, den nur das Team sieht — hier stehen gelöschte Nachrichten im Klartext."
                    kind={
                        <Auswahl
                            wert={e.textChannelId}
                            setzen={(x) => m.setzeFeld("textChannelId", x)}
                            liste={konfig.textChannels || []}
                            praefix="#"
                        />
                    }
                />
            </section>

            {GRUPPEN.map((g) => (
                <section className="karte-flach" key={g.titel}>
                    <div className="karte-kopf">
                        <h2>{g.titel}</h2>
                        <div className="listenzeile">
                            <button className="knopf leise klein" onClick={() => alle(g, true)}>Alle</button>
                            <button className="knopf leise klein" onClick={() => alle(g, false)}>Keine</button>
                        </div>
                    </div>
                    {g.punkte.map(([name, titel, hilfe]) => (
                        <Schalter
                            key={name}
                            an={e[name]}
                            setzen={(x) => m.setzeFeld(name, x)}
                            titel={titel}
                            hilfe={hilfe}
                        />
                    ))}
                </section>
            ))}

            {m.leiste}
        </Modulseite>
    );
}
