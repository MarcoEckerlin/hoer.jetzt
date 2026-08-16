import React from "react";
import { Auswahl, Feld, Schalter, Text, Zahl } from "../teile/felder.jsx";
import { Modulseite, useModul } from "./rahmen.jsx";

/**
 * Join-to-Create: wer einen Kanal betritt, bekommt seinen eigenen.
 *
 * <p>Die Platzhalterliste steht hier vollstaendig. Sie stand vorher nur im
 * Quelltext des Listeners - wer nicht hineinsah, kannte {@code {user}} und
 * sonst nichts.</p>
 */
export default function JoinToCreate({ guildId, konfig, neuLaden }) {
    const j = konfig.joinToCreate || {};
    const m = useModul({
        anfang: {
            enabled: !!j.enabled,
            cleanupDelaySeconds: j.cleanupDelaySeconds ?? 10,
            audioIdleTimeoutSeconds: j.audioIdleTimeoutSeconds ?? 300,
            entries: (j.entries || []).map((e) => ({ ...e }))
        },
        pfad: `/api/dashboard/guilds/${guildId}/modules/join-to-create`,
        neuLaden
    });
    const e = m.entwurf;

    function eintrag(i, name, wert) {
        m.setzeFeld("entries", e.entries.map((x, k) => (k === i ? { ...x, [name]: wert } : x)));
    }

    return (
        <Modulseite
            titel="Join to Create"
            hilfe="Ein Sprachkanal als Auslöser: wer ihn betritt, bekommt einen eigenen und wird hineingezogen."
            kopfzusatz={
                j.managedChannelCount > 0 && (
                    <span className="marke">{j.managedChannelCount} Kanäle gerade offen</span>
                )
            }
        >
            <section className="karte-flach">
                <Schalter an={e.enabled} setzen={(x) => m.setzeFeld("enabled", x)} titel="Modul aktiv" />
            </section>

            <section className="karte-flach">
                <h2>Aufräumen</h2>
                <div className="feldgitter">
                    <Feld
                        titel="Leeren Kanal löschen nach"
                        hilfe="Sekunden. Ein kurzer Verzug verhindert, dass der Kanal verschwindet, während jemand nur die Verbindung wechselt."
                        kind={
                            <Zahl
                                wert={e.cleanupDelaySeconds}
                                setzen={(x) => m.setzeFeld("cleanupDelaySeconds", x)}
                                min={0}
                                max={3600}
                            />
                        }
                    />
                    <Feld
                        titel="Musik beenden nach"
                        hilfe="Sekunden ohne Zuhörer, bevor der Bot den Kanal verlässt."
                        kind={
                            <Zahl
                                wert={e.audioIdleTimeoutSeconds}
                                setzen={(x) => m.setzeFeld("audioIdleTimeoutSeconds", x)}
                                min={0}
                                max={7200}
                            />
                        }
                    />
                </div>
            </section>

            <section className="karte-flach">
                <h2>Auslöser</h2>
                {e.entries.length === 0 && (
                    <p className="leise">Noch kein Auslöser. Ohne einen passiert nichts.</p>
                )}

                {e.entries.map((k, i) => (
                    <div className="listenkarte" key={k.id || i}>
                        <div className="feldgitter">
                            <Feld
                                titel="Auslöser-Kanal"
                                hilfe="Diesen Kanal betreten heißt: einen eigenen bekommen."
                                kind={
                                    <Auswahl
                                        wert={k.sourceChannelId}
                                        setzen={(x) => eintrag(i, "sourceChannelId", x)}
                                        liste={konfig.voiceChannels || []}
                                        praefix="🔊 "
                                    />
                                }
                            />
                            <Feld
                                titel="Kategorie"
                                hilfe="Wo die neuen Kanäle entstehen. Leer = dieselbe wie der Auslöser."
                                kind={
                                    <Auswahl
                                        wert={k.categoryId}
                                        setzen={(x) => eintrag(i, "categoryId", x)}
                                        liste={konfig.categories || []}
                                        leerText="— wie der Auslöser —"
                                    />
                                }
                            />
                            <Feld
                                breit
                                titel="Name des neuen Kanals"
                                hilfe={
                                    <>
                                        Platzhalter: <code>{"{user}"}</code> Anzeigename ·{" "}
                                        <code>{"{username}"}</code> Discord-Name · <code>{"{userid}"}</code> ID ·{" "}
                                        <code>{"{server}"}</code> · <code>{"{kanal}"}</code> Auslöser ·{" "}
                                        <code>{"{kategorie}"}</code> · <code>{"{count}"}</code> laufende Nummer (auch{" "}
                                        <code>{"{counter}"}</code>, <code>{"{nummer}"}</code>, <code>{"{n}"}</code>) ·{" "}
                                        <code>{"{mitglieder}"}</code> Mitgliederzahl · <code>{"{datum}"}</code> ·{" "}
                                        <code>{"{zeit}"}</code> · <code>{"{wochentag}"}</code>.
                                        <br />
                                        Die Nummer ist die <em>kleinste freie</em>: schließt Kanal 2, heißt der nächste
                                        wieder 2 und nicht 5.
                                    </>
                                }
                                kind={
                                    <Text
                                        wert={k.nameTemplate}
                                        setzen={(x) => eintrag(i, "nameTemplate", x)}
                                        platzhalter="#{count} — {user}"
                                    />
                                }
                            />
                            <Feld
                                titel="Höchstzahl Nutzer"
                                hilfe="0 = unbegrenzt."
                                kind={<Zahl wert={k.userLimit} setzen={(x) => eintrag(i, "userLimit", x)} min={0} max={99} />}
                            />
                            <Feld
                                titel="Bitrate (kbit/s)"
                                hilfe={`Der Server erlaubt höchstens ${j.maxBitrateKbps || 96}. Mehr wird beim Speichern gekürzt.`}
                                kind={
                                    <Zahl
                                        wert={k.bitrateKbps}
                                        setzen={(x) => eintrag(i, "bitrateKbps", x)}
                                        min={8}
                                        max={j.maxBitrateKbps || 96}
                                    />
                                }
                            />
                        </div>

                        <Schalter
                            an={k.sendConfigPrompt}
                            setzen={(x) => eintrag(i, "sendConfigPrompt", x)}
                            titel="Bedienknöpfe in den Kanal schreiben"
                            hilfe="Umbenennen, sperren, Nutzerzahl ändern — ohne Befehle."
                        />

                        <div className="listenzeile">
                            <button
                                className="knopf leise klein"
                                onClick={() => m.setzeFeld("entries", e.entries.filter((_, k2) => k2 !== i))}
                            >
                                Auslöser entfernen
                            </button>
                        </div>
                    </div>
                ))}

                <button
                    className="knopf leise"
                    onClick={() =>
                        m.setzeFeld("entries", [
                            ...e.entries,
                            {
                                id: null,
                                sourceChannelId: null,
                                categoryId: null,
                                nameTemplate: "#{count} — {user}",
                                userLimit: 0,
                                bitrateKbps: 64,
                                sendConfigPrompt: true
                            }
                        ])
                    }
                >
                    Auslöser hinzufügen
                </button>
            </section>

            {m.leiste}
        </Modulseite>
    );
}
