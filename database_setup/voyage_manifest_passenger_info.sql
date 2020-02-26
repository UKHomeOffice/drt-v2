CREATE TABLE public.voyage_manifest_passenger_info (
    event_code character varying(2),
    arrival_port_code character varying(5),
    departure_port_code character varying(5),
    voyage_number integer,
    carrier_code character varying(5),
    scheduled_date timestamp without time zone,
    day_of_week smallint,
    week_of_year smallint,
    document_type character varying(50),
    document_issuing_country_code character varying(5),
    eea_flag character varying(5),
    age smallint,
    disembarkation_port_code character varying(5),
    in_transit_flag character varying(2),
    disembarkation_port_country_code character varying(5),
    nationality_country_code character varying(5),
    passenger_identifier character varying(25),
    in_transit boolean
);

CREATE INDEX arrival_dow_woy ON public.voyage_manifest_passenger_info (event_code, arrival_port_code, departure_port_code, voyage_number, week_of_year, day_of_week);
CREATE INDEX arrival_woy ON public.voyage_manifest_passenger_info (event_code, arrival_port_code, departure_port_code, voyage_number, week_of_year);
CREATE INDEX route_woy_dow ON public.voyage_manifest_passenger_info (event_code, arrival_port_code, departure_port_code, week_of_year, day_of_week);
CREATE INDEX egate_eligibility ON public.voyage_manifest_passenger_info (event_code, arrival_port_code, document_type, nationality_country_code, age);
CREATE INDEX egate_eligibility_with_scheduled ON public.voyage_manifest_passenger_info (event_code, arrival_port_code, document_type, nationality_country_code, age);
CREATE INDEX unique_arrival ON public.voyage_manifest_passenger_info (arrival_port_code, departure_port_code, scheduled_date, voyage_number);
