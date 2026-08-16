-- Stufen fuer Audio-Knoten.
--
-- Wird vom Bot beim Start selbst ausgefuehrt (DB.java, gleiche Stelle wie die
-- uebrigen ALTER-Anweisungen). Hier zum Nachvollziehen und fuer den Fall, dass
-- jemand die Spalten von Hand pruefen will.

ALTER TABLE deployment_lavalink_nodes
    ADD COLUMN IF NOT EXISTS tier varchar(16) NOT NULL DEFAULT 'free';

ALTER TABLE deployment_lavalink_nodes
    ADD COLUMN IF NOT EXISTS max_players int(11) NOT NULL DEFAULT 0;

-- Die Auswahl fragt nach Stufe und aktiven Knoten - dafuer ein eigener Index.
ALTER TABLE deployment_lavalink_nodes
    ADD KEY IF NOT EXISTS idx_nodes_tier (bot_id, tier, enabled);

-- Premium wird wie KI-Chat und AI-Radio in guild_entitlements gefuehrt und
-- braucht deshalb keine eigene Tabelle. Eintrag entsteht beim Freischalten.
