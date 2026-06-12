alter table audio_stream_session
    add column session_ended_at timestamp with time zone,
    add column session_close_reason text,
    add column session_conversation_memory_id uuid,
    add column session_post_processing_status varchar(32) not null default 'pending';

alter table conversation_memory
    add column source_stream_session_id uuid references audio_stream_session(id),
    add column correction_of_id uuid references conversation_memory(id),
    add column evidence_refs jsonb not null default '[]'::jsonb;

alter table audio_stream_session
    add constraint fk_audio_stream_session_memory
    foreign key (session_conversation_memory_id) references conversation_memory(id);

create index idx_conversation_memory_stream_session on conversation_memory(source_stream_session_id);
create index idx_conversation_memory_correction_of on conversation_memory(correction_of_id);
