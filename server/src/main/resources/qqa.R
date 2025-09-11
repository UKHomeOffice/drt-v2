# =============================== qqa module =================================
#
# The qqa module contains "Queue Quality Assurance" fuctions, for argument 
# validation and error handling by SDRT.

qqa = new.env()

# Ensure the db arg to new.simulation is in the correct format
# Routes and txtimes are checked later, under inflow and server
qqa$check.db = function(db) {
	if (!is.list(db)) stop("new.simulation: 'db' argument must be a list")
	if (is.null(names(db)) || any(names(db) == ""))
		stop("new.simulation: 'db' list elements must be named (by job type)")
	lapply(db, function(jobdata) {
		if (!is.data.frame(jobdata))
			stop("new.simulation: elements of 'db' must be data frames (describing jobs)")
		if (!("arrival.time" %in% names(jobdata)))
			stop("new.simulation: a data frame in 'db' is missing its 'arrival.time' column")
	})
	return()
}

# Ensure the given inflow has a .route column in the db, and that the column is an R 'factor'
qqa$check.inflow = function(inflow) {
	sim.env = parent.env(inflow)
	jobdata = sim.env$db[[inflow$job.type]]
	route.colname = paste(inflow$inflow.name, "route", sep = ".")
	if (!(route.colname %in% colnames(jobdata)))
		stop("new.simulation: the '", inflow$job.type, "' data frame is missing its '", route.colname, "' column")
	if (!is.factor(jobdata[[route.colname]]))
		stop("new.simulation: the '", route.colname, "' column of '", inflow$job.type, "' needs to be an R factor.\n",
			"  Hint: when creating the data frame, specify 'stringsAsFactors = TRUE'")
}

# Ensure the given 'route.name' character vector matches the names of elements of 'routes'
qqa$check.route.resolved = function(route.colname, route.name, routes) {
	route.resolved = route.name %in% names(routes)
	if (!all(route.resolved)) {
		first.unresolved = route.name[!route.resolved][1]
		stop("The value '", first.unresolved, "' in the '", route.colname, "' column is not the name of a known route.\n",
			"  Valid routes are queues, choosers and outflows.")
	}
}

# Ensure the given 'job.type' is the name of an element of the db in 'sim.env'
qqa$check.job.type = function(sim.env, obj.name, job.type) {
	if (is.null(job.type))
		stop("In '", obj.name, "', the job.type cannot be NULL")
	if (!(job.type %in% names(sim.env$db)))
		stop("In '", obj.name, "', the job.type '", job.type, "' is not the name of a job type in the simulation's db")
}

# Ensure the main.server for the given queue exists
qqa$check.main.server = function(queue) {
	if (is.null(queue$main.server) || is.null(queue$main.server$multiserver.name))
		stop("In queue '", queue$queue.name, "', the main server name '", queue$main.server.name,
			"' is not the name of a multiserver in the simulation")
}

# Chooser checks

qqa$check.queues.exist = function(chooser) {
	sim.env = parent.env(chooser)
	lapply(chooser$queue.names, function(qname) {
		queue = sim.env[[qname]]
		if (is.null(queue) || is.null(queue$queue.name)) 
			stop("In chooser '", chooser$chooser.name, "', the queue '", qname,
				"' is not the name of a queue in the simulation."
			)
	})
}

qqa$check.metric = function(chooser) {
	if (!chooser$metric.name %in% c("qlen", "projected.qtime"))
		stop("In chooser '", chooser$chooser.name, "', the metric '", chooser$metric.name,
			"' is not recognised.\n  Valid metrics are 'qlen' or 'projected.qtime'."
		)
}

# Multiserver checks

qqa$check.capacity = function(multiserver) {
	capacity = multiserver$capacity
	if (!is.finite(capacity) || capacity != floor(capacity) || capacity < 0)
		stop("In multiserver '", multiserver$multiserver.name, "', the given capacity (", capacity,
			") is not a finite positive integer."
		)
	if (capacity > multiserver$max.capacity)
		stop("The capacity given for multiserver '", multiserver$multiserver.name, 
			"' is greater than the max.capacity."
		)
}

qqa$check.fixed.points = function(multiserver) {
	fixed.points = multiserver$fixed.points
	if (!is.finite(fixed.points) || fixed.points != floor(fixed.points) || fixed.points < 0)
		stop("In multiserver '", multiserver$multiserver.name, "', the given fixed.points (",
			fixed.points, ") is not a finite positive integer."
		)
	if (fixed.points > multiserver$max.capacity)
		stop("The fixed.points given for multiserver '", multiserver$multiserver.name,
			"' is greater than the max.capacity."
		)
}

