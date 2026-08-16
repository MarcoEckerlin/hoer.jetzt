import React from "react";

/**
 * Der bewegte Hintergrund im Kopfbereich.
 *
 * <p>Bewusst als SVG mit CSS-Animation statt als Video oder Canvas: es wiegt
 * nichts, skaliert auf jedem Bildschirm und laeuft auch dann, wenn jemand
 * JavaScript sparsam behandelt. Wer Bewegung nicht mag, bekommt ueber
 * prefers-reduced-motion ein ruhiges Bild - siehe stil.css.</p>
 */
export default function Wellen() {
    // Feste Werte statt Zufall: der Aufbau soll bei jedem Laden gleich
    // aussehen, sonst wirkt die Seite bei jedem Besuch ein bisschen anders.
    const balken = [38, 64, 22, 88, 46, 72, 30, 96, 54, 26, 80, 42, 68, 34, 90, 50, 24, 76, 40, 60];

    return (
        <div className="wellen" aria-hidden="true">
            <div className="wellen-verlauf" />
            <div className="wellen-balken">
                {balken.map((hoehe, i) => (
                    <span
                        key={i}
                        style={{
                            height: `${hoehe}%`,
                            animationDelay: `${(i % 7) * 0.18}s`,
                            animationDuration: `${1.4 + (i % 5) * 0.22}s`
                        }}
                    />
                ))}
            </div>
        </div>
    );
}
