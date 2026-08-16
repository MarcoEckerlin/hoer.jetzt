import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Im Entwicklungsbetrieb laeuft der Bot woanders. Ohne diesen Weiterleiter
// muesste man CORS oeffnen - und was man fuer die Entwicklung oeffnet, bleibt
// erfahrungsgemaess offen.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": { target: process.env.HJ_CORE_URL || "http://localhost:8080", changeOrigin: true },
      "/auth": { target: process.env.HJ_CORE_URL || "http://localhost:8080", changeOrigin: true },
      "/public": { target: process.env.HJ_CORE_URL || "http://localhost:8080", changeOrigin: true }
    }
  },
  build: { outDir: "dist", sourcemap: false }
});
