-- hoer.jetzt - Schema fuer PostgreSQL
--
-- Wird beim Start von DB.java ausgefuehrt, Anweisung fuer Anweisung, und ist
-- beliebig oft wiederholbar. Deshalb ueberall IF NOT EXISTS: ein Update darf
-- keine Reihenfolge voraussetzen, und wer eine Version ueberspringt, bekommt
-- trotzdem alles.
--
-- ---------------------------------------------------------------------------
-- Warum eine gemeinsame Sequenz mit Versatz
--
-- Bei Multi-Master-Replikation vergibt jede Node ihre Nummern selbst. Zwei
-- Nodes, die beide bei 1 anfangen, kollidieren beim ersten Abgleich. Deshalb
-- bekommt jede Node einen eigenen Zahlenraum: Node 1 vergibt 1, 1001, 2001 -
-- Node 2 vergibt 2, 1002, 2002. Bis 1000 Nodes ist das kollisionsfrei, und die
-- Nummern bleiben klein genug, um sie am Telefon vorzulesen. UUIDs taeten es
-- auch, machen aber jede Fehlersuche muehsam.
--
-- ${HJ_NODE_NR} setzt DB.java ein (Vorgabe 1). Eine einzelne Node merkt davon
-- nichts - die Sequenz zaehlt dann eben in Tausenderschritten.
-- ---------------------------------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS hj_id_seq START WITH ${HJ_NODE_NR} INCREMENT BY 1000;

-- ---------------------------------------------------------------------------
-- Protokoll und globale Einstellungen
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS logs (
    type        text,
    module      text,
    value       text,
    "timestamp" timestamp
);

CREATE TABLE IF NOT EXISTS settings (
    id                    int,
    token                 text,
    activity              text,
    activity_rotation     text,
    status                text DEFAULT 'IDLE',
    brand_image_url       text,
    hero_image_url        text,
    maintenance_enabled   boolean,
    maintenance_message   text,
    legal_owner_name      text,
    legal_email           text,
    legal_address         text,
    web_base_url          text,
    no_guild_invite_url   text,
    discord_client_id     text,
    discord_client_secret text,
    redirect_uri          text,
    admin_user_ids        text,
    llm_provider          text,
    llm_ollama_url        text,
    llm_openai_base_url   text,
    llm_api_key           text,
    llm_model             text,
    llm_available_models  text,
    llm_timeout_ms        int,
    llm_temperature       double precision,
    llm_max_tokens        int,
    llm_history_turns     int,
    llm_system_message    text,
    created_at            timestamp DEFAULT current_timestamp,
    updated_at            timestamp DEFAULT current_timestamp
);

-- ---------------------------------------------------------------------------
-- Audio
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS lavalink_sessions (
    bot_id     int          NOT NULL,
    node_name  varchar(160) NOT NULL,
    session_id varchar(191),
    updated_at timestamp    NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (bot_id, node_name)
);

CREATE TABLE IF NOT EXISTS guild_playback_state (
    bot_id           int         NOT NULL,
    guild_id         varchar(32) NOT NULL,
    voice_channel_id varchar(32),
    current_encoded  text,
    position_ms      bigint      NOT NULL DEFAULT 0,
    queue_encoded    text,
    volume           int         NOT NULL DEFAULT 100,
    repeat_enabled   boolean     NOT NULL DEFAULT false,
    bass_enabled     boolean     NOT NULL DEFAULT false,
    smart_radio      boolean     NOT NULL DEFAULT false,
    radio_name       varchar(160),
    updated_at       timestamp   NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (bot_id, guild_id)
);

CREATE INDEX IF NOT EXISTS idx_playback_recent
    ON guild_playback_state (bot_id, updated_at);

-- ---------------------------------------------------------------------------
-- Module je Server
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS guild_module_settings (
    bot_id        int         NOT NULL,
    guild_id      varchar(32) NOT NULL,
    settings_json text        NOT NULL,
    updated_at    timestamp   DEFAULT current_timestamp,
    PRIMARY KEY (bot_id, guild_id)
);

