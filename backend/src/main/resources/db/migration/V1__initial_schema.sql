-- WAN Download Manager local schema (SQLite). Times are epoch milliseconds (INTEGER), ids are UUID text.
-- No passwords and no authentication tokens are ever stored here (tokens live in the OS credential store).

CREATE TABLE download_categories (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL UNIQUE,
    extensions    TEXT NOT NULL DEFAULT '',          -- comma-separated, lower case, no dot
    subfolder     TEXT NOT NULL,
    built_in      INTEGER NOT NULL DEFAULT 0 CHECK (built_in IN (0, 1)),
    created_at    INTEGER NOT NULL
);

CREATE TABLE download_queues (
    id             TEXT PRIMARY KEY,
    name           TEXT NOT NULL UNIQUE,
    priority       INTEGER NOT NULL DEFAULT 0,
    max_concurrent INTEGER NOT NULL DEFAULT 3 CHECK (max_concurrent BETWEEN 1 AND 16),
    state          TEXT NOT NULL DEFAULT 'STOPPED' CHECK (state IN ('RUNNING', 'STOPPED')),
    built_in       INTEGER NOT NULL DEFAULT 0 CHECK (built_in IN (0, 1)),
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);

CREATE TABLE downloads (
    id               TEXT PRIMARY KEY,
    url              TEXT NOT NULL,
    final_url        TEXT,
    page_url         TEXT,
    file_name        TEXT NOT NULL,
    save_dir         TEXT NOT NULL,
    temp_path        TEXT,
    category_id      TEXT REFERENCES download_categories (id) ON DELETE SET NULL,
    status           TEXT NOT NULL CHECK (status IN ('QUEUED', 'DOWNLOADING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    total_bytes      INTEGER,                            -- NULL = unknown length
    downloaded_bytes INTEGER NOT NULL DEFAULT 0,
    mime_type        TEXT,
    etag             TEXT,
    last_modified    TEXT,
    resumable        INTEGER NOT NULL DEFAULT 0 CHECK (resumable IN (0, 1)),
    connections      INTEGER NOT NULL DEFAULT 1 CHECK (connections BETWEEN 1 AND 16),
    speed_limit_bps  INTEGER NOT NULL DEFAULT 0,         -- 0 = unlimited
    retry_count      INTEGER NOT NULL DEFAULT 0,
    max_retries      INTEGER NOT NULL DEFAULT 5,
    error_message    TEXT,
    sha256           TEXT,
    source           TEXT NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'BATCH', 'BROWSER', 'MEDIA', 'SCHEDULED')),
    priority         INTEGER NOT NULL DEFAULT 0,
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL,
    started_at       INTEGER,
    completed_at     INTEGER
);
CREATE INDEX idx_downloads_status ON downloads (status);
CREATE INDEX idx_downloads_url ON downloads (url);
CREATE INDEX idx_downloads_created ON downloads (created_at);

-- Recovery metadata: one row per byte range of a segmented download.
CREATE TABLE download_parts (
    id               TEXT PRIMARY KEY,
    download_id      TEXT NOT NULL REFERENCES downloads (id) ON DELETE CASCADE,
    part_index       INTEGER NOT NULL,
    start_byte       INTEGER NOT NULL,
    end_byte         INTEGER,                            -- inclusive; NULL = until end of stream
    downloaded_bytes INTEGER NOT NULL DEFAULT 0,
    status           TEXT NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'DONE', 'FAILED')),
    UNIQUE (download_id, part_index)
);

