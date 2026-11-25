create table advisor_reach_day (
    channel_id varchar(128) not null,
    report_day date not null,
    report_id varchar(256) not null,
    report_created timestamp with time zone not null,
    imported_at timestamp with time zone not null,
    verified_at timestamp with time zone not null,
    primary key (channel_id, report_day)
);

create table advisor_video_reach (
    channel_id varchar(128) not null,
    report_day date not null,
    video_id varchar(32) not null,
    impressions bigint not null,
    ctr double precision,
    primary key (channel_id, report_day, video_id),
    foreign key (channel_id, report_day) references advisor_reach_day(channel_id, report_day) on delete cascade
);

create index advisor_video_reach_lookup on advisor_video_reach(channel_id, video_id, report_day);

create table advisor_optimization (
    id varchar(36) primary key,
    channel_id varchar(128) not null,
    video_id varchar(32) not null,
    action varchar(24) not null,
    state varchar(24) not null,
    revision integer not null,
    rationale varchar(2000) not null,
    original_value text,
    applied_value text,
    created_at timestamp with time zone not null,
    applied_at timestamp with time zone,
    before_metrics text,
    draft_options text not null
);

create index advisor_optimization_video on advisor_optimization(channel_id, video_id, created_at);

create table advisor_optimization_image (
    optimization_id varchar(36) not null references advisor_optimization(id) on delete cascade,
    choice varchar(1) not null,
    image_base64 text not null,
    primary key (optimization_id, choice)
);

create table advisor_optimization_claim (
    channel_id varchar(128) not null,
    video_id varchar(32) not null,
    action varchar(24) not null,
    optimization_id varchar(36) not null references advisor_optimization(id) on delete cascade,
    primary key(channel_id, video_id)
);

create table advisor_optimization_evaluation (
    optimization_id varchar(36) not null references advisor_optimization(id) on delete cascade,
    window_days integer not null,
    observed_at timestamp with time zone not null,
    payload text not null,
    primary key (optimization_id, window_days)
);

create table advisor_end_screen_day (
    channel_id varchar(128) not null,
    report_day date not null,
    report_id varchar(256) not null,
    report_created timestamp with time zone not null,
    imported_at timestamp with time zone not null,
    verified_at timestamp with time zone not null,
    primary key(channel_id,report_day)
);

create table advisor_end_screen_metric (
    channel_id varchar(128) not null,
    report_day date not null,
    video_id varchar(32) not null,
    impressions bigint not null,
    clicks bigint not null,
    primary key(channel_id, report_day, video_id),
    foreign key(channel_id, report_day) references advisor_end_screen_day(channel_id, report_day) on delete cascade
);

create index advisor_end_screen_lookup on advisor_end_screen_metric(channel_id, video_id, report_day);
