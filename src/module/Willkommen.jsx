import React from "react";
import { Auswahl, Feld, Mehrzeilig, Rollenwahl, Schalter, Text } from "../teile/felder.jsx";
import EmbedEditor from "../teile/EmbedEditor.jsx";
import { Modulseite, useModul } from "./rahmen.jsx";

/** Begruessung fuer neue Mitglieder: Nachricht, Kanal, Rollen. */
export default function Willkommen({ guildId, konfig, neuLaden }) {
    const w = konfig.welcome || {};
    const m = useModul({
        anfang: {
            enabled: !!w.enabled,
            channelId: w.channelId || null,
            roleIds: w.roleIds || [],
            welcomeText: w.welcomeText || "",
            sendImage: !!w.sendImage,
            backgroundImageUrl: w.backgroundImageUrl || "",
            accentColor: w.accentColor || "",
            embed: w.embed || null,
            embedVorlageId: w.embedVorlageId || null
        },
        pfad: `/api/dashboard/guilds/${guildId}/modules/welcome`,
        neuLaden
    });
    const e = m.entwurf;

    return (
        <Modulseite
            titel="Willkommen"
            hilfe="Was passiert, wenn jemand den Server betritt."
            hinweis={w.notice}
        >
            <section className="karte-flach">
                <Schalter
                    an={e.enabled}
                    setzen={(v) => m.setzeFeld("enabled", v)}
                    titel="Modul aktiv"
                    hilfe="Aus heißt: keine Nachricht, keine Rolle — der Bot merkt sich nichts nach."
                />
            </section>

            <section className="karte-flach">
                <h2>Wohin</h2>
                <div className="feldgitter">
                    <Feld
                        titel="Kanal"
                        hilfe="Ohne Kanal wird nichts geschrieben; die Rollen werden trotzdem vergeben."
                        kind={
                            <Auswahl
                                wert={e.channelId}
                                setzen={(v) => m.setzeFeld("channelId", v)}
                                liste={konfig.textChannels || []}
                                praefix="#"
                            />
                        }
                    />
                    <Feld
                        breit
                        titel="Rollen beim Beitritt"
                        hilfe="Der Bot braucht dafür eine Rolle, die in der Serverliste über diesen steht — sonst darf er sie nicht vergeben."
                        kind={
                            <Rollenwahl
                                werte={e.roleIds}
                                setzen={(v) => m.setzeFeld("roleIds", v)}
                                liste={konfig.roles || []}
                            />
                        }
                    />
                </div>
            </section>

            <section className="karte-flach">
                <h2>Nachricht</h2>
                <div className="feldgitter">
                    <Feld
                        breit
                        titel="Begrüßungstext"
                        hilfe="Platzhalter: {user} erwähnt das Mitglied, {username} schreibt nur den Namen, {server} den Servernamen, {count} die Mitgliederzahl."
                        kind={<Mehrzeilig wert={e.welcomeText} setzen={(v) => m.setzeFeld("welcomeText", v)} />}
                    />
                </div>

                <EmbedEditor
                    embed={e.embed}
                    setzen={(v) => m.setzeFeld("embed", v)}
                    vorlagen={konfig.embedVorlagen || []}
                    vorlageId={e.embedVorlageId}
                    vorlageSetzen={(v) => m.setzeFeld("embedVorlageId", v)}
                />
            </section>

            <section className="karte-flach">
                <h2>Begrüßungsbild</h2>
                <Schalter
                    an={e.sendImage}
                    setzen={(v) => m.setzeFeld("sendImage", v)}
                    titel="Bild erzeugen"
                    hilfe="Der Bot zeichnet Avatar und Name auf den Hintergrund."
                />
                {e.sendImage && (
                    <div className="feldgitter">
                        <Feld
                            titel="Hintergrundbild"
                            hilfe="Am besten 1024×360. Leer lassen nimmt den Standard."
                            kind={
                                <Text
                                    wert={e.backgroundImageUrl}
                                    setzen={(v) => m.setzeFeld("backgroundImageUrl", v)}
                                    platzhalter="https://…"
                                />
                            }
                        />
                        <Feld
                            titel="Akzentfarbe"
                            kind={<Text wert={e.accentColor} setzen={(v) => m.setzeFeld("accentColor", v)} platzhalter="#5865F2" />}
                        />
                    </div>
                )}
            </section>

            {m.leiste}
        </Modulseite>
    );
}
