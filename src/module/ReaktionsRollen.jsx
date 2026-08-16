import React from "react";
import { Auswahl, Farbe, Feld, Mehrzeilig, Rollenwahl, Schalter, Text } from "../teile/felder.jsx";
import EmbedEditor from "../teile/EmbedEditor.jsx";
import { Modulseite, useModul } from "./rahmen.jsx";

/**
 * Rollen per Knopfdruck.
 *
 * <p>Jeder Eintrag kann Rollen vergeben <em>und</em> Rollen entfernen. Damit
 * lassen sich Auswahlen bauen, die sich gegenseitig ausschliessen, ohne dafuer
 * eine zweite Nachricht anzulegen.</p>
 */
export default function ReaktionsRollen({ guildId, konfig, neuLaden }) {
    const r = konfig.reactionRoles || {};
    const m = useModul({
        anfang: {
            enabled: !!r.enabled,
            panels: (r.panels || []).map((p) => ({
                ...p,
                entries: (p.entries || []).map((e) => ({ ...e }))
            }))
        },
        pfad: `/api/dashboard/guilds/${guildId}/modules/reaction-roles`,
        neuLaden
    });
    const e = m.entwurf;

    function tafel(i, name, wert) {
        m.setzeFeld("panels", e.panels.map((p, k) => (k === i ? { ...p, [name]: wert } : p)));
    }

    function eintrag(i, j, name, wert) {
        tafel(i, "entries", e.panels[i].entries.map((x, k) => (k === j ? { ...x, [name]: wert } : x)));
    }

    return (
        <Modulseite
            titel="Reaktionsrollen"
            hilfe="Eine Nachricht mit Knöpfen: wer drückt, bekommt eine Rolle."
            hinweis={r.notice}
        >
            <section className="karte-flach">
                <Schalter an={e.enabled} setzen={(x) => m.setzeFeld("enabled", x)} titel="Modul aktiv" />
            </section>

            {e.panels.map((p, i) => (
                <section className="karte-flach" key={p.id || i}>
                    <div className="karte-kopf">
                        <h2>{p.title || `Nachricht ${i + 1}`}</h2>
                        <button
                            className="knopf leise klein"
                            onClick={() => m.setzeFeld("panels", e.panels.filter((_, k) => k !== i))}
                        >
                            Entfernen
                        </button>
                    </div>

                    <div className="feldgitter">
                        <Feld
                            titel="Kanal"
                            hilfe={p.messageId ? "Steht bereits — wird beim Speichern dort geändert." : null}
                            kind={
                                <Auswahl
                                    wert={p.publishChannelId}
                                    setzen={(x) => tafel(i, "publishChannelId", x)}
                                    liste={konfig.textChannels || []}
                                    praefix="#"
                                />
                            }
                        />
                        <Feld titel="Überschrift" kind={<Text wert={p.title} setzen={(x) => tafel(i, "title", x)} />} />
                        <Feld
                            breit
                            titel="Text"
                            kind={<Mehrzeilig wert={p.description} setzen={(x) => tafel(i, "description", x)} />}
                        />
                        <Feld titel="Farbe" kind={<Farbe wert={p.accentColor} setzen={(x) => tafel(i, "accentColor", x)} />} />
                        <Feld
                            titel="Bild"
                            kind={<Text wert={p.imageUrl} setzen={(x) => tafel(i, "imageUrl", x)} platzhalter="https://…" />}
                        />
                    </div>

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

                    <h3>Knöpfe</h3>
                    {(p.entries || []).map((k, j) => (
                        <div className="listenkarte" key={k.id || j}>
                            <div className="feldgitter">
                                <Feld
                                    titel="Beschriftung"
                                    kind={<Text wert={k.label} setzen={(x) => eintrag(i, j, "label", x)} />}
                                />
                                <Feld
                                    titel="Emoji"
                                    hilfe="Ein Unicode-Emoji oder ein Server-Emoji in der Form :name:."
                                    kind={<Text wert={k.emoji} setzen={(x) => eintrag(i, j, "emoji", x)} />}
                                />
                                <Feld
                                    breit
                                    titel="Beschreibung"
                                    kind={<Text wert={k.description} setzen={(x) => eintrag(i, j, "description", x)} />}
                                />
                                <Feld
                                    breit
                                    titel="Rollen vergeben"
                                    kind={
                                        <Rollenwahl
                                            werte={k.roleIds}
                                            setzen={(x) => eintrag(i, j, "roleIds", x)}
                                            liste={konfig.roles || []}
                                        />
                                    }
                                />
                                <Feld
                                    breit
                                    titel="Rollen entfernen"
                                    hilfe="Beim Drücken abgenommen. So bauen sich Auswahlen, die sich gegenseitig ausschließen."
                                    kind={
                                        <Rollenwahl
                                            werte={k.removedRoleIds}
                                            setzen={(x) => eintrag(i, j, "removedRoleIds", x)}
                                            liste={konfig.roles || []}
                                            leerText="Keine Rolle wird entfernt."
                                        />
                                    }
                                />
                            </div>
                            <button
                                className="knopf leise klein"
                                onClick={() => tafel(i, "entries", p.entries.filter((_, k2) => k2 !== j))}
                            >
                                Knopf entfernen
                            </button>
                        </div>
                    ))}

                    {(p.entries || []).length < 25 && (
                        <button
                            className="knopf leise klein"
                            onClick={() =>
                                tafel(i, "entries", [
                                    ...(p.entries || []),
                                    { id: null, emoji: "", label: "", description: "", roleIds: [], removedRoleIds: [] }
                                ])
                            }
                        >
                            Knopf hinzufügen
                        </button>
                    )}
                </section>
            ))}

            <button
                className="knopf leise"
                onClick={() =>
                    m.setzeFeld("panels", [
                        ...e.panels,
                        {
                            id: null,
                            publishChannelId: null,
                            title: "Rollen",
                            description: "",
                            accentColor: "#5865F2",
                            entries: []
                        }
                    ])
                }
            >
                Nachricht hinzufügen
            </button>

            {m.leiste}
        </Modulseite>
    );
}
