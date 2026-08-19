import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App.jsx";
import "./stil.css";
import { starten } from "./lib/farbschema.js";

// Vor dem ersten Zeichnen: sonst blitzt kurz die helle Fassung auf.
starten();

createRoot(document.getElementById("wurzel")).render(
    <React.StrictMode>
        <App />
    </React.StrictMode>
);
