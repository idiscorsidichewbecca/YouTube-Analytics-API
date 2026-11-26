create table advisor_extended_day (
    channel_id varchar(128) not null,
    report_type varchar(48) not null,
    report_day date not null,
    report_id varchar(256) not null,
    report_created timestamp with time zone not null,
    imported_at timestamp with time zone not null,
    verified_at timestamp with time zone not null,
    primary key (channel_id, report_type, report_day)
);

create table advisor_segmented_reach (
    channel_id varchar(128) not null,
    report_type varchar(48) not null,
    report_day date not null,
    video_id varchar(32) not null,
    source_type varchar(48) not null,
    source_detail varchar(2048) not null,
    device varchar(48) not null,
    operating_system varchar(48) not null,
    impressions bigint not null,
    ctr double precision,
    foreign key (channel_id, report_type, report_day)
        references advisor_extended_day(channel_id, report_type, report_day) on delete cascade
);

create index advisor_segmented_reach_video on advisor_segmented_reach(channel_id, video_id, report_day);

create table advisor_subtitles (
    channel_id varchar(128) not null,
    report_type varchar(48) not null,
    report_day date not null,
    video_id varchar(32) not null,
    subtitle_language varchar(48) not null,
    auto_translated varchar(48) not null,
    engaged_views bigint,
    views bigint,
    watch_minutes double precision,
    premium_views bigint,
    premium_watch_minutes double precision,
    foreign key (channel_id, report_type, report_day)
        references advisor_extended_day(channel_id, report_type, report_day) on delete cascade
);

create index advisor_subtitles_video on advisor_subtitles(channel_id, video_id, report_day);

alter table advisor_end_screen_day add column has_element_breakdown boolean not null default false;

create table advisor_end_screen_type (
    channel_id varchar(128) not null,
    report_day date not null,
    video_id varchar(32) not null,
    element_type varchar(48) not null,
    impressions bigint not null,
    clicks bigint not null,
    primary key(channel_id, report_day, video_id, element_type),
    foreign key(channel_id, report_day) references advisor_end_screen_day(channel_id, report_day) on delete cascade
);

create index advisor_end_screen_type_video on advisor_end_screen_type(channel_id, video_id, report_day);
