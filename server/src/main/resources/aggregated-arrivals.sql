CREATE TABLE general.arrival(
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

CREATE INDEX code ON general.arrival (code);
CREATE INDEX number ON general.arrival (number);
CREATE INDEX origin ON general.arrival (origin);
CREATE INDEX terminal ON general.arrival (terminal);
CREATE INDEX scheduled ON general.arrival (scheduled ASC);
CREATE INDEX pcp ON general.arrival (pcp ASC);