# For the given multiserver, check that the .route and .txtime columns for its server.type
# exist in the db, and that the .route resolves to actual routes.
qqa$check.server.type = function(multiserver) {
	sim.env = parent.env(multiserver)
	multiserver.name = multiserver$multiserver.name
	job.type = multiserver$job.type
	jobdata = sim.env$db[[job.type]]
	
	server.type = multiserver$server.type
	if (!is.character(server.type))
		stop("The server.type of multiserver '", multiserver.name, "' is not a character string")
	
	route.colname = paste(server.type, "route", sep = ".")
	if (!(route.colname %in% colnames(jobdata)))
		stop("The multiserver '", multiserver.name, "', requires there to be a column named '",
			route.colname, "' in the '", job.type, "' data frame originally passed to new.simulation."
		)	
	route = jobdata[[route.colname]]
	if (!is.factor(route))
		stop("The '", route.colname, "' column of the '", job.type, 
			"' data frame originally passed to new.simulation, needs to be an R factor.\n",
			"  Hint: when creating the data frame, specify 'stringsAsFactors = TRUE'")
	route.name = levels(route)[route]
	qqa$check.route.resolved(route.colname, route.name, sim.env$routes)
	
	txtime.colname = paste(server.type, "txtime", sep = ".")
	if (!(txtime.colname %in% colnames(jobdata)))
		stop("The multiserver '", multiserver.name, "', requires there to be a column named '",
			txtime.colname, "' in the '", job.type, "' data frame originally passed to new.simulation."
		)
}

# Check that the queues specified as served by the given multiserver all exist
qqa$check.queues.served = function(multiserver) {
	sim.env = parent.env(multiserver)
	multiserver.name = multiserver$multiserver.name
	main.queue.name = multiserver$main.queue.name
	queue.names.served = multiserver$queue.names.served
	if (length(queue.names.served) < 1)
		stop("The multiserver '", multiserver.name, "', must be specified to serve at least one queue")
	if (!(main.queue.name %in% queue.names.served))
		stop("The main.queue.name '", main.queue.name, "' of multiserver '", multiserver.name,
			"' is not one of its queue.names.served"
		)
	if (!all(queue.names.served %in% names(sim.env$all.queues())))
		stop("The queue.names.served given for '", multiserver.name, "' are not all the names of queues in the simulation")		
}

# For a given resource, check that the multiservers in 'used.by' exist and that
# any server.type in the conversion functions appear in those multiservers.
# Also check alpha is in (0, 1]
qqa$check.resource = function(resource) {
	sim.env = parent.env(resource)
	if (!all(resource$used.by %in% names(sim.env$multiservers)))
		stop("In resource '", resource$resource.name, "', not all the 'used.by' are names of multiservers in the simulation")
	if (length(resource$resource2capacity) != 0 || length(resource$capacity2resource != 0)) {
		server.types = character(0)
		for (ms in sim.env$multiservers[resource$used.by])
			server.types = union(server.types, ms$server.type)
		if (!is.list(resource$resource2capacity) || is.null(names(resource$resource2capacity)))
			stop("In resource '", resource$resource.name, "', resource2capacity must be a named list of functions")
		if (!is.list(resource$capacity2resource) || is.null(names(resource$capacity2resource)))
			stop("In resource '", resource$resource.name, "', capacity2resource must be a named list of functions")
		if (!(all(names(resource$resource2capacity) %in% server.types)))
			stop("In resource '", resource$resource.name, "', the names of the resource2capacity list don't all match the server.type\n",
				"  of a multiserver in the 'used.by' of this resource."
			)
		if (!(all(names(resource$capacity2resource) %in% server.types)))
			stop("In resource '", resource$resource.name, "', the names of the capacity2resource list don't all match the server.type\n",
				"  of a multiserver in the 'used.by' of this resource."
			)
	}
	if (resource$alpha <= 0 || resource$alpha > 1)
		stop("In resource '", resource$resource.name, "', the exponential smoothing constant alpha must be between 0 (exc) and 1 (inc).")
}

# If schedule.row is NA, raise an error
qqa$check.schedule.row = function(schedule.row, ticks) {
	if (is.na(schedule.row))
		stop("allocate.scheduled: the clock ticks (", ticks, ") do not match any of the times in the schedule")
}

# Raise an error if sim.env already contains a scheduler with the given name
qqa$check.duplicate.scheduler = function(sim.env, scheduler.name) {
	if (!is.null(sim.env[[scheduler.name]]))
		stop("make.scheduler: There is already a scheduler called '", scheduler.name, "'")
}

# Check that the resource specified for a scheduler exists and that the quantities
# and times are non-empty vectors of equal length
qqa$check.scheduler = function(scheduler) {
	sim.env = parent.env(scheduler)
	if (is.null(sim.env[[scheduler$for.resource.name]]$resource.name))
		stop("In the scheduler for resource '", scheduler$for.resource.name, "', there is no resource of that name")
	if (length(scheduler$times) < 1)
		stop("In the scheduler for '", scheduler$for.resource.name, "', at least one time (in ticks) must be provided in the 'times' argument")
	if (length(scheduler$times) != length(scheduler$quantities))
		stop("In the scheduler for '", scheduler$for.resource.name, "', the length of the 'quantities' vector does not equal the length of the 'times' vector")
}

