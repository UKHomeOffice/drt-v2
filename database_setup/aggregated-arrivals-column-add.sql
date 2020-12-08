ALTER TABLE public.arrival ADD COLUMN scheduled_departure timestamp;

CREATE INDEX scheduled_departure ON public.arrival (scheduled_departure ASC);