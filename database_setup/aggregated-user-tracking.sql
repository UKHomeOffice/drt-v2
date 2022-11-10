CREATE TABLE public.user_tracking
(
    username text NOT NULL,
    id text NOT NULL,
    email text NOT NULL,
    latest_login timestamp NOT NULL,
    inactive_email_sent timestamp,
    revoke_access timestamp,
    PRIMARY KEY (id)
);

CREATE INDEX id ON public.user_tracking (id);
CREATE INDEX latestLogin ON public.user_tracking (latest_login ASC);
CREATE INDEX userTrackingEmail ON public.user_tracking (email);