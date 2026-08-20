import React from "react";
import { Feld, Text, Auswahl, Zahl, Mehrzeilig } from "../teile/felder.jsx";
import { useInstanzKonfig, Betriebsseite } from "./instanz-felder.jsx";

const STATUS = [
    { id: "online", name: "Online" },
    { id: "idle", name: "Abwesend" },
    { id: "dnd", name: "Bitte nicht stören" },
    { id: "invisible", name: "Unsichtbar" }
];

const ANBIETER = [
    { id: "ollama", name: "Ollama (selbst gehostet)" },
    { id: "openai", name: "OpenAI-kompatibel" }
];

/**
 * Zugangsdaten, Auftritt und KI-Anbieter.
 *
 * <h2>Zu den Geheimnissen</h2>
 *
 * <p>Bot-Token, Client-Secret und der KI-Schluessel kommen von der API nur
 * maskiert zurueck. Ein leeres Feld heisst deshalb "unveraendert lassen" und
 * nicht "loeschen" - sonst haette jeder Besuch dieser Seite, gefolgt von
 * einem Klick auf Speichern, den Bot abgemeldet.</p>
 */
export default function BotZugang() {
    const { konfig, fehler, wert, setzen, leiste } = useInstanzKonfig();

    if (!konfig && !fehler) return <div className="ladeschirm"><div className="puls" /></div>;

    return (
        <Betriebsseite
            titel="Bot & Zugang"
            hilfe="Token, Auftritt in Discord, Anmeldung und KI-Anbieter."
            fehler={fehler}
        >
            <section className="karte-flach">
                <h2>Discord-Bot</h2>
                <p className="leise">
                    Leer lassen heißt <strong>unverändert</strong>. Die Geheimnisse werden nie
                    im Klartext zurückgeliefert.
                </p>
                <Feld
                    titel="Bot-Token"
                    breit
                    hilfe="Aus dem Discord-Entwicklerportal. Ein neuer Token wird erst nach einem Neustart wirksam."
                    kind={<Text wert={wert("token")} setzen={(w) => setzen("token", w)}
                                typ="password" platzhalter="unverändert" />}
                />
                <div className="feldgitter">
                    <Feld
                        titel="Statusanzeige"
                        kind={<Auswahl wert={wert("status") || "online"} setzen={(w) => setzen("status", w)}
                                       liste={STATUS} leerText="— Vorgabe —" />}
                    />
                    <Feld
                        titel="Tätigkeit"
                        hilfe="Erscheint als „spielt …“."
                        kind={<Text wert={wert("activity")} setzen={(w) => setzen("activity", w)}
                                    platzhalter="Musik auf hoer.jetzt" />}
                    />
                </div>
                <Feld
                    titel="Wechselnde Tätigkeiten"
                    breit
                    hilfe="Eine je Zeile. Sind hier Zeilen eingetragen, wechselt der Bot durch."
                    kind={<Mehrzeilig wert={wert("activityRotation")} setzen={(w) => setzen("activityRotation", w)}
                                      zeilen={3} />}
                />
            </section>

            <section className="karte-flach">
                <h2>Anmeldung über Discord</h2>
                <div className="feldgitter">
                    <Feld
                        titel="Client-ID"
                        kind={<Text wert={wert("discordClientId")} setzen={(w) => setzen("discordClientId", w)} />}
                    />
                    <Feld
                        titel="Client-Secret"
                        hilfe="Leer = unverändert."
                        kind={<Text wert={wert("discordClientSecret")} setzen={(w) => setzen("discordClientSecret", w)}
                                    typ="password" platzhalter="unverändert" />}
                    />
                </div>
                <Feld
                    titel="Redirect-URI"
                    breit
                    hilfe="Muss zeichengenau so im Entwicklerportal stehen — sonst bricht die Anmeldung mit „invalid redirect_uri“ ab."
                    kind={<Text wert={wert("redirectUri")} setzen={(w) => setzen("redirectUri", w)}
                                platzhalter="https://hoer.jetzt/auth/discord/callback" />}
                />
                <Feld
                    titel="Notfall-Admins"
                    breit
                    hilfe="Discord-Benutzer-IDs, durch Komma getrennt. Sie kommen auch dann ins Panel, wenn die Admin-Tabelle leer ist — die Tür für den Abend, an dem niemand mehr hineinkommt."
                    kind={<Text wert={wert("adminUserIds")} setzen={(w) => setzen("adminUserIds", w)}
                                platzhalter="595602901653520384" />}
                />
            </section>

            <section className="karte-flach">
                <h2>KI-Anbieter</h2>
                <p className="leise">
                    Für den Chat und das KI-Radio. Modelle ohne Function-Calling erkennt der Bot
                    selbst und läuft dann ohne Werkzeuge weiter.
                </p>
                <div className="feldgitter">
                    <Feld
                        titel="Anbieter"
                        kind={<Auswahl wert={wert("llmProvider") || "ollama"} setzen={(w) => setzen("llmProvider", w)}
                                       liste={ANBIETER} leerText="— Vorgabe —" />}
                    />
                    <Feld
                        titel="Modell"
                        kind={<Text wert={wert("llmModel")} setzen={(w) => setzen("llmModel", w)}
                                    platzhalter="qwen3:8b" />}
                    />
                    <Feld
                        titel="Ollama-Adresse"
                        kind={<Text wert={wert("llmOllamaUrl")} setzen={(w) => setzen("llmOllamaUrl", w)}
                                    platzhalter="http://127.0.0.1:11434" />}
                    />
                    <Feld
                        titel="OpenAI-Basisadresse"
                        kind={<Text wert={wert("llmOpenAiBaseUrl")} setzen={(w) => setzen("llmOpenAiBaseUrl", w)}
                                    platzhalter="https://api.openai.com/v1" />}
                    />
                    <Feld
                        titel="API-Schlüssel"
                        hilfe="Leer = unverändert."
                        kind={<Text wert={wert("llmApiKey")} setzen={(w) => setzen("llmApiKey", w)}
                                    typ="password" platzhalter="unverändert" />}
                    />
                    <Feld
                        titel="Zeitlimit (ms)"
                        kind={<Zahl wert={wert("llmTimeoutMs")} setzen={(w) => setzen("llmTimeoutMs", w)}
                                    min={1000} max={600000} />}
                    />
                    <Feld
                        titel="Antwortlänge (Token)"
                        kind={<Zahl wert={wert("llmMaxTokens")} setzen={(w) => setzen("llmMaxTokens", w)}
                                    min={1} max={32000} />}
                    />
                    <Feld
                        titel="Gesprächsverlauf (Runden)"
                        hilfe="Wie viel der Bot vom bisherigen Gespräch mitschickt."
                        kind={<Zahl wert={wert("llmHistoryTurns")} setzen={(w) => setzen("llmHistoryTurns", w)}
                                    min={0} max={50} />}
                    />
                </div>
                <Feld
                    titel="Auswählbare Modelle"
                    breit
                    hilfe="Eines je Zeile. Steht hier nichts, gilt nur das Modell oben."
                    kind={<Mehrzeilig wert={wert("llmAvailableModels")} setzen={(w) => setzen("llmAvailableModels", w)}
                                      zeilen={3} />}
                />
                <Feld
                    titel="System-Prompt"
                    breit
                    hilfe="Bei Reasoning-Modellen wie qwen3 schaltet /no_think die <think>-Blöcke ab — die werden ohnehin gefiltert und kosten nur Antwortzeit."
                    kind={<Mehrzeilig wert={wert("llmSystemMessage")} setzen={(w) => setzen("llmSystemMessage", w)}
                                      zeilen={4} />}
                />
            </section>

            {leiste}
        </Betriebsseite>
    );
}
