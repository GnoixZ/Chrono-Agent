create table audio_stream_session (
    id uuid primary key,
    user_id varchar(128) not null,
    device_id varchar(128),
    source_type varchar(64) not null,
    sample_rate integer,
    codec varchar(32),
    started_at timestamp with time zone not null,
    last_active_at timestamp with time zone not null,
    closed_at timestamp with time zone,
    status varchar(32) not null,
    close_reason text,
    current_audio_event_id uuid,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create unique index ux_audio_stream_one_active on audio_stream_session(user_id) where status = 'active';
create index idx_audio_stream_user_started on audio_stream_session(user_id, started_at desc);

create table audio_event (
    id uuid primary key,
    user_id varchar(128) not null,
    source_type varchar(64) not null,
    started_at timestamp with time zone not null,
    ended_at timestamp with time zone,
    audio_uri text not null,
    processing_status varchar(32) not null,
    stream_session_id uuid references audio_stream_session(id),
    sample_rate integer,
    codec varchar(32),
    duration_ms integer,
    retention_expires_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

alter table audio_stream_session
add constraint fk_audio_stream_current_event
foreign key (current_audio_event_id) references audio_event(id);

create index idx_audio_event_user_started on audio_event(user_id, started_at desc);
create index idx_audio_event_processing on audio_event(processing_status, created_at);

create table health_event (
    id uuid primary key,
    user_id varchar(128) not null,
    event_type varchar(64) not null,
    measured_at timestamp with time zone not null,
    value_numeric double precision,
    value_text text,
    unit varchar(32),
    source varchar(64) not null,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamp with time zone not null
);

create index idx_health_event_user_time on health_event(user_id, measured_at desc);
create index idx_health_event_user_type_time on health_event(user_id, event_type, measured_at desc);

create table conversation_session (
    id uuid primary key,
    user_id varchar(128) not null,
    title text not null,
    session_type varchar(64) not null,
    started_at timestamp with time zone not null,
    last_message_at timestamp with time zone not null,
    status varchar(32) not null,
    source varchar(64) not null
);

create table agent_message (
    id uuid primary key,
    conversation_session_id uuid not null references conversation_session(id),
    user_id varchar(128) not null,
    role varchar(32) not null,
    content_type varchar(64) not null,
    content text,
    content_ref text,
    source_event_id uuid,
    model_name varchar(128),
    safety_level varchar(64),
    created_at timestamp with time zone not null,
    deleted_at timestamp with time zone
);

create index idx_agent_message_session_time on agent_message(conversation_session_id, created_at);

create table agent_run (
    id uuid primary key,
    conversation_session_id uuid not null references conversation_session(id),
    trigger_message_id uuid references agent_message(id),
    status varchar(32) not null,
    context_window_start timestamp with time zone,
    context_window_end timestamp with time zone,
    short_term_memory_ref text,
    retrieved_context_ref text,
    model_request_ref text,
    model_response_ref text,
    safety_result jsonb not null default '{}'::jsonb,
    created_at timestamp with time zone not null,
    completed_at timestamp with time zone
);

create table conversation_memory (
    id uuid primary key,
    user_id varchar(128) not null,
    source_type varchar(64) not null,
    source_audio_event_id uuid references audio_event(id),
    source_conversation_session_id uuid references conversation_session(id),
    started_at timestamp with time zone,
    ended_at timestamp with time zone,
    title text not null,
    overview text not null,
    language varchar(16),
    category varchar(64),
    status varchar(32) not null,
    post_processing_status varchar(32) not null,
    processing_attempts integer not null default 0,
    last_error_type varchar(128),
    last_error_message text,
    discarded boolean not null default false,
    discard_reason text,
    visibility varchar(32) not null default 'private',
    transcript_ref text,
    speaker_refs jsonb not null default '[]'::jsonb,
    health_refs jsonb not null default '[]'::jsonb,
    topic_tags jsonb not null default '[]'::jsonb,
    emotion_tags jsonb not null default '[]'::jsonb,
    suggested_actions jsonb not null default '[]'::jsonb,
    suggested_events jsonb not null default '[]'::jsonb,
    embedding_ref text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted_at timestamp with time zone
);

create index idx_conversation_memory_user_started on conversation_memory(user_id, started_at desc);
create index idx_conversation_memory_status on conversation_memory(status, post_processing_status, created_at);
create index idx_conversation_memory_topic_tags on conversation_memory using gin(topic_tags);

create table speaker_cluster (
    id uuid primary key,
    user_id varchar(128) not null,
    display_name varchar(255) not null,
    status varchar(32) not null,
    created_from varchar(64) not null,
    first_seen_at timestamp with time zone not null,
    last_seen_at timestamp with time zone not null,
    match_confidence_summary jsonb not null default '{}'::jsonb,
    user_labeled boolean not null default false,
    label_suggestion varchar(255),
    label_suggestion_source varchar(64),
    label_suggestion_confidence double precision,
    merged_into_id uuid,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_speaker_cluster_user_status on speaker_cluster(user_id, status);
create index idx_speaker_cluster_user_seen on speaker_cluster(user_id, last_seen_at desc);

create table speaker_segment (
    id uuid primary key,
    audio_event_id uuid not null references audio_event(id),
    speaker_cluster_id uuid references speaker_cluster(id),
    speaker_id integer not null,
    is_user boolean not null default false,
    person_id uuid,
    start_ms integer not null,
    end_ms integer not null,
    transcript text not null,
    language varchar(16),
    confidence double precision not null,
    emotion_tags jsonb not null default '[]'::jsonb,
    topic_tags jsonb not null default '[]'::jsonb,
    created_at timestamp with time zone not null
);

create index idx_speaker_segment_audio_time on speaker_segment(audio_event_id, start_ms);
create index idx_speaker_segment_cluster on speaker_segment(speaker_cluster_id);

create table speaker_embedding (
    id uuid primary key,
    speaker_cluster_id uuid not null references speaker_cluster(id),
    audio_event_id uuid references audio_event(id),
    embedding_ref text not null,
    model_name varchar(128) not null,
    quality_score double precision not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone
);

create table speaker_label_suggestion (
    id uuid primary key,
    speaker_cluster_id uuid not null references speaker_cluster(id),
    suggested_label varchar(255) not null,
    source_type varchar(64) not null,
    evidence_ref text not null,
    confidence double precision not null,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    decided_at timestamp with time zone
);

create table person_label_history (
    id uuid primary key,
    speaker_cluster_id uuid not null references speaker_cluster(id),
    user_id varchar(128) not null,
    action varchar(32) not null,
    old_value jsonb not null default '{}'::jsonb,
    new_value jsonb not null default '{}'::jsonb,
    reason text,
    created_at timestamp with time zone not null
);

create table person_insight (
    id uuid primary key,
    user_id varchar(128) not null,
    speaker_cluster_id uuid not null references speaker_cluster(id),
    insight_type varchar(64) not null,
    time_window_start timestamp with time zone not null,
    time_window_end timestamp with time zone not null,
    summary text not null,
    evidence_refs jsonb not null default '[]'::jsonb,
    confidence double precision not null,
    safety_level varchar(64) not null,
    created_at timestamp with time zone not null
);

create table memory_item (
    id uuid primary key,
    user_id varchar(128) not null,
    source_type varchar(64) not null,
    memory_type varchar(64) not null,
    scope varchar(64) not null,
    subject_type varchar(64),
    subject_id uuid,
    content text not null,
    confidence double precision not null,
    source varchar(64) not null,
    evidence_refs jsonb not null default '[]'::jsonb,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    valid_at timestamp with time zone not null,
    invalid_at timestamp with time zone,
    superseded_by uuid,
    last_used_at timestamp with time zone,
    expires_at timestamp with time zone,
    deleted_at timestamp with time zone
);

create index idx_memory_item_active_user on memory_item(user_id, memory_type)
where invalid_at is null and deleted_at is null;

create index idx_memory_item_subject on memory_item(user_id, subject_type, subject_id)
where invalid_at is null and deleted_at is null;

create table memory_write_candidate (
    id uuid primary key,
    conversation_session_id uuid references conversation_session(id),
    conversation_memory_id uuid references conversation_memory(id),
    source_message_id uuid references agent_message(id),
    source_type varchar(64) not null,
    memory_type varchar(64) not null,
    content text not null,
    confidence double precision not null,
    decision varchar(64) not null,
    decision_reason text,
    created_at timestamp with time zone not null,
    decided_at timestamp with time zone
);

create table memory_recall_event (
    id uuid primary key,
    agent_run_id uuid not null references agent_run(id),
    recall_type varchar(64) not null,
    memory_item_id uuid references memory_item(id),
    conversation_memory_id uuid references conversation_memory(id),
    rank integer not null,
    reason text not null,
    score double precision not null,
    created_at timestamp with time zone not null
);

create table model_job (
    id uuid primary key,
    user_id varchar(128) not null,
    job_type varchar(64) not null,
    source_ref_type varchar(64) not null,
    source_ref_id uuid not null,
    status varchar(32) not null,
    attempts integer not null default 0,
    next_run_at timestamp with time zone not null,
    request_ref text,
    response_ref text,
    last_error_type varchar(128),
    last_error_message text,
    idempotency_key varchar(255) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create unique index ux_model_job_idempotency on model_job(idempotency_key);
create index idx_model_job_pending on model_job(status, next_run_at) where status in ('pending', 'failed');

create table audit_log (
    id uuid primary key,
    user_id varchar(128) not null,
    actor_type varchar(64) not null,
    action varchar(128) not null,
    target_type varchar(64) not null,
    target_id uuid,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamp with time zone not null
);