-- Der Einladungs-Kurzlink stand bisher nur im JSON und wurde per
-- LIKE '%"slug":"x"%' gesucht. Zwei Nachteile: die Suche kann keinen Index
-- benutzen, und die Eindeutigkeit haengt an einer Pruefung im Programm - zwei
-- Server, die gleichzeitig denselben Namen speichern, bekommen beide ein Ja.
--
-- Als eigene Spalte erledigt die Datenbank beides: der Zugriff geht ueber den
-- Index, und der zweite Server bekommt einen Fehler statt eines gekaperten
-- Links. Die Spalte wird beim Speichern aus dem JSON mitgeschrieben.
ALTER TABLE guild_module_settings
    ADD COLUMN IF NOT EXISTS invite_slug varchar(32);

CREATE UNIQUE INDEX IF NOT EXISTS uq_invite_slug
    ON guild_module_settings (bot_id, invite_slug)
    WHERE invite_slug IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Deployments und Knoten
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS deployments (
    id             bigint       NOT NULL DEFAULT nextval('hj_id_seq'),
    bot_id         int          NOT NULL,
    deployment_key varchar(120) NOT NULL,
    display_name   varchar(160),
    web_port       int,
    base_url       text,
    redirect_uri   text,
    enabled        boolean      NOT NULL DEFAULT true,
    sort_order     int          NOT NULL DEFAULT 0,
    created_at     timestamp    DEFAULT current_timestamp,
    updated_at     timestamp    DEFAULT current_timestamp,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_bot_deployment
    ON deployments (bot_id, deployment_key);

CREATE TABLE IF NOT EXISTS deployment_lavalink_nodes (
    id                     bigint       NOT NULL DEFAULT nextval('hj_id_seq'),
    bot_id                 int          NOT NULL,
    deployment_key         varchar(120) NOT NULL,
    node_name              varchar(160),
    server_uri             text         NOT NULL,
    password               text,
    http_timeout_ms        int          NOT NULL DEFAULT 15000,
    resume_enabled         boolean      NOT NULL DEFAULT true,
    resume_timeout_seconds bigint       NOT NULL DEFAULT 180,
    enabled                boolean      NOT NULL DEFAULT true,
    sort_order             int          NOT NULL DEFAULT 0,
    tier                   varchar(16)  NOT NULL DEFAULT 'free',
    max_players            int          NOT NULL DEFAULT 0,
    created_at             timestamp    DEFAULT current_timestamp,
    updated_at             timestamp    DEFAULT current_timestamp,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_deployment_nodes
    ON deployment_lavalink_nodes (bot_id, deployment_key, enabled, sort_order);

CREATE INDEX IF NOT EXISTS idx_nodes_tier
    ON deployment_lavalink_nodes (bot_id, tier, enabled);

-- Woher ein Knoten stammt. Das entscheidet, wer ihn wieder loeschen darf:
--   manuell - im Adminbereich eingetragen
--   selbst  - hat sich per install.sh angemeldet
--   auto    - vom Autoscaling bei Hetzner erzeugt
-- Wichtig, weil das Speichern im Adminbereich die Knotentabelle austauscht
-- (loeschen und neu schreiben). Ohne diese Spalte wuerde ein Klick auf
-- "Speichern" jeden selbst angemeldeten Knoten mitnehmen - unbemerkt, denn
-- die Oberflaeche kennt ihn gar nicht.
ALTER TABLE deployment_lavalink_nodes
    ADD COLUMN IF NOT EXISTS herkunft varchar(16) NOT NULL DEFAULT 'manuell';

-- Adresse des Knoten-Agenten (Neustart, Version, Selbsttest). Leer bei
-- Knoten, die von Hand eingetragen wurden - dort gibt es nur "Neu verbinden".
ALTER TABLE deployment_lavalink_nodes
    ADD COLUMN IF NOT EXISTS agent_url text;

-- Wann sich der Knoten zuletzt gemeldet hat. Das Autoscaling raeumt nur
-- Knoten ab, die es selbst erzeugt hat und die sich laenger nicht melden.
ALTER TABLE deployment_lavalink_nodes
    ADD COLUMN IF NOT EXISTS zuletzt_gesehen timestamp;

-- Server-ID bei Hetzner, damit ein abgebauter Knoten auch dort verschwindet.
ALTER TABLE deployment_lavalink_nodes
    ADD COLUMN IF NOT EXISTS hetzner_id bigint;

-- Bewusst *kein* UNIQUE auf (bot_id, node_name): in bestehenden Installationen
-- koennen doppelte Namen liegen - der Lader verwirft den zweiten stillschweigend,
-- die Tabelle laesst sie aber zu. Ein nachtraeglicher eindeutiger Index wuerde
-- beim Schemalauf scheitern und den Bot am Starten hindern. Die Eindeutigkeit
-- stellt stattdessen KnotenRegistrierungService beim Eintragen her.
CREATE INDEX IF NOT EXISTS idx_nodes_herkunft
    ON deployment_lavalink_nodes (bot_id, herkunft);

-- ---------------------------------------------------------------------------
-- Tickets, Statistik, Dateien
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ticket_transcripts (
    id              bigint      NOT NULL DEFAULT nextval('hj_id_seq'),
    bot_id          int         NOT NULL,
    guild_id        varchar(32) NOT NULL,
    channel_id      varchar(32),
    opener_user_id  varchar(32),
    opener_display  varchar(160),
    ticket_subject  varchar(190),
    transcript_text text        NOT NULL,
    created_at      timestamp   DEFAULT current_timestamp,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_ticket_transcripts_guild
    ON ticket_transcripts (bot_id, guild_id, created_at);

CREATE TABLE IF NOT EXISTS music_track_events (
    id          bigint       NOT NULL DEFAULT nextval('hj_id_seq'),
    bot_id      int          NOT NULL,
    guild_id    varchar(32)  NOT NULL,
    title       varchar(255) NOT NULL,
    author      varchar(255),
    uri         text,
    identifier  varchar(255),
    source_name varchar(80),
    duration_ms bigint,
    is_stream   boolean      NOT NULL DEFAULT false,
    created_at  timestamp    DEFAULT current_timestamp,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_music_track_events_guild
    ON music_track_events (bot_id, guild_id, created_at);

CREATE INDEX IF NOT EXISTS idx_music_track_events_lookup
    ON music_track_events (bot_id, guild_id, is_stream, created_at);

CREATE TABLE IF NOT EXISTS uploaded_assets (
    asset_id           varchar(64)  NOT NULL,
    bot_id             int          NOT NULL,
    created_by_user_id varchar(32),
    original_name      varchar(255),
    content_type       varchar(120) NOT NULL,
    base64_data        text         NOT NULL,
    size_bytes         int          NOT NULL DEFAULT 0,
    created_at         timestamp    DEFAULT current_timestamp,
    PRIMARY KEY (asset_id)
);

CREATE INDEX IF NOT EXISTS idx_uploaded_assets_bot
    ON uploaded_assets (bot_id, created_at);

CREATE TABLE IF NOT EXISTS music_listener_events (
    id               bigint       NOT NULL DEFAULT nextval('hj_id_seq'),
    bot_id           int          NOT NULL,
    guild_id         varchar(32)  NOT NULL,
    listener_hash    varchar(64)  NOT NULL,
    playback_kind    varchar(32)  NOT NULL,
    title            varchar(255) NOT NULL,
    author           varchar(255),
    identifier       varchar(255),
    source_label     varchar(190),
    listened_seconds int          NOT NULL DEFAULT 0,
    is_stream        boolean      NOT NULL DEFAULT false,
    started_at       timestamp    DEFAULT current_timestamp,
    ended_at         timestamp    DEFAULT current_timestamp,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_listener_events_time
    ON music_listener_events (bot_id, started_at);

CREATE INDEX IF NOT EXISTS idx_listener_events_lookup
    ON music_listener_events (bot_id, guild_id, playback_kind, started_at);

CREATE INDEX IF NOT EXISTS idx_listener_events_listener
    ON music_listener_events (bot_id, listener_hash, started_at);

-- ---------------------------------------------------------------------------
-- Rechte
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS bot_admins (
    bot_id            int         NOT NULL,
    user_id           varchar(32) NOT NULL,
    role              varchar(16) NOT NULL DEFAULT 'ADMIN',
    display_name      varchar(160),
    added_by          varchar(32),
    application_owner boolean     NOT NULL DEFAULT false,
    created_at        timestamp   DEFAULT current_timestamp,
    updated_at        timestamp   DEFAULT current_timestamp,
    PRIMARY KEY (bot_id, user_id)
);

CREATE TABLE IF NOT EXISTS guild_role_permissions (
    bot_id     int         NOT NULL,
    guild_id   varchar(32) NOT NULL,
    role_id    varchar(32) NOT NULL,
    permission varchar(40) NOT NULL,
    created_at timestamp   DEFAULT current_timestamp,
    PRIMARY KEY (bot_id, guild_id, role_id, permission)
);

CREATE INDEX IF NOT EXISTS idx_guild_role_permissions
    ON guild_role_permissions (bot_id, guild_id);

CREATE TABLE IF NOT EXISTS guild_entitlements (
    bot_id      int         NOT NULL,
    guild_id    varchar(32) NOT NULL,
    feature     varchar(40) NOT NULL,
    enabled     boolean     NOT NULL DEFAULT false,
    daily_limit int         NOT NULL DEFAULT 0,
    note        varchar(255),
    granted_by  varchar(32),
    created_at  timestamp   DEFAULT current_timestamp,
    updated_at  timestamp   DEFAULT current_timestamp,
    PRIMARY KEY (bot_id, guild_id, feature)
);

CREATE TABLE IF NOT EXISTS guild_feature_usage (
    bot_id     int         NOT NULL,
    guild_id   varchar(32) NOT NULL,
    feature    varchar(40) NOT NULL,
    usage_day  date        NOT NULL,
    used_count int         NOT NULL DEFAULT 0,
    PRIMARY KEY (bot_id, guild_id, feature, usage_day)
);

CREATE INDEX IF NOT EXISTS idx_guild_feature_usage_day
    ON guild_feature_usage (bot_id, usage_day);

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id            bigint      NOT NULL DEFAULT nextval('hj_id_seq'),
    bot_id        int         NOT NULL,
    actor_user_id varchar(32),
    actor_name    varchar(160),
    action        varchar(80) NOT NULL,
    target_type   varchar(40),
    target_id     varchar(64),
    details       text,
    created_at    timestamp   DEFAULT current_timestamp,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_time
    ON admin_audit_log (bot_id, created_at);

-- ---------------------------------------------------------------------------
-- Verbund: welche Node faehrt welche Shards
--
-- Der Controller ist die einzige Stelle, die Shard-Nummern vergibt. Das ist
-- kein Ordnungsprinzip, sondern Notwendigkeit: Discord erlaubt je Nummer genau
-- eine Verbindung und wirft beide hinaus, die sich streiten. Wer Nummern von
-- Hand verteilt, macht das genau einmal falsch.
--
-- letzte_meldung ist die Lebendmeldung des Agenten. Bleibt sie aus, gilt die
-- Node als verschwunden und ihre Shards duerfen neu vergeben werden - aber
-- erst nach einer Karenzzeit, sonst reisst ein Netzwackler die Wiedergabe ab.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS cluster_nodes (
    node_name       varchar(64) NOT NULL,
    privat_ip       varchar(64),
    node_nr         int         NOT NULL DEFAULT 1,
    shards_von      int,
    shards_bis      int,
    shards_gesamt   int,
    release_version varchar(40),
    zustand_json    text,
    letzte_meldung  timestamp   DEFAULT current_timestamp,
    erstellt_am     timestamp   DEFAULT current_timestamp,
    PRIMARY KEY (node_name)
);

CREATE INDEX IF NOT EXISTS idx_cluster_nodes_meldung
    ON cluster_nodes (letzte_meldung);

-- Was der Verbund als Ganzes gerade fahren soll. Eine Zeile, id = 1.
CREATE TABLE IF NOT EXISTS cluster_ziel (
    id              int         NOT NULL DEFAULT 1,
    release_version varchar(40),
    shards_gesamt   int         NOT NULL DEFAULT 1,
    gesetzt_von     varchar(64),
    gesetzt_am      timestamp   DEFAULT current_timestamp,
    PRIMARY KEY (id)
);
