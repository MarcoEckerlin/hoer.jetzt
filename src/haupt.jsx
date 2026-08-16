import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App.jsx";
import "./stil.css";

createRoot(document.getElementById("wurzel")).render(
    <React.StrictMode>
        <App />
    </React.StrictMode>
);
