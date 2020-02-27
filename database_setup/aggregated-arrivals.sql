CREATE TABLE public.arrival(
  code text NOT NULL,
  number integer NOT NULL,
  destination text NOT NULL,
  origin text NOT NULL,
  terminal text NOT NULL,
  gate text,
  stand text,
  status text NOT NULL,
  scheduled timestamp NOT NULL,
  estimated timestamp,
  actual timestamp,
  estimatedChox timestamp,
  actualChox timestamp,
  pcp timestamp NOT NULL,
  totalPassengers integer,
  pcpPassengers integer,
  PRIMARY KEY (number, destination, terminal, scheduled)
);

CREATE INDEX code ON public.arrival (code);
CREATE INDEX number ON public.arrival (number);
CREATE INDEX origin ON public.arrival (origin);
CREATE INDEX terminal ON public.arrival (terminal);
CREATE INDEX scheduled ON public.arrival (scheduled ASC);
CREATE INDEX pcp ON public.arrival (pcp ASC);
