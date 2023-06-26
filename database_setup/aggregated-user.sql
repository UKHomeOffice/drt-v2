CREATE TABLE public.user
(
    id text NOT NULL,
    username text NOT NULL,
    email text NOT NULL,
    latest_login timestamp NOT NULL,
    inactive_email_sent timestamp,
    revoked_access timestamp,
    PRIMARY KEY (id)
);

ALTER TABLE public.user
    ADD COLUMN viewed_feature_content text ;

CREATE INDEX username ON public.user (username);
CREATE INDEX latestLogin ON public.user (latest_login ASC);
CREATE INDEX userEmail ON public.user (email);
