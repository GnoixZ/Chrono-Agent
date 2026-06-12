alter table audio_stream_session
    add column session_status varchar(32) not null default 'listening',
    add column session_started_at timestamp with time zone;

create index idx_audio_stream_user_session_started on audio_stream_session(user_id, session_started_at desc);
