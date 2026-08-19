import React from "react";
import { Auswahl, Farbe, Feld, Mehrzeilig, Rollenwahl, Schalter, Text } from "../teile/felder.jsx";
import EmbedEditor from "../teile/EmbedEditor.jsx";
import Bildfeld from "../teile/Bildfeld.jsx";
import { Modulseite, useModul } from "./rahmen.jsx";

/**
 * Verifizierung: eine Nachricht mit Knopf, dahinter Rollen.
 *
 * <p>Neu gegenueber der alten Oberflaeche sind die Rollen, die beim
 * Verifizieren <em>entfernt</em> werden. Der uebliche Aufbau ist eine
 * Besucherrolle, die nach dem Klick verschwindet - vorher liess sich das nur
 * ueber Umwege bauen.</p>
 */
export default function Verify({ guildId, konfig, neuLaden }) {
    const v = konfig.verify || {};
    const m = useModul({
        anfang: {
            enabled: !!v.enabled,
            publishChannelId: v.publishChannelId || null,
            verifiedRoleIds: v.verifiedRoleIds || [],
            removedRoleIds: v.removedRoleIds || [],
            title: v.title || "",
            description: v.description || "",
            imageUrl: v.imageUrl || "",
            thumbnailUrl: v.thumbnailUrl || "",
            accentColor: v.accentColor || "",
            embed: v.embed || null,
            embedVorlageId: v.embedVorlageId || null
        },
        pfad: `/api/dashboard/guilds/${guildId}/modules/verify`,
        neuLaden
    });
    const e = m.entwurf;

    const ueberschneidung = (e.verifiedRoleIds || []).filter((r) => (e.removedRoleIds || []).includes(r));

    return (
        <Modulseite
            titel="Verifizierung"
            hilfe="Ein Knopf, den neue Mitglieder drücken, um Zugang zu bekommen."
            hinweis={v.notice}
        >
            <section className="karte-flach">
                <Schalter an={e.enabled} setzen={(x) => m.setzeFeld("enabled", x)} titel="Modul aktiv" />
            </section>

            <section className="karte-flach">
                <h2>Rollen</h2>
                <div className="feldgitter">
                    <Feld
                        breit
                        titel="Rollen vergeben"
                        hilfe="Bekommt, wer den Knopf drückt."
                        kind={
                            <Rollenwahl
                                werte={e.verifiedRoleIds}
                                setzen={(x) => m.setzeFeld("verifiedRoleIds", x)}
                                liste={konfig.roles || []}
                            />
                        }
                    />
                    <Feld
                        breit
                        titel="Rollen entfernen"
                        hilfe="Wird beim Verifizieren abgenommen — typischerweise eine Besucher- oder Wartezimmer-Rolle."
                        kind={
                            <Rollenwahl
                                werte={e.removedRoleIds}
                                setzen={(x) => m.setzeFeld("removedRoleIds", x)}
                                liste={konfig.roles || []}
                                leerText="Keine Rolle wird entfernt."
                            />
                        }
                    />
                </div>
                {ueberschneidung.length > 0 && (
                    <div className="notiz notiz-fehler">
                        {ueberschneidung.length === 1 ? "Eine Rolle steht" : `${ueberschneidung.length} Rollen stehen`} in
                        beiden Listen. Sie würde erst vergeben und dann wieder entfernt — das Ergebnis hängt an der
                        Reihenfolge und ist nicht das, was gemeint ist.
                    </div>
                )}
            </section>

            <section className="karte-flach">
                <h2>Nachricht</h2>
                <div className="feldgitter">
                    <Feld
                        titel="Kanal"
                        hilfe={
                            v.messageId
                                ? "Die Nachricht steht bereits. Beim Speichern wird sie an Ort und Stelle geändert."
                                : "Beim Speichern schreibt der Bot die Nachricht dorthin."
                        }
                        kind={
                            <Auswahl
                                wert={e.publishChannelId}
                                setzen={(x) => m.setzeFeld("publishChannelId", x)}
                                liste={konfig.textChannels || []}
                                praefix="#"
                            />
                        }
                    />
                    <Feld titel="Überschrift" kind={<Text wert={e.title} setzen={(x) => m.setzeFeld("title", x)} />} />
                    <Feld
                        breit
                        titel="Text"
                        kind={<Mehrzeilig wert={e.description} setzen={(x) => m.setzeFeld("description", x)} />}
                    />
                    <Feld titel="Farbe" kind={<Farbe wert={e.accentColor} setzen={(x) => m.setzeFeld("accentColor", x)} />} />
                    <Feld
                        titel="Bild"
                        kind={<Bildfeld wert={e.imageUrl} setzen={(x) => m.setzeFeld("imageUrl", x)}
                                            seitenverhaeltnis={16 / 9} zielbreite={1024} />}
                    />
                    <Feld
                        titel="Kleines Bild"
                        kind={<Bildfeld wert={e.thumbnailUrl} setzen={(x) => m.setzeFeld("thumbnailUrl", x)}
                                            seitenverhaeltnis={1} zielbreite={512} />}
                    />
                </div>

                <details className="ausklapp">
                    <summary>Nachricht frei gestalten</summary>
                    <p className="feld-hilfe">
                        Ersetzt die Felder oben vollständig — Überschrift, Text, Farbe und Bilder kommen dann von hier.
                    </p>
                    <EmbedEditor
                        embed={e.embed}
                        setzen={(x) => m.setzeFeld("embed", x)}
                        vorlagen={konfig.embedVorlagen || []}
                        vorlageId={e.embedVorlageId}
                        vorlageSetzen={(x) => m.setzeFeld("embedVorlageId", x)}
                    />
                </details>
            </section>

            {m.leiste}
        </Modulseite>
    );
}