-- Non-sensitive request headers only (e.g. Referer). Cookie and Authorization are refused by the validator.
CREATE TABLE download_headers (
    id          TEXT PRIMARY KEY,
    download_id TEXT NOT NULL REFERENCES downloads (id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    value       TEXT NOT NULL
);
CREATE INDEX idx_download_headers_download ON download_headers (download_id);

CREATE TABLE queue_items (
    id          TEXT PRIMARY KEY,
    queue_id    TEXT NOT NULL REFERENCES download_queues (id) ON DELETE CASCADE,
    download_id TEXT NOT NULL REFERENCES downloads (id) ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    UNIQUE (download_id)                                 -- a download is in at most one queue
);
CREATE INDEX idx_queue_items_order ON queue_items (queue_id, position);

CREATE TABLE media_sources (
    id           TEXT PRIMARY KEY,
    page_url     TEXT NOT NULL,
    source_url   TEXT NOT NULL,
    title        TEXT,
    mime_type    TEXT,
    kind         TEXT NOT NULL CHECK (kind IN ('FILE', 'HLS', 'DASH')),
    duration_sec REAL,
    thumbnail    TEXT,
    browser      TEXT,
    fingerprint  TEXT NOT NULL UNIQUE,                   -- SHA-256 of normalised source URL (duplicate filter)
    detected_at  INTEGER NOT NULL
);
CREATE INDEX idx_media_sources_detected ON media_sources (detected_at);

-- Only formats verified from the source itself (manifest variants or probed file) are stored.
CREATE TABLE media_formats (
    id              TEXT PRIMARY KEY,
    media_source_id TEXT NOT NULL REFERENCES media_sources (id) ON DELETE CASCADE,
    format_url      TEXT NOT NULL,
    container       TEXT,
    width           INTEGER,
    height          INTEGER,
    bandwidth       INTEGER,
    video_codec     TEXT,
    audio_codec     TEXT,
    estimated_bytes INTEGER,
    label           TEXT NOT NULL
);
CREATE INDEX idx_media_formats_source ON media_formats (media_source_id);

CREATE TABLE browser_sessions (
    id              TEXT PRIMARY KEY,
    browser         TEXT NOT NULL CHECK (browser IN ('CHROME', 'EDGE', 'FIREFOX', 'UNKNOWN')),
    extension_ver   TEXT,
    connected_at    INTEGER NOT NULL,
    last_seen_at    INTEGER NOT NULL
);

CREATE TABLE download_history (
    id           TEXT PRIMARY KEY,
    download_id  TEXT,                                   -- kept after the download row is deleted
    file_name    TEXT NOT NULL,
    file_type    TEXT,
    url          TEXT NOT NULL,
    size_bytes   INTEGER,
    status       TEXT NOT NULL,
    save_path    TEXT,
    completed_at INTEGER NOT NULL
);
CREATE INDEX idx_history_completed ON download_history (completed_at);
CREATE INDEX idx_history_name ON download_history (file_name);

CREATE TABLE schedules (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    queue_id        TEXT REFERENCES download_queues (id) ON DELETE CASCADE,
    start_time      TEXT NOT NULL,                       -- HH:mm local time
    end_time        TEXT,                                -- HH:mm, optional download window end
    days_of_week    TEXT NOT NULL DEFAULT 'MON,TUE,WED,THU,FRI,SAT,SUN',
    one_shot_date   TEXT,                                -- yyyy-MM-dd for a non-repeating schedule
    speed_limit_bps INTEGER NOT NULL DEFAULT 0,
    enabled         INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    last_run_at     INTEGER,
    created_at      INTEGER NOT NULL
);

CREATE TABLE app_settings (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE speed_statistics (
    id          TEXT PRIMARY KEY,
    bucket_at   INTEGER NOT NULL UNIQUE,                 -- start of minute
    bytes       INTEGER NOT NULL,
    peak_bps    INTEGER NOT NULL
);

CREATE TABLE conversion_jobs (
    id            TEXT PRIMARY KEY,
    input_path    TEXT NOT NULL,
    output_path   TEXT NOT NULL,
    preset        TEXT NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    progress      REAL NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);

-- Display cache of the WAN account (claims only; tokens are in the OS credential store).
CREATE TABLE account_cache (
    sub               TEXT PRIMARY KEY,
    name              TEXT,
    preferred_username TEXT,
    email             TEXT,
    email_verified    INTEGER,
    phone             TEXT,
    phone_verified    INTEGER,
    locale            TEXT,
    issuer            TEXT NOT NULL,
    verified_at       INTEGER NOT NULL
);

-- Offline entitlement cache. `signature` is an HMAC (key held in the OS credential store) so that
-- editing this table cannot grant features.
CREATE TABLE entitlement_cache (
    sub          TEXT PRIMARY KEY,
    tier         TEXT NOT NULL,
    status       TEXT NOT NULL,
    roles        TEXT NOT NULL,
    verified_at  INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,
    signature    TEXT NOT NULL
);

CREATE TABLE device_registration_cache (
    installation_id TEXT PRIMARY KEY,
    device_name     TEXT NOT NULL,
    registered_with TEXT,                                -- NULL: the hub has no device API (see docs)
    last_verified_at INTEGER,
    created_at      INTEGER NOT NULL
);

CREATE TABLE application_events (
    id         TEXT PRIMARY KEY,
    level      TEXT NOT NULL CHECK (level IN ('INFO', 'WARN', 'ERROR', 'AUDIT')),
    category   TEXT NOT NULL,
    message    TEXT NOT NULL,
    subject_id TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX idx_events_created ON application_events (created_at);

INSERT INTO download_categories (id, name, extensions, subfolder, built_in, created_at) VALUES
 ('cat-video',      'Video',      'mp4,mkv,webm,avi,mov,m4v,flv,wmv,ts', 'Video', 1, 0),
 ('cat-music',      'Music',      'mp3,m4a,aac,wav,flac,ogg,opus,wma',    'Music', 1, 0),
 ('cat-compressed', 'Compressed', 'zip,rar,7z,tar,gz,bz2,xz,tgz',         'Compressed', 1, 0),
 ('cat-documents',  'Documents',  'pdf,doc,docx,xls,xlsx,ppt,pptx,txt,csv,epub,odt', 'Documents', 1, 0),
 ('cat-programs',   'Programs',   'exe,msi,dmg,pkg,deb,rpm,appimage,msix', 'Programs', 1, 0),
 ('cat-apk',        'APKs',       'apk,aab,xapk',                         'APKs', 1, 0),
 ('cat-images',     'Images',     'png,jpg,jpeg,gif,webp,bmp,svg,tiff,avif', 'Images', 1, 0),
 ('cat-disk',       'Disk Images','iso,img,vhd,vmdk',                     'Disk Images', 1, 0),
 ('cat-other',      'Other',      '',                                     'Other', 1, 0);

INSERT INTO download_queues (id, name, priority, max_concurrent, state, built_in, created_at, updated_at) VALUES
 ('queue-main', 'Main queue', 0, 3, 'RUNNING', 1, 0, 0);
