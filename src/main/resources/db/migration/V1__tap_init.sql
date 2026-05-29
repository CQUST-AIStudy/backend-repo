-- TAP 模块数据表 (合并自 teacher-assistant-platform 的 V1~V9 迁移)

create table if not exists tap_user (
  id bigint not null auto_increment,
  username varchar(64) not null,
  display_name varchar(128),
  role varchar(16) not null default 'TEACHER',
  password_hash varchar(255) null,
  created_at timestamp(3) not null default current_timestamp(3),
  updated_at timestamp(3) not null default current_timestamp(3),
  primary key (id),
  constraint chk_tap_user_role check (role in ('TEACHER','ADMIN'))
) engine=InnoDB;

create unique index uq_tap_user_username on tap_user(username);
create index idx_tap_user_role on tap_user(role);

create table if not exists paper (
  id bigint not null auto_increment,
  arxiv_id varchar(256) not null,
  title text not null,
  abstract_text text,
  pdf_url text,
  published_at timestamp(3) null,
  updated_at timestamp(3) null,
  created_at timestamp(3) not null default current_timestamp(3),
  primary key (id)
) engine=InnoDB;

create unique index uq_paper_arxiv_id on paper(arxiv_id);

create table if not exists paper_author (
  paper_id bigint not null,
  author_name varchar(256) not null,
  primary key (paper_id, author_name),
  constraint fk_paper_author_paper foreign key (paper_id) references paper(id) on delete cascade
) engine=InnoDB;

create table if not exists paper_category (
  paper_id bigint not null,
  category varchar(64) not null,
  primary key (paper_id, category),
  constraint fk_paper_category_paper foreign key (paper_id) references paper(id) on delete cascade
) engine=InnoDB;

create table if not exists library_item (
  id bigint not null auto_increment,
  user_id bigint not null,
  paper_id bigint not null,
  saved_at timestamp(3) not null default current_timestamp(3),
  downloaded_at timestamp(3) null,
  note text,
  primary key (id),
  constraint fk_library_item_user foreign key (user_id) references tap_user(id) on delete cascade,
  constraint fk_library_item_paper foreign key (paper_id) references paper(id) on delete cascade
) engine=InnoDB;

create unique index uq_library_item_user_paper on library_item(user_id, paper_id);

create table if not exists upload_folder (
  id bigint not null auto_increment,
  user_id bigint not null,
  folder_name varchar(256) not null,
  original_structure_json json null,
  created_at timestamp(3) not null default current_timestamp(3),
  primary key (id),
  constraint fk_upload_folder_user foreign key (user_id) references tap_user(id) on delete cascade
) engine=InnoDB;

create table if not exists document (
  id bigint not null auto_increment,
  user_id bigint not null,
  upload_folder_id bigint not null,
  original_path text not null,
  filename varchar(512) not null,
  content_type varchar(128) not null,
  size_bytes bigint not null,
  sha256 varchar(64) not null,
  language varchar(16),
  extracted_text text,
  extracted_text_key text null,
  extracted_text_truncated boolean not null default false,
  object_key text not null,
  created_at timestamp(3) not null default current_timestamp(3),
  primary key (id),
  constraint fk_document_user foreign key (user_id) references tap_user(id) on delete cascade,
  constraint fk_document_folder foreign key (upload_folder_id) references upload_folder(id) on delete cascade
) engine=InnoDB;

create index idx_document_sha256 on document(sha256);
create index idx_document_upload_folder_id on document(upload_folder_id);
create index idx_document_user_id on document(user_id);

create table if not exists translation_segment (
  id bigint not null auto_increment,
  document_id bigint not null,
  target_lang varchar(16) not null,
  segment_index int not null,
  source_text text not null,
  target_text text not null,
  provider varchar(32) not null,
  created_at timestamp(3) not null default current_timestamp(3),
  primary key (id),
  unique (document_id, target_lang, segment_index),
  constraint fk_translation_segment_doc foreign key (document_id) references document(id) on delete cascade
) engine=InnoDB;

create table if not exists structured_summary (
  id bigint not null auto_increment,
  scope_type varchar(16) not null,
  scope_key varchar(256) not null,
  content_hash varchar(64) not null,
  provider varchar(32) not null,
  model varchar(64) not null default '',
  summary_json json not null,
  markdown text not null,
  created_at timestamp(3) not null default current_timestamp(3),
  updated_at timestamp(3) not null default current_timestamp(3),
  primary key (id),
  unique (scope_type, scope_key, provider, model)
) engine=InnoDB;

create table if not exists audit_event (
  id bigint not null auto_increment,
  user_id bigint null,
  role varchar(16),
  action varchar(64) not null,
  target_type varchar(32),
  target_id varchar(64),
  metadata_json json null,
  ip varchar(64),
  user_agent text,
  trace_id varchar(64),
  created_at timestamp(3) not null default current_timestamp(3),
  primary key (id),
  constraint fk_audit_event_user foreign key (user_id) references tap_user(id) on delete set null
) engine=InnoDB;

create table if not exists user_daily_quota_usage (
  id bigint not null auto_increment,
  user_id bigint not null,
  usage_date date not null,
  translation_chars bigint not null default 0,
  ai_requests bigint not null default 0,
  updated_at timestamp(3) not null default current_timestamp(3),
  primary key (id),
  unique (user_id, usage_date),
  constraint fk_user_daily_quota_usage_user foreign key (user_id) references tap_user(id) on delete cascade
) engine=InnoDB;

create table if not exists agent_job (
  id bigint not null auto_increment,
  user_id bigint not null,
  upload_folder_id bigint not null,
  status varchar(16) not null,
  progress int not null default 0,
  error_message text,
  retry_count int not null default 0,
  started_at timestamp(3) null,
  finished_at timestamp(3) null,
  created_at timestamp(3) not null default current_timestamp(3),
  updated_at timestamp(3) not null default current_timestamp(3),
  version bigint not null default 0,
  primary key (id),
  constraint fk_agent_job_user foreign key (user_id) references tap_user(id) on delete cascade,
  constraint fk_agent_job_folder foreign key (upload_folder_id) references upload_folder(id) on delete cascade,
  constraint chk_agent_job_status check (status in ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
) engine=InnoDB;

create table if not exists agent_result (
  id bigint not null auto_increment,
  job_id bigint not null,
  topic varchar(256),
  tags_json json null,
  summary text,
  translation_link text,
  result_json json null,
  created_at timestamp(3) not null default current_timestamp(3),
  primary key (id),
  constraint fk_agent_result_job foreign key (job_id) references agent_job(id) on delete cascade
) engine=InnoDB;

create unique index uq_agent_result_job_id on agent_result(job_id);
