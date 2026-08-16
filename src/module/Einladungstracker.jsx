import React from "react";
import { Schalter } from "../teile/felder.jsx";
import { Modulseite, useModul } from "./rahmen.jsx";

/** Wer wen geworben hat. */
export default function Einladungstracker({ guildId, konfig, neuLaden }) {
    const t = konfig.inviteTracker || {};
    const m = useModul({
        anfang: { enabled: !!t.enabled },
        pfad: `/api/dashboard/guilds/${guildId}/modules/invite-tracker`,
        neuLaden
    });

    return (
        <Modulseite
            titel="Einladungen"
            hilfe="Der Bot merkt sich, über welche Einladung jemand gekommen ist."
            hinweis={t.notice}
        >
            {!t.canReadInvites && (
                <div className="notiz notiz-fehler">
                    Dem Bot fehlt das Recht <em>Server verwalten</em>. Ohne das darf er die
                    Einladungsliste nicht lesen — das Modul bleibt wirkungslos, auch eingeschaltet.
                </div>
            )}

            <section className="karte-flach">
                <Schalter
                    an={m.entwurf.enabled}
                    setzen={(x) => m.setzeFeld("enabled", x)}
                    titel="Modul aktiv"
                    hilfe="Der Bot vergleicht die Zählerstände aller Einladungen, sobald jemand beitritt. Anders lässt sich das bei Discord nicht ermitteln."
                />
            </section>

            <section className="karte-flach">
                <h2>Offene Einladungen</h2>
                {(t.activeInvites || []).length === 0 ? (
                    <p className="leise">Keine.</p>
                ) : (
                    <div className="tabelle">
                        <div className="zeile kopfzeile">
                            <span>Code</span><span>Benutzt</span><span>Von</span><span></span>
                        </div>
                        {t.activeInvites.map((i) => (
                            <div className="zeile" key={i.code}>
                                <span><code>{i.code}</code></span>
                                <span>{i.uses ?? 0}×</span>
                                <span className="leise">{i.inviter || "unbekannt"}</span>
                                <span>{i.temporary && <span className="marke">befristet</span>}</span>
                            </div>
                        ))}
                    </div>
                )}
            </section>

            <section className="karte-flach">
                <h2>Letzte Beitritte</h2>
                {(t.recentJoins || []).length === 0 ? (
                    <p className="leise">Noch nichts aufgezeichnet.</p>
                ) : (
                    <div className="tabelle">
                        <div className="zeile kopfzeile">
                            <span>Mitglied</span><span>Code</span><span>Geworben von</span><span>Wann</span>
                        </div>
                        {t.recentJoins.map((j, k) => (
                            <div className="zeile" key={k}>
                                <span>{j.memberDisplay}</span>
                                <span><code>{j.inviteCode || "—"}</code></span>
                                <span className="leise">{j.inviterDisplay || "unbekannt"}</span>
                                <span className="leise">{j.joinedAt}</span>
                            </div>
                        ))}
                    </div>
                )}
            </section>

            {m.leiste}
        </Modulseite>
    );
}
