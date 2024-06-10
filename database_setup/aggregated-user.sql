CREATE TABLE public.user
(
    id                              text      NOT NULL,
    username                        text      NOT NULL,
    email                           text      NOT NULL,
    latest_login                    timestamp NOT NULL,
    inactive_email_sent             timestamp,
    revoked_access                  timestamp,
    drop_in_notification_at         timestamp,
    created_at                      timestamp,
    feedback_banner_closed_at       timestamp,
    staff_planning_interval_minutes integer
    PRIMARY KEY (id)
);

CREATE INDEX username ON public.user (username);
CREATE INDEX latestLogin ON public.user (latest_login ASC);
CREATE INDEX userEmail ON public.user (email);
