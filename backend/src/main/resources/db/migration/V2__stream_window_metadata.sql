alter table audio_event
    add column window_index integer,
    add column window_started_at timestamp with time zone,
    add column window_ended_at timestamp with time zone,
    add column is_final_window boolean not null default false;

create index idx_audio_event_stream_window on audio_event(stream_session_id, window_index);

alter table audio_stream_session
    add column window_count integer not null default 0,
    add column processed_window_count integer not null default 0,
    add column failed_window_count integer not null default 0;
