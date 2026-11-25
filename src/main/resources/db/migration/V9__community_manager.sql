create table community_reply_batch (
    id varchar(36) primary key,
    channel_id varchar(128) not null,
    revision integer not null,
    created_at timestamp with time zone not null
);

create table community_reply_item (
    batch_id varchar(36) not null references community_reply_batch(id) on delete cascade,
    item_no integer not null,
    thread_id varchar(128) not null,
    comment_id varchar(128) not null,
    parent_id varchar(128) not null,
    video_id varchar(32) not null,
    video_title varchar(512) not null,
    author varchar(512) not null,
    original_text text not null,
    response_text text,
    priority varchar(16) not null,
    reason varchar(2000),
    status varchar(24) not null,
    youtube_reply_id varchar(128),
    primary key (batch_id, item_no),
    unique (batch_id, parent_id)
);

create table community_reply_claim (
    channel_id varchar(128) not null,
    parent_id varchar(128) not null,
    batch_id varchar(36) not null references community_reply_batch(id) on delete cascade,
    item_no integer not null,
    primary key (channel_id, parent_id)
);

create index community_reply_batch_channel on community_reply_batch(channel_id, created_at);
