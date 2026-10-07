create table app_users (
    id uuid primary key,
    firebase_project_id text,
    firebase_uid text,
    created_at timestamptz not null default clock_timestamp()
);
insert into app_users(id) select distinct owner_id from wishlist_items;

create table public_category_groups (
    id varchar(16) primary key, name text not null, display_order integer not null unique
);
create table public_categories (
    id varchar(16) primary key, parent_id varchar(16) not null references public_category_groups(id),
    name text not null, display_order integer not null, unique(parent_id,display_order)
);
insert into public_category_groups values ('G001','패션·잡화',0);
insert into public_categories values ('C001','G001','아우터',0);
insert into public_categories values ('C002','G001','상의',1);
insert into public_categories values ('C003','G001','하의',2);
insert into public_categories values ('C004','G001','원피스·스커트',3);
insert into public_categories values ('C005','G001','언더웨어·홈웨어',4);
insert into public_categories values ('C006','G001','신발',5);
insert into public_categories values ('C007','G001','가방',6);
insert into public_categories values ('C008','G001','지갑·카드지갑',7);
insert into public_categories values ('C009','G001','시계',8);
insert into public_categories values ('C010','G001','주얼리',9);
insert into public_categories values ('C011','G001','패션 소품',10);
insert into public_category_groups values ('G002','뷰티·퍼스널케어',1);
insert into public_categories values ('C012','G002','스킨케어',0);
insert into public_categories values ('C013','G002','메이크업',1);
insert into public_categories values ('C014','G002','향수',2);
insert into public_categories values ('C015','G002','헤어케어·스타일링',3);
insert into public_categories values ('C016','G002','바디케어',4);
insert into public_categories values ('C017','G002','네일',5);
insert into public_categories values ('C018','G002','뷰티 디바이스',6);
insert into public_category_groups values ('G003','디지털·IT',2);
insert into public_categories values ('C019','G003','스마트폰',0);
insert into public_categories values ('C020','G003','태블릿',1);
insert into public_categories values ('C021','G003','노트북',2);
insert into public_categories values ('C022','G003','데스크톱·미니 PC',3);
insert into public_categories values ('C023','G003','모니터',4);
insert into public_categories values ('C024','G003','키보드',5);
insert into public_categories values ('C025','G003','마우스·트랙패드',6);
insert into public_categories values ('C026','G003','헤드폰',7);
insert into public_categories values ('C027','G003','이어폰',8);
insert into public_categories values ('C028','G003','스피커',9);
insert into public_categories values ('C029','G003','카메라·액션캠',10);
insert into public_categories values ('C030','G003','웨어러블 기기',11);
insert into public_categories values ('C031','G003','게임 콘솔·휴대용 게임기',12);
insert into public_categories values ('C032','G003','저장장치·네트워크 장비',13);
insert into public_categories values ('C033','G003','스마트홈 기기',14);
insert into public_category_groups values ('G004','가구·인테리어',3);
insert into public_categories values ('C034','G004','의자',0);
insert into public_categories values ('C035','G004','책상·테이블',1);
insert into public_categories values ('C036','G004','소파',2);
insert into public_categories values ('C037','G004','침대·매트리스',3);
insert into public_categories values ('C038','G004','수납 가구',4);
insert into public_categories values ('C039','G004','조명',5);
insert into public_categories values ('C040','G004','러그·커튼',6);
insert into public_categories values ('C041','G004','인테리어 소품',7);
insert into public_category_groups values ('G005','생활·주방·가전',4);
insert into public_categories values ('C042','G005','대형 가전',0);
insert into public_categories values ('C043','G005','주방 가전',1);
insert into public_categories values ('C044','G005','조리 도구',2);
insert into public_categories values ('C045','G005','식기·테이블웨어',3);
insert into public_categories values ('C046','G005','청소 가전·도구',4);
insert into public_categories values ('C047','G005','세탁·의류 관리',5);
insert into public_categories values ('C048','G005','욕실용품',6);
insert into public_categories values ('C049','G005','생활 수납·정리용품',7);
insert into public_categories values ('C050','G005','공기·온습도 관리',8);
insert into public_category_groups values ('G006','스포츠·아웃도어·여행',5);
insert into public_categories values ('C051','G006','홈트레이닝·운동기구',0);
insert into public_categories values ('C052','G006','구기 스포츠 용품',1);
insert into public_categories values ('C053','G006','러닝 용품',2);
insert into public_categories values ('C054','G006','자전거 용품',3);
insert into public_categories values ('C055','G006','등산·클라이밍 용품',4);
insert into public_categories values ('C056','G006','캠핑 용품',5);
insert into public_categories values ('C057','G006','수상 스포츠 용품',6);
insert into public_categories values ('C058','G006','겨울 스포츠 용품',7);
insert into public_categories values ('C059','G006','여행 가방·캐리어',8);
insert into public_categories values ('C060','G006','여행 소품',9);
insert into public_category_groups values ('G007','취미·문화·컬렉터블',6);
insert into public_categories values ('C061','G007','도서',0);
insert into public_categories values ('C062','G007','음반·음악 매체',1);
insert into public_categories values ('C063','G007','악기·음향 취미 용품',2);
insert into public_categories values ('C064','G007','미술·드로잉 용품',3);
insert into public_categories values ('C065','G007','문구',4);
insert into public_categories values ('C066','G007','보드게임·퍼즐',5);
insert into public_categories values ('C067','G007','게임 소프트웨어',6);
insert into public_categories values ('C068','G007','피규어·컬렉터블',7);
insert into public_categories values ('C069','G007','DIY·공예 용품',8);
insert into public_category_groups values ('G008','유아·키즈',7);
insert into public_categories values ('C070','G008','유아 이동·외출 용품',0);
insert into public_categories values ('C071','G008','유아 수유·위생 용품',1);
insert into public_categories values ('C072','G008','유아·아동 의류·신발',2);
insert into public_categories values ('C073','G008','유아·아동 완구',3);
insert into public_categories values ('C074','G008','유아·아동 교육·도서',4);
insert into public_category_groups values ('G009','반려동물',8);
insert into public_categories values ('C075','G009','사료·간식',0);
insert into public_categories values ('C076','G009','생활·배변 용품',1);
insert into public_categories values ('C077','G009','위생·미용 용품',2);
insert into public_categories values ('C078','G009','의류·액세서리',3);
insert into public_categories values ('C079','G009','이동·외출 용품',4);
insert into public_category_groups values ('G010','자동차·모빌리티',9);
insert into public_categories values ('C080','G010','차량 관리·정비 용품',0);
insert into public_categories values ('C081','G010','차량 내외장 액세서리',1);
insert into public_categories values ('C082','G010','차량 전자기기',2);
insert into public_categories values ('C083','G010','라이딩 보호장비·의류',3);
insert into public_category_groups values ('G011','건강·웰빙',10);
insert into public_categories values ('C084','G011','건강기능식품',0);
insert into public_categories values ('C085','G011','건강 측정·관리 기기',1);
insert into public_categories values ('C086','G011','수면·회복 용품',2);
insert into public_categories values ('C087','G011','재활·보호 용품',3);

