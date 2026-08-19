import React from "react";
import { Auswahl, Farbe, Feld, Mehrzeilig, Rollenwahl, Schalter, Text } from "../teile/felder.jsx";
import EmbedEditor from "../teile/EmbedEditor.jsx";
import { Modulseite, useModul } from "./rahmen.jsx";

/** Ticket-System: eine Nachricht, aus der Hilfegespräche entstehen. */
export default function Tickets({ guildId, konfig, neuLaden }) {
    const t = konfig.tickets || {};
    const m = useModul({
        anfang: {
            enabled: !!t.enabled,
            transcriptChannelId: t.transcriptChannelId || null,
            panels: (t.panels || []).map((p) => ({
                ...p,
                options: (p.options || []).map((o) => ({ ...o }))
            }))
        },
        pfad: `/api/dashboard/guilds/${guildId}/modules/tickets`,
        neuLaden
    });
    const e = m.entwurf;

    function tafel(i, name, wert) {
        m.setzeFeld("panels", e.panels.map((p, k) => (k === i ? { ...p, [name]: wert } : p)));
    }

    function option(i, j, name, wert) {
        tafel(i, "options", e.panels[i].options.map((x, k) => (k === j ? { ...x, [name]: wert } : x)));
    }

    return (
        <Modulseite
            titel="Tickets"
            hilfe="Ein Knopf, aus dem ein privater Kanal mit dem Team entsteht."
            hinweis={t.notice}
            kopfzusatz={t.activeTicketCount > 0 && <span className="marke">{t.activeTicketCount} offen</span>}
        >
            <section className="karte-flach">
                <Schalter an={e.enabled} setzen={(x) => m.setzeFeld("enabled", x)} titel="Modul aktiv" />
                <Feld
                    titel="Kanal für Mitschriften"
                    hilfe="Beim Schließen legt der Bot den Gesprächsverlauf dort ab. Leer = keine Mitschrift."
                    kind={
                        <Auswahl
                            wert={e.transcriptChannelId}
                            setzen={(x) => m.setzeFeld("transcriptChannelId", x)}
                            liste={konfig.textChannels || []}
                            praefix="#"
                        />
                    }
                />
            </section>

            {e.panels.map((p, i) => (
                <section className="karte-flach" key={p.id || i}>
                    <div className="karte-kopf">
                        <h2>{p.title || `Tafel ${i + 1}`}</h2>
                        <button
                            className="knopf leise klein"
                            onClick={() => m.setzeFeld("panels", e.panels.filter((_, k) => k !== i))}
                        >
                            Entfernen
                        </button>
                    </div>

                    <div className="feldgitter">
                        <Feld
                            titel="Kanal *"
                            hilfe={p.messageId
                                ? "Steht bereits — wird beim Speichern dort geändert."
                                : "Pflicht. Dorthin stellt der Bot die Nachricht mit dem Knopf."}
                            kind={
                                <Auswahl
                                    wert={p.publishChannelId}
                                    setzen={(x) => tafel(i, "publishChannelId", x)}
                                    liste={konfig.textChannels || []}
                                    praefix="#"
                                />
                            }
                        />
                        <Feld
                            titel="Kategorie für neue Tickets"
                            hilfe="Discord erlaubt 50 Kanäle je Kategorie. Bei viel Andrang lieber eine eigene."
                            kind={
                                <Auswahl
                                    wert={p.categoryId}
                                    setzen={(x) => tafel(i, "categoryId", x)}
                                    liste={konfig.categories || []}
                                />
                            }
                        />
                        <Feld titel="Überschrift" kind={<Text wert={p.title} setzen={(x) => tafel(i, "title", x)} />} />
                        <Feld
                            breit
                            titel="Text"
                            kind={<Mehrzeilig wert={p.description} setzen={(x) => tafel(i, "description", x)} />}
                        />
                        {/*
                          Hier standen die Wahl zwischen Knopf und
                          Auswahlliste und der Platzhaltertext der Liste.
                          Beides ist weg: es gibt nur noch Knöpfe. Eine
                          Einstellung mit genau einer möglichen Antwort ist
                          keine Einstellung, sondern eine Frage, die man
                          jedes Mal neu beantworten muss.
                        */}
                        <Feld titel="Farbe" kind={<Farbe wert={p.accentColor} setzen={(x) => tafel(i, "accentColor", x)} />} />
                        <Feld
                            titel="Benachrichtigen"
                            hilfe="Diese Rolle wird bei jedem neuen Ticket erwähnt."
                            kind={
                                <Auswahl
                                    wert={p.notifyRoleId}
                                    setzen={(x) => tafel(i, "notifyRoleId", x)}
                                    liste={konfig.roles || []}
                                    praefix="@"
                                />
                            }
                        />
                        <Feld
                            breit
                            titel="Erste Nachricht im Ticket"
                            hilfe={
                                <>
                                    Steht im neuen Kanal, bevor jemand vom Team antwortet. Platzhalter:{" "}
                                    <code>{"{user}"}</code> erwähnt die Person, <code>{"{username}"}</code>,{" "}
                                    <code>{"{ticket}"}</code> das Anliegen, <code>{"{count}"}</code> die Nummer.
                                </>
                            }
                            kind={<Mehrzeilig wert={p.welcomeMessage} setzen={(x) => tafel(i, "welcomeMessage", x)} />}
                        />
                        <Feld
                            breit
                            titel="Team"
                            hilfe="Sieht jedes Ticket dieser Tafel. Einzelne Anliegen können unten ein eigenes Team bekommen."
                            kind={
                                <Rollenwahl
                                    werte={p.supportRoleIds}
                                    setzen={(x) => tafel(i, "supportRoleIds", x)}
                                    liste={konfig.roles || []}
                                />
                            }
                        />
                    </div>

                    <Schalter an={p.allowClaim} setzen={(x) => tafel(i, "allowClaim", x)}
                        titel="Übernehmen erlauben" hilfe="Ein Teammitglied macht das Ticket zu seinem." />
                    <Schalter an={p.allowPause} setzen={(x) => tafel(i, "allowPause", x)}
                        titel="Pausieren erlauben" hilfe="Ticket ruht, ohne geschlossen zu werden." />
                    <Schalter an={p.allowCreatorClose} setzen={(x) => tafel(i, "allowCreatorClose", x)}
                        titel="Ersteller darf schließen" />
                    <Schalter an={p.oneTicketPerUser} setzen={(x) => tafel(i, "oneTicketPerUser", x)}
                        titel="Nur ein offenes Ticket je Person" />

                    <details className="ausklapp">
                        <summary>Nachricht frei gestalten</summary>
                        <EmbedEditor
                            embed={p.embed}
                            setzen={(x) => tafel(i, "embed", x)}
                            vorlagen={konfig.embedVorlagen || []}
                            vorlageId={p.embedVorlageId}
                            vorlageSetzen={(x) => tafel(i, "embedVorlageId", x)}
                        />
                    </details>

                    <h3>Anliegen</h3>
                    {(p.options || []).map((o, j) => (
                        <div className="listenkarte" key={o.id || j}>
                            <div className="feldgitter">
                                <Feld titel="Beschriftung" kind={<Text wert={o.label} setzen={(x) => option(i, j, "label", x)} />} />
                                <Feld titel="Emoji" kind={<Text wert={o.emoji} setzen={(x) => option(i, j, "emoji", x)} />} />
                                <Feld
                                    breit
                                    titel="Beschreibung"
                                    hilfe="Steht als Zeile unter dem Text der Tafel — auf dem Knopf ist dafür kein Platz."
                                    kind={<Text wert={o.description} setzen={(x) => option(i, j, "description", x)} />}
                                />
                                <Feld
                                    breit
                                    titel="Name des Ticket-Kanals"
                                    hilfe={
                                        <>
                                            Platzhalter: <code>{"{user}"}</code>, <code>{"{username}"}</code>,{" "}
                                            <code>{"{count}"}</code> laufende Nummer.
                                        </>
                                    }
                                    kind={
                                        <Text
                                            wert={o.channelNameTemplate}
                                            setzen={(x) => option(i, j, "channelNameTemplate", x)}
                                            platzhalter="ticket-{count}"
                                        />
                                    }
                                />
                                <Feld
                                    breit
                                    titel="Eigenes Team"
                                    hilfe="Leer = das Team der Tafel."
                                    kind={
                                        <Rollenwahl
                                            werte={o.supportRoleIds}
                                            setzen={(x) => option(i, j, "supportRoleIds", x)}
                                            liste={konfig.roles || []}
                                            leerText="Das Team der Tafel."
                                        />
                                    }
                                />
                            </div>
                            <button
                                className="knopf leise klein"
                                onClick={() => tafel(i, "options", p.options.filter((_, k) => k !== j))}
                            >
                                Anliegen entfernen
                            </button>
                        </div>
                    ))}

                    <button
                        className="knopf leise klein"
                        onClick={() =>
                            tafel(i, "options", [
                                ...(p.options || []),
                                { id: null, label: "", description: "", emoji: "", channelNameTemplate: "", supportRoleIds: [] }
                            ])
                        }
                    >
                        Anliegen hinzufügen
                    </button>
                </section>
            ))}

            <button
                className="knopf leise"
                onClick={() =>
                    m.setzeFeld("panels", [
                        ...e.panels,
                        {
                            id: null,
                            // Beide ausdruecklich auf null: ohne sie waeren
                            // die Auswahlfelder erst unkontrolliert und dann
                            // kontrolliert - React warnt zu Recht davor.
                            publishChannelId: null,
                            categoryId: null,
                            title: "Support",
                            description: "",
                            accentColor: "#5865F2",
                            allowClaim: true,
                            allowPause: false,
                            allowCreatorClose: true,
                            oneTicketPerUser: true,
                            supportRoleIds: [],
                            options: []
                        }
                    ])
                }
            >
                Tafel hinzufügen
            </button>

            {(t.transcripts || []).length > 0 && (
                <section className="karte-flach">
                    <h2>Letzte Mitschriften</h2>
                    <div className="tabelle">
                        {t.transcripts.map((s) => (
                            <div className="zeile" key={s.id}>
                                <span>{s.ticketSubject || "Ohne Betreff"}</span>
                                <span className="leise">{s.openerDisplay}</span>
                                <span className="leise">{s.createdAt}</span>
                                <a
                                    className="knopf leise klein"
                                    href={`/api/dashboard/guilds/${guildId}/tickets/transcripts/${s.id}`}
                                    target="_blank"
                                    rel="noreferrer"
                                >
                                    Öffnen
                                </a>
                            </div>
                        ))}
                    </div>
                </section>
            )}

            {m.leiste}
        </Modulseite>
    );
}