qqa$check.capacity.schedule = function(marshal) {

	qqa$check.schedule.exists(marshal)
	schedule = marshal$capacity.schedule

	# Check that it's a numeric data frame with a 'times' column and no negative 
	# or missing values. Also check capacities are integers.
	if (!identical(colnames(schedule)[1], "times"))
		stop("Capacity schedule must be a data frame with 'times' as its first column")
	if (!all(sapply(schedule, is.numeric)))
		stop("Capacity schedule must be entirely numeric")
	if (!all(sapply(schedule, function(x) all(is.finite(x)))))
		stop("Capacity schedule must not contain Inf or NA")
	if (!all(schedule >= 0))
		stop("Capacity schedule contains negative value(s)")
	if (!all(schedule[-1] == round(schedule[-1])))
		stop("Capacity schedule contains non-integer capacitie(s)")

	# Check times are in ascending order
	times = schedule$times
	if (!all(times == sort(times)))
		stop("Times in the capacity schedule must be in ascending order")
	
	# Check all columns except 'times' are names of multiservers
	ms.names = colnames(schedule)[-1]	# should all be names of multiservers
	sim = evalq(this.sim, marshal)		# the sim in which marshal resides
	unknown.names = setdiff(ms.names, names(sim$multiservers))
	if (length(unknown.names) > 0)
		stop("The column(s) '", paste(unknown.names, collapse = ", "),
			"' in the capacity schedule do not have the names of multiservers")
	
	# Check all multiservers that use resources are included
	for (res.name in names(sim$resources)) {
		used.by = sim[[res.name]]$used.by
		unspecified.names = setdiff(used.by, names(schedule))
		if (length(unspecified.names > 0))
			stop("Multiserver(s) '", paste(unspecified.names, collapse = ", "), "' use resource '", 
				res.name, "' but do not have a column in the capacity schedule")
	}
	
	# Check all multiservers named in the schedule use at least one resource
	res.users = character(0)	# to hold the names of all multiservers that use resources
	for (res in sim$resources)
		res.users = union(res.users, res$used.by)
	unresourced.names = setdiff(ms.names, res.users)
	if (length(unresourced.names) > 0)
		stop("The multiserver(s) '", paste(unresourced.names, collapse = ", "),
			"' in the capacity schedule need to use at least one resource")
}

# Ensure that a capacity schedule is present on the given marshal
qqa$check.schedule.exists = function(marshal) {
	if (is.null(marshal$capacity.schedule))
		stop("A capacity.schedule must exist in the marshal before a resource schedule can be calculated")
}

# Raise an error when a zero SLA cannot be processed
qqa$error.zero.sla = function(queue) {
	stop("SLA-based allocation cannot be used with queue '", queue$queue.name, "' because its SLA is zero.\n",
		"  Try using a small positive value instead of zero.")
}

qqa$check.allocation.method = function(method, interval) {
	if (!(method %in% c("scheduled", "sla", "wait")))
		stop("set.allocation.method: method must be one of \"scheduled\", \"sla\" or \"wait\"")
	if (method == "scheduled" && interval != 0)
		stop("set.allocation.method: when method is \"scheduled\", interval must be zero")
	if (method != "scheduled" && interval <= 0)
		stop("set.allocation.method: interval must be greater than zero for selected method")
}

qqa$check.resource.exists = function(sim, resource.name) {
	if (is.null(sim[[resource.name]]$resource.name))
		stop("The resource '", resource.name, "' does not exist in this simulation")
}

qqa$check.route.exists = function(sim, route.name) {
	if (!(route.name %in% names(sim$routes)))
		stop("The route '", route.name, "' is not a route in this simulation")
}

qqa$check.not.scheduled = function(resource) {
	if (resource$allocator.name == "scheduled")
		stop("search.deployment: the '", resource$resource.name, "' resource is using \"scheduled\" allocation.\n",
			"  Use the marshal$set.allocation.method function of this sim to set \"sla\" or \"wait\" instead."
		)
}

# Check that there is at least one resource in the sim (for fitting capacities)
qqa$check.have.resource = function(sim) {
	if (length(sim$resources) == 0)
		stop("This simulation contains no resources, so no variable capacities can be fitted")
}

# Check that ignorable queues for search.deployment exist
qqa$check.ignorable.queues = function(sim, ignorable.qnames) {
	lapply(ignorable.qnames, function(qname) {
		queue = sim[[qname]]
		if (is.null(queue) || is.null(queue$queue.name)) 
			stop("In search.deployment, the queue '", qname,
				"' to ignore is not the name of a queue in the simulation."
			)
	})
}

