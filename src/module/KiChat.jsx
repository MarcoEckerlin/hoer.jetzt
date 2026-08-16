import React from "react";
import { Auswahl, Feld, Schalter } from "../teile/felder.jsx";
import { Modulseite, useModul } from "./rahmen.jsx";

/** KI-Chat: ein Kanal, in dem der Bot antwortet. */
export default function KiChat({ guildId, konfig, neuLaden }) {
    const l = konfig.llm || {};
    const frei = konfig.entitlements?.llmChat;

    const m = useModul({
        anfang: {
            enabled: !!l.enabled,
            textChannelId: l.textChannelId || null,
            model: l.model || ""
        },
        pfad: `/api/dashboard/guilds/${guildId}/modules/llm`,
        neuLaden
    });
    const e = m.entwurf;

    if (!frei) {
        return (
            <Modulseite titel="KI-Chat" hilfe="Der Bot antwortet in einem Kanal wie ein Gesprächspartner.">
                <div className="notiz">
                    Für diesen Server nicht freigeschaltet. Die Freigabe erteilt ein Bot-Administrator —
                    das Rechenwerk dahinter kostet Leistung und wird deshalb einzeln vergeben.
                </div>
            </Modulseite>
        );
    }

    return (
        <Modulseite
            titel="KI-Chat"
            hilfe="Der Bot antwortet in einem Kanal wie ein Gesprächspartner."
            hinweis={l.notice}
        >
            {!l.configured && (
                <div className="notiz notiz-fehler">
                    Auf dieser Instanz ist kein Sprachmodell hinterlegt. Bis dahin bleibt das Modul
                    wirkungslos, auch eingeschaltet.
                </div>
            )}

            <section className="karte-flach">
                <Schalter an={e.enabled} setzen={(x) => m.setzeFeld("enabled", x)} titel="Modul aktiv" />
                <div className="feldgitter">
                    <Feld
                        titel="Kanal"
                        hilfe="Nur hier antwortet der Bot von selbst. Anderswo braucht es eine Erwähnung."
                        kind={
                            <Auswahl
                                wert={e.textChannelId}
                                setzen={(x) => m.setzeFeld("textChannelId", x)}
                                liste={konfig.textChannels || []}
                                praefix="#"
                            />
                        }
                    />
                    <Feld
                        titel="Modell"
                        hilfe={l.provider ? `Läuft über ${l.provider}.` : null}
                        kind={
                            (l.availableModels || []).length > 0 ? (
                                <Auswahl
                                    wert={e.model}
                                    setzen={(x) => m.setzeFeld("model", x)}
                                    liste={(l.availableModels || []).map((x) => ({ id: x, name: x }))}
                                    leerText="— Standard der Instanz —"
                                />
                            ) : (
                                <input
                                    className="eingabe"
                                    value={e.model ?? ""}
                                    placeholder={l.model || "Standard der Instanz"}
                                    onChange={(ev) => m.setzeFeld("model", ev.target.value)}
                                />
                            )
                        }
                    />
                </div>
            </section>

            {m.leiste}
        </Modulseite>
    );
}
