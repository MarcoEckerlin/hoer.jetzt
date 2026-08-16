import React from "react";

/**
 * Die Symbole der Oberflaeche.
 *
 * <p>Handgeschriebenes SVG statt einer Symbolbibliothek. Der Grund ist nicht
 * Sparsamkeit, sondern Gewicht und Kontrolle: die ueblichen Pakete bringen
 * mehrere hundert Symbole mit, von denen hier achtzehn gebraucht werden, und
 * liefern sie in einer Groesse und Strichstaerke, die man anschliessend doch
 * wieder ueberschreibt.</p>
 *
 * <p>Alle folgen derselben Bauweise: 24er-Raster, Strich statt Flaeche,
 * {@code currentColor} statt fester Farbe. Damit erben sie die Schriftfarbe der
 * Zeile - ein Symbol, das im hellen Modus grau bleibt, waehrend der Text dunkel
 * wird, faellt sofort auf.</p>
 */
function Symbol({ kinder, ...rest }) {
    return (
        <svg
            className="symbol"
            viewBox="0 0 24 24"
            width="16"
            height="16"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
            {...rest}
        >
            {kinder}
        </svg>
    );
}

export const SYMBOLE = {
    uebersicht: <Symbol kinder={<><rect x="3" y="3" width="7" height="9" rx="1.5" /><rect x="14" y="3" width="7" height="5" rx="1.5" /><rect x="14" y="12" width="7" height="9" rx="1.5" /><rect x="3" y="16" width="7" height="5" rx="1.5" /></>} />,
    wiedergabe: <Symbol kinder={<><path d="M9 18V6l10-2v12" /><circle cx="6.5" cy="18" r="2.5" /><circle cx="16.5" cy="16" r="2.5" /></>} />,
    radio: <Symbol kinder={<><rect x="2" y="8" width="20" height="12" rx="2.5" /><circle cx="8" cy="14" r="2.5" /><path d="M14 12h5M14 16h5M17 8 8.5 4" /></>} />,
    willkommen: <Symbol kinder={<><path d="M15 20v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="8.5" cy="7" r="3.5" /><path d="M18 8v6M15 11h6" /></>} />,
    verify: <Symbol kinder={<><path d="M12 3 4 6v6c0 4.5 3.2 8.3 8 9 4.8-.7 8-4.5 8-9V6z" /><path d="m9 12 2 2 4-4" /></>} />,
    rollen: <Symbol kinder={<><circle cx="12" cy="12" r="3.5" /><path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1" /></>} />,
    tickets: <Symbol kinder={<><path d="M3 8a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4z" /><path d="M13 6v2M13 11v2M13 16v2" /></>} />,
    sprache: <Symbol kinder={<><rect x="9" y="2" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0 0 14 0M12 18v4M8 22h8" /></>} />,
    ki: <Symbol kinder={<><path d="M12 3v3M12 18v3M3 12h3M18 12h3" /><rect x="6" y="6" width="12" height="12" rx="3" /><circle cx="10" cy="11" r="1" /><circle cx="14" cy="11" r="1" /><path d="M10 15h4" /></>} />,
    protokoll: <Symbol kinder={<><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5M9 13h6M9 17h4" /></>} />,
    einladungen: <Symbol kinder={<><path d="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1" /><path d="M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1" /></>} />,
    vorlagen: <Symbol kinder={<><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M3 9h18M8 13h8M8 16h5" /></>} />,
    befehle: <Symbol kinder={<><rect x="2" y="4" width="20" height="16" rx="2" /><path d="m7 10 2.5 2.5L7 15M13 15h4" /></>} />,
    verbund: <Symbol kinder={<><circle cx="12" cy="5" r="2.5" /><circle cx="5" cy="18" r="2.5" /><circle cx="19" cy="18" r="2.5" /><path d="M10.5 7 6.5 15.5M13.5 7l4 8.5M7.5 18h9" /></>} />,
    knoten: <Symbol kinder={<><rect x="3" y="4" width="18" height="6" rx="1.5" /><rect x="3" y="14" width="18" height="6" rx="1.5" /><path d="M7 7h.01M7 17h.01" /></>} />,
    werkzeug: <Symbol kinder={<><path d="M14.5 5.5a4 4 0 0 0 5.3 5.3l-8.3 8.3a2.5 2.5 0 0 1-3.6-3.6z" /><path d="m5 5 3 3" /></>} />
};

export default SYMBOLE;
