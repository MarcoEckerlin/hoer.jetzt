import Uebersicht from "./Uebersicht.jsx";
import Player from "./Player.jsx";
import Radio from "./Radio.jsx";
import Willkommen from "./Willkommen.jsx";
import Verify from "./Verify.jsx";
import ReaktionsRollen from "./ReaktionsRollen.jsx";
import Tickets from "./Tickets.jsx";
import JoinToCreate from "./JoinToCreate.jsx";
import Logs from "./Logs.jsx";
import KiChat from "./KiChat.jsx";
import Einladung from "./Einladung.jsx";
import Einladungstracker from "./Einladungstracker.jsx";
import Vorlagen from "./Vorlagen.jsx";
import Befehle from "./Befehle.jsx";
import { SYMBOLE } from "../teile/Symbole.jsx";

/**
 * Welche Modulseiten es gibt und in welcher Reihenfolge.
 *
 * <p>{@code sichtbar} entscheidet, ob ein Modul ueberhaupt in der Leiste
 * auftaucht. Das betrifft die Freigaben: KI-Chat und KI-Radio kosten
 * Rechenzeit und werden einzeln vergeben. Ein Menuepunkt, der beim Anklicken
 * nur mitteilt, dass er nicht benutzt werden darf, ist kein Hinweis, sondern
 * eine Sackgasse mit Ankuendigung.</p>
 *
 * <p>Eine Liste statt einer Wegetabelle im Dashboard: eine neue Seite ist ein
 * Eintrag hier und sonst nichts. Die Kennung steht in der Adresse - sie darf
 * sich deshalb nicht mehr aendern, sonst laufen gespeicherte Links ins Leere.</p>
 *
 * <p>{@code symbol} ist das Zeichen links in der Leiste. Vorher stand dort
 * ueberall dieselbe Raute - was zwar nach Discord aussah, aber vierzehn Zeilen
 * ergab, die sich nur durch ihren Text unterschieden. Ein Symbol findet das
 * Auge schneller als ein Wort.</p>
 *
 * <p>{@code aktiv} entscheidet ueber den Punkt neben dem Namen. Er beantwortet
 * die Frage, die man beim Oeffnen des Dashboards hat: was ist auf diesem Server
 * ueberhaupt eingeschaltet?</p>
 */
export const MODULE = [
    // Ohne "aktiv" - eine Uebersicht ist nie ein- oder ausgeschaltet.
    { id: "start", symbol: SYMBOLE.uebersicht, titel: "Übersicht", seite: Uebersicht, gruppe: "Server" },
    { id: "player", symbol: SYMBOLE.wiedergabe, titel: "Wiedergabe", seite: Player, gruppe: "Audio" },
    { id: "radio", symbol: SYMBOLE.radio, titel: "Radio", seite: Radio, gruppe: "Audio" },
    { gruppe: "Mitglieder", id: "willkommen", symbol: SYMBOLE.willkommen, titel: "Willkommen", seite: Willkommen, aktiv: (k) => k.welcome?.enabled },
    { gruppe: "Mitglieder", id: "verify", symbol: SYMBOLE.verify, titel: "Verifizierung", seite: Verify, aktiv: (k) => k.verify?.enabled },
    { gruppe: "Mitglieder", id: "rollen", symbol: SYMBOLE.rollen, titel: "Reaktionsrollen", seite: ReaktionsRollen, aktiv: (k) => k.reactionRoles?.enabled },
    { gruppe: "Community", id: "tickets", symbol: SYMBOLE.tickets, titel: "Tickets", seite: Tickets, aktiv: (k) => k.tickets?.enabled },
    { gruppe: "Community", id: "jtc", symbol: SYMBOLE.sprache, titel: "Join to Create", seite: JoinToCreate, aktiv: (k) => k.joinToCreate?.enabled },
    { gruppe: "Community", id: "ki", symbol: SYMBOLE.ki, titel: "KI-Chat", seite: KiChat, aktiv: (k) => k.llm?.enabled,
      // Nicht freigeschaltet heisst: gar nicht erst zeigen. Ein Eintrag,
      // der beim Anklicken nur "nicht freigeschaltet" sagt, ist eine
      // Sackgasse mit Ankuendigung.
      sichtbar: (k) => k.entitlements?.llmChat },
    { gruppe: "Verwaltung", id: "logs", symbol: SYMBOLE.protokoll, titel: "Protokoll", seite: Logs, aktiv: (k) => k.discordLogs?.enabled },
    { gruppe: "Mitglieder", id: "einladungen", symbol: SYMBOLE.einladungen, titel: "Einladungen", seite: Einladungstracker, aktiv: (k) => k.inviteTracker?.enabled },
    { gruppe: "Verwaltung", id: "invite", symbol: SYMBOLE.einladungen, titel: "Einladungslink", seite: Einladung },
    { gruppe: "Verwaltung", id: "vorlagen", symbol: SYMBOLE.vorlagen, titel: "Vorlagen", seite: Vorlagen, aktiv: (k) => (k.embedVorlagen || []).length > 0 },
    { gruppe: "Verwaltung", id: "befehle", symbol: SYMBOLE.befehle, titel: "Befehle", seite: Befehle }
];
