import React, { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api.js";

/** Wie eine Aktion in der Liste heisst - der Rohwert steht daneben. */
const NAMEN = {
    ADMIN_SAVE: "Admin eingetragen",
    ADMIN_REMOVE: "Admin entfernt",
    FEATURE_GRANT: "Freigabe erteilt",
    FEATURE_REVOKE: "Freigabe entzogen",
    GUILD_LEAVE: "Server verlassen",
    CONFIG_SAVE: "Konfiguration gespeichert",
    NODE_CREATE: "Knoten angelegt",
    NODE_DELETE: "Knoten abgebaut"
};

/**
 * Das Protokoll der Verwaltungsvorgaenge.
 *
 * <p>Es beantwortet die Frage, die nach jeder unerwarteten Aenderung kommt:
 * wer war das, und wann. Nur lesbar - ein Protokoll, das sich bearbeiten
 * laesst, ist keins.</p>
 */
export default function Protokoll() {
    const [eintraege, setEintraege] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [grenze, setGrenze] = useState(100);

    const laden = useCallback(async () => {
        try {
            setEintraege((await api("GET", `/api/admin/management/audit?limit=${grenze}`)) || []);
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        }
    }, [grenze]);

    useEffect(() => { laden(); }, [laden]);

    if (!eintraege && !fehler) return <div className="ladeschirm"><div className="puls" /></div>;

    return (
        <>
            <header className="modulkopf">
                <div>
                    <h1>Protokoll</h1>
                    <p>Wer hat was verändert — und wann.</p>
                </div>
                <div className="kopf-knoepfe">
                    <button className="knopf leise klein" onClick={laden}>Neu laden</button>
                </div>
            </header>

            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}

            <section className="karte-flach">
                {(eintraege || []).map((eintrag) => (
                    <div className="warteliste-zeile" key={eintrag.id}>
                        <span className="listenzeile-text">
                            <strong>{NAMEN[eintrag.action] || eintrag.action}</strong>
                            <span className="leise">
                                {eintrag.actorName || eintrag.actorUserId}
                                {eintrag.targetId ? ` · ${eintrag.targetType} ${eintrag.targetId}` : ""}
                                {eintrag.details ? ` · ${eintrag.details}` : ""}
                            </span>
                        </span>
                        <span className="leise einfarbig">{zeit(eintrag.createdAt)}</span>
                    </div>
                ))}

                {(eintraege || []).length === 0 && !fehler && (
                    <p className="leise">Noch nichts protokolliert.</p>
                )}

                {(eintraege || []).length >= grenze && (
                    <div className="listenzeile">
                        <button className="knopf leise klein" onClick={() => setGrenze(grenze + 200)}>
                            Weitere laden
                        </button>
                    </div>
                )}
            </section>
        </>
    );
}

function zeit(iso) {
    if (!iso) return "";
    const d = new Date(iso);
    return Number.isNaN(d.getTime())
        ? iso
        : d.toLocaleString("de-DE", { dateStyle: "short", timeStyle: "short" });
}