create function category_examples_valid(values_array text[]) returns boolean language sql immutable as $$
    select cardinality(values_array) <= 5 and not exists (
        select 1 from unnest(values_array) value where value is null or char_length(value) > 60
    )
$$;
create table custom_categories (
    id uuid primary key,
    owner_id uuid not null references app_users(id),
    parent_id varchar(16) not null references public_category_groups(id),
    display_order integer not null default 0 check (display_order >= 0),
    name text not null check (char_length(name) between 1 and 40),
    normalized_name text not null check (char_length(normalized_name) > 0),
    description text check (char_length(description) <= 200),
    examples text[] not null default '{}' check (category_examples_valid(examples)),
    version integer not null default 1 check (version > 0),
    ai_eligible boolean not null default true,
    ai_exclusion_reason varchar(64),
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    deleted_at timestamptz,
    unique(owner_id,id),
    unique(owner_id,parent_id,display_order)
);
create unique index custom_category_name_unique on custom_categories(owner_id,parent_id,normalized_name) where deleted_at is null;
create index custom_category_owner_order on custom_categories(owner_id,created_at,id) where deleted_at is null;

create function protect_custom_category_parent() returns trigger language plpgsql as $$
begin
    if new.parent_id is distinct from old.parent_id or new.owner_id is distinct from old.owner_id then
        raise exception 'custom category parent and owner are immutable' using errcode='23514';
    end if;
    return new;
end
$$;
create trigger custom_category_fixed_parent before update on custom_categories
for each row execute function protect_custom_category_parent();

create table mutation_receipts (
    owner_id uuid not null references app_users(id),
    operation varchar(64) not null,
    idempotency_key uuid not null,
    request_fingerprint char(64) not null,
    category_id uuid not null,
    created_at timestamptz not null default clock_timestamp(),
    primary key(owner_id,operation,idempotency_key),
    foreign key(owner_id,category_id) references custom_categories(owner_id,id)
);
create index mutation_receipt_rate_window on mutation_receipts(owner_id,operation,created_at);

alter table wishlist_items add column custom_category_id uuid;
alter table wishlist_items add constraint wishlist_custom_category_owner_fk
    foreign key(owner_id,custom_category_id) references custom_categories(owner_id,id);
alter table wishlist_items add constraint wishlist_category_exclusive_check
    check (category_id is null or custom_category_id is null);
alter table wishlist_items drop constraint wishlist_items_category_assignment_check;
alter table wishlist_items add constraint wishlist_items_category_assignment_check
    check ((category_id is null and custom_category_id is null) or
        (category_source is not null and category_source in ('AI','USER') and category_missing_reason is null));
create index wishlist_active_custom_category on wishlist_items(owner_id,custom_category_id) where lifecycle_status='ACTIVE';
create index wishlist_active_public_category on wishlist_items(owner_id,category_id) where lifecycle_status='ACTIVE';
