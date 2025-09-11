# =============================== qsim module =================================
#
# Principal queue simulation objects for SDRT v3

# Alan Hall, Borders Analysis, HOAI

# =============================================================================
# 'new.simulation' creates (but doesn't yet run) a simulation.
# =============================================================================

# The 'db' argument is a named list, whose elements are data frames and whose 
# names are text descriptions of job types, e.g. "customer". You can have more 
# than one job type per simulation.
#
# Within each data frame, the rows specify individual jobs. The columns, at a
# minimum, must include:
#
#	-	a column called 'arrival.time', giving the time in ticks at which the job
#   	arrives.
#	-	a column whose name is suffixed with '.route' for the inflow of that job
#		type, e.g. 'customer.inflow.route'. The cells in this column specify
#		where to send the job on arrival, e.g. 'customer.queue'.
#	-	a column whose name is suffixed with '.route' for every server.type that
#		can serve that job type, e.g. 'service.desk.route'. This column specifies
#		the next destination of the job after it has been processed by a multiserver
#		of that type.
#   -	a column whose name is suffixed with '.txtime' for every server.type that
# 		can serve that job type, giving the transaction time in ticks.
#
# Optionally, there can be further columns describing attributes of individual jobs.
#
# The simulation will automatically contain a marshal, a clock, and an inflow and 
# outflow for each job.type. For example, if you have a job.type called 'customer',
# then 'customer.inflow' and 'customer.outflow' will be created for you, so do not
# create these yourself.
#
# RETURN VALUE
# An R environment representing the simulation. Typically the user will pass this
# environment to make.queue, make.multiserver, etc. to set up simulation objects 
# before running it.

new.simulation = function(db) {
	qqa$check.db(db)			# check user provided db in the correct format
	sim.env = new.env()
	sim.env$this.sim = sim.env
	sim.env$db = db
	sim.env$run.status = "not.yet.run"
	sim.env$allocation.pending = -1		# At what tick are we waiting for resource(s)
										# to be polled? A value of -1 means none pending.
										
	# list of 'routes' known to this simulation; objects that can behave as routes
	# (queues, choosers) must add themselves to this list on creation.
	sim.env$routes = list()		
	
	# list of 'resources' known to this simulation; each individual resource on 
	# creation must append itself to this list.
	sim.env$resources = list() 	

	# list of 'multiservers' known to this simulation; new multiservers must add
	# themselves to this list on creation.
	sim.env$multiservers = list()	
	
	sim.env$next.legend = list()	# details of next legend to be added to a plot
	make.marshal(sim.env)			# every simulation has one 'marshal' object
	make.clock(sim.env)				# every simulation has one clock object

	# Make an inflow and an outflow for each job type in db.
	# Add a job.size column, if not already present.
	for (job.type in names(db)) {
		qqa$check.inflow(
			make.inflow(sim.env, paste(job.type, "inflow", sep = "."), job.type)
		)
		make.outflow(sim.env, paste(job.type, "outflow", sep = "."), job.type)
		if (!("job.size" %in% colnames(db[[job.type]])))
			sim.env$db[[job.type]]$job.size = 1
	}

	# -----------------------------------------------------------------------------
	# 'run' runs the simulation. To be called after all queues, servers, etc. have 
	# been created. A simulation can only be run once.
	
	evalq(run <- function() {
		if (run.status != "not.yet.run")
			stop("Simulation can only be run once")
		else run.status <<- "run.commenced"
	
		# Call the 'wakeup' function on all simulation objects that have one
		lapply(this.sim, function(obj) {
			if (typeof(obj) == "environment") {
				wakeup.call = obj$wakeup
				if (typeof(wakeup.call) == "closure")
					wakeup.call()	
			}
		})
		
		# Start the clock. In the first instance, this will run poll events raised 
		# at wakeup. These can then raise follow-up events.
		clock$run()
		
		# When clock runs down, call the 'shutdown' function on all simulation 
		# objects that have one.
		lapply(this.sim, function(obj) {
			if (typeof(obj) == "environment") {
				shutdown.call = obj$shutdown
				if (typeof(shutdown.call) == "closure")
					shutdown.call()
			}
		})
		
		run.status <<- "run.complete"
	}, sim.env)

	# -----------------------------------------------------------------------------
	# Utility functions for use within a simulation
	
	# zdivide(x, y) returns x / y UNLESS both x and y are zero; in that case, it 
	# returns zero (not NaN as per regular division)
	evalq(zdivide <- function(x, y) {
		ifelse(x == 0 & y == 0, 0, x / y)
	}, sim.env)

	# list.union(list1, list2) forms the union of two named lists. Where an identically 
	# named element appears in both, only the first is retained.
	evalq(list.union <- function(primary, secondary) {
		c(
			primary, 
			secondary[setdiff(names(secondary), names(primary))]
		)
	}, sim.env)
	
	# all.queues() returns a list of all queues in this simulation
	evalq(all.queues <- function() {
		result = list()
		for (route in routes)
			if (!is.null(route$queue.name)) result[[route$queue.name]] = route
		return(result)
	}, sim.env)
	
	# qpopulation() returns the total size of all queues in this simulation
	# If called at the end of the simulation, it can be used to check whether
	# any jobs were still in a queue at the end.
	evalq(qpopulation <- function() {
		sum(sapply(all.queues(), function(queue) queue$qlen()))
	}, sim.env)
	
	# add.legend(...) adds a legend to an existing plot. Any optional args are passed 
	# to the R 'legend' function, overriding defaults where applicable.
	evalq(add.legend <- function(...) {
		legend.args = list.union(list(...), next.legend)
		do.call(legend, legend.args)
	}, sim.env)
	
	return(sim.env)	
}	# end new.simulation

# =============================================================================
# 'make.inflow' creates an 'inflow' object, which handles newly arriving jobs.
# =============================================================================

# Do not call this function directly; it is called automatically when the simulation
# is created, once for each job type in 'db'.

# ARGUMENTS
# sim.env:		a simulation environment, as created by new.simulation
# inflow.name:	a textual name for this inflow
# job.type:		the type of job produced by this inflow; must be the name of a 
#               data frame in sim.env$db.

make.inflow = function(sim.env, inflow.name, job.type) {
	env = new.env(parent = sim.env)
	sim.env[[inflow.name]] = env
	env$this.inflow = env
	env$inflow.name = inflow.name
	env$job.type = job.type
	env$job.index = 1
	
	# to be resolved on wakeup:
	env$arrival.time = "placeholder"	# the job type's vector of arrival times
	env$route.index = "placeholder"     # the job type's vector of next destinations
	
	# -----------------------------------------------------------------------------
	# Polling an inflow identifies jobs that are due to arrive at the current tick, 
	# and sends them down the specified route. 'receive.jobs' will be called on the 
	# object identified in the route, which is usually a queue.
	
	evalq(poll.me <- function() {
		ticks = clock$ticks
		if (allocation.pending == ticks) {
			# re-raise the poll event to be handled once pending allocation complete
			clock$poll.after(this.inflow, 0)
			return()
		}
		old.job.index = job.index
		while (
			(jobs.exist = (job.index <= length(arrival.time))) &&
		    (jobs.due = (arrival.time[job.index] <= ticks))
		){
			job.index <<- job.index + 1
		}
		if (job.index > old.job.index) {
			for (job in old.job.index:(job.index - 1))
				routes[[route.index[job]]]$receive.jobs(job)
		}
		if (jobs.exist) clock$poll.at(this.inflow, arrival.time[job.index])	
	}, env)
	
	# -----------------------------------------------------------------------------	
	# Waking up an inflow resolves its arrival.time and route.index properties. It
	# then raises an initial poll event that will cause jobs to start arriving.
	
	evalq(wakeup <- function() {
		job.data = db[[job.type]]
		arrival.time <<- job.data$arrival.time
		route.colname = paste(inflow.name, "route", sep = ".")
		route = job.data[[route.colname]]
		route.name = levels(route)[route]
		route.index <<- match(route.name, names(routes))      
			# vector of indices into 'routes' list, for where jobs are sent upon arrival
		qqa$check.route.resolved(route.colname, route.name, routes)
		clock$poll.at(this.inflow, 0) 
	}, env)
	
	return(env)	
}	# end make.inflow

# =============================================================================
# 'make.queue' creates a 'queue' object, that can hold jobs on a first-in-first
# -out basis. 
# =============================================================================
#
# The queue maintains a data frame, 'qdata', holding the times that jobs joined 
# and left the queue. Jobs remain in the queue until a server calls its yield.jobs
# function. When a job joins a previously empty queue, it raises poll events on 
# associated servers to alert them that work has arrived.
#
# ARGUMENTS
# sim.env:			a simulation environment, as created by new.simulation
# queue.name:		a textual name for this queue
# main.server.name:	the multiserver primarily intended for this queue. This is the
#					multiserver that will be used for estimating current queue 
#					times. Other multiservers may also serve it, usually if not 
#					busy with their own primary jobs.
# job.type:			the type of job that can be held by this queue; a queue can 
#					only hold jobs of a single type. Must be the name of a data 
#					frame in sim.env$db.
# sla:				"service level agreement" - the notional maximum acceptable
#					waiting	time (in ticks) in this queue. It is consulted by the 
#					capacity optimising functions, and by allocate.sla
# project.maximal	if TRUE, base the projected.qtime for this queue on the 
#					max.capacity, as opposed to the target capacity, of its main
#					server. This will reduce balking from a multiserver that is not
#					currently open to its full capacity, to suppress feedback loops.
 
make.queue = function(
	sim.env, queue.name, main.server.name, job.type, sla = 10, project.maximal = FALSE
) {
	# Fields initialised as "placeholder" are to be resolved on wakeup
	env = new.env(parent = sim.env)
	sim.env[[queue.name]] = env
	env$this.queue = env
	sim.env$routes[[queue.name]] = env		# add this queue to the list of available 'routes'
	env$queue.name = queue.name
	env$main.server.name = main.server.name
	env$main.server = "placeholder"			# the multiserver object corresponding to main.server.name
	env$job.type = job.type
	qqa$check.job.type(sim.env, queue.name, job.type)
	env$sla = sla
	env$project.maximal = project.maximal
	env$qdata = "placeholder"				# data frame to record passage of jobs
	env$job.size = "placeholder"			# vector of job.size for the given job.type
	env$qtail = 1       					# row index in qdata where next job will join
	env$qhead = 1       					# row index of job (if any) at head of queue
											# 	- if qhead == qtail, this denotes an empty queue
	env$served.by = list()					# multiservers for this queue add themselves to the list on creation
	env$ticks.when.empty = numeric(0)		# times when queue was asked to yield jobs, but was empty

	# ---- preferred.by -----------------------------------------------------------
	# For which (currently existing) choosers is this queue the first choice, i.e. 
	# the first listed in that chooser's queue.names? Returns a named list of choosers.
	
	evalq(preferred.by <- function() {
		result = list()
		for (route in routes) {
			chooser.name = route$chooser.name
			if (
				!is.null(chooser.name ) &&				# the route is a chooser 
				route$queue.names[1] == queue.name		# its first choice queue is this queue
			) result[[chooser.name]] = route			# add chooser to result list
		}
		return(result)
	}, env)

	# ---- receive.jobs -----------------------------------------------------------
	# Receive zero or more jobs into the queue. This function is to be called by the
	# object upstream in the simulation (e.g. an inflow) when it wants to route a job
	# to this queue.
	
	evalq(receive.jobs <- function(job.indices) {
		number.to.receive = length(job.indices)
		if (number.to.receive > 0) {
			if (qhead == qtail)  		              # joining a previously empty queue, so...
				lapply(served.by, clock$poll.after)	  # ...poll relevant servers
			qtail.seq = seq(qtail, length = number.to.receive)
			qdata$job[qtail.seq] <<- job.indices
			qdata$joined[qtail.seq] <<- clock$ticks
			qtail <<- qtail + number.to.receive
		}
	}, env)

	# ---- yield.jobs -------------------------------------------------------------
	# Remove up to how.many jobs from the head of the queue and return their job.index 
	# values, or an empty vector if queue is empty. To be called by a multiserver 
	# when ready to receive jobs.
	
	evalq(yield.jobs <- function(how.many) {
		number.to.yield = min(how.many, qtail - qhead)
		if (number.to.yield > 0) {
			job.seq = seq(qhead, length = number.to.yield)
			job.indices = qdata$job[job.seq]
			qdata$left[job.seq] <<- clock$ticks
			qhead <<- qhead + number.to.yield
		} else {
			ticks.when.empty <<- c(ticks.when.empty, clock$ticks)
			job.indices = numeric(0)
		}
		return(job.indices)
	}, env)

	# ---- qlen -------------------------------------------------------------------	
	# Return the total job.size of jobs in the queue at the current tick of the simulation
	
	evalq(qlen <- function() {
		if (qhead == qtail) return(0)
		jobs.in.queue = qdata$job[qhead:(qtail - 1)]	# *indices* of the queued jobs
		return(
			sum(job.size[jobs.in.queue])				# sum the sizes of the jobs with those indices
		)
	}, env)

	# ---- projected.qtime --------------------------------------------------------	
	# Estimate the projected wait time if a job joins the queue at the current tick 
	# of the simulation. If the queue's project.maximal flag is TRUE, the estimate 
	# is based on the main server's max.capacity. Otherwise, it is based on the main
	# server's target.capacity -- so any imminent reductions are anticipated.
	
	# This is an approximate projection using the assumptions:
	# 		- all jobs currently in the queue will be processed on main.server
	#		- the wait time is equal to the processing time of those jobs, at 
	#		  main.server's given capacity; jobs currently on the server are ignored.
	# 		- if the relevant capacity is zero, the return value is always Inf.
	
	evalq(projected.qtime <- function() {
		projected.capacity = 
			if (project.maximal) main.server$max.capacity 
			else main.server$target.capacity
		if (projected.capacity == 0) return(Inf)
		if (qhead == qtail) return(0)            # empty queue, finite server capacity
		jobs.in.queue = qdata$job[qhead:(qtail - 1)]
		work.in.queue = sum(main.server$residual.time[jobs.in.queue])
		return(work.in.queue / projected.capacity)
	}, env)
	
	# ---- calculate.stats --------------------------------------------------------
	# To be called by 'shutdown' once the simulation has finished. Record the qlen 
	# and qtime in qdata for all jobs, where qlen is expressed in terms of the total 
	# job.size of jobs waiting in the queue. Unused rows are trimmed from qdata.
	
	evalq(calculate.stats <- function() {
		
		# Trim unused rows from qdata, and add a job.size column
		qdata <<- subset(qdata, !is.na(job))
		qdata$job.size <<- job.size[qdata$job]
		
		# Calculate the pre-existing qlen at the time when each job joined
		qindices = seq(length = nrow(qdata))				
			# simple ascending index (1, 2, 3,... ) for jobs seen in this queue
		left.so.far = qdata$left[!is.na(qdata$left)]    
			# times at which jobs left the queue, excluding any jobs that never left
		next.leavers = findInterval(qdata$joined, left.so.far) + 1
			# indices of next job to leave the queue strictly later than the current job joined
		qlen.in.jobs = pmax(0, qindices - next.leavers)		
			# the number of jobs already in the queue when the current job joined
		size.so.far = c(0, cumsum(qdata$job.size))			
			# cumulative size of all jobs that joined *before* the current job
		qdata$qlen <<- size.so.far[qindices] - size.so.far[qindices - qlen.in.jobs]
			# queue length, expressed as total size (rather than count) of jobs 
			# in queue when current job joined
		
		# Calculate waiting times
		qdata$qtime <<- qdata$left - qdata$joined         	# time each job waited in the queue
		qdata$qtime[is.na(qdata$qtime)] <<- Inf			  	# regard non-leavers as having infinite queue time
	}, env)

	# ---- qmax -------------------------------------------------------------------
	# Return the maximum value of qtime (can be Inf). If no jobs used the queue, 
	# returns zero. Note calculate.stats must have been called first.
	# If ignore.inf is set to TRUE, jobs with infite qtime are ignored; if they
	# were *all* infinite, the return value (as before) is zero.
	
	evalq(qmax <- function(ignore.inf = FALSE) {
		qtime = qdata$qtime
		if (ignore.inf) 
			qtime = qtime[is.finite(qtime)]
		if (length(qtime) == 0) return(0)
		else return(max(qtime))
	}, env)

	# ---- qmean ------------------------------------------------------------------
	# Return the mean value of qtime (can be Inf), weighted by job.size. If no jobs
	# used the queue or their total size was zero (after ignoring any Inf qtimes, if 
	# so directed), the return value is zero. Note calculate.stats must have been 
	# called first.
	
	evalq(qmean <- function(ignore.inf = FALSE) {
		working.qdata = qdata		# working copy for possible subset
		if (ignore.inf)
			working.qdata = working.qdata[is.finite(working.qdata$qtime), ]
		if (sum(working.qdata$job.size) == 0)
			return(0)
		else return(
			weighted.mean(working.qdata$qtime, working.qdata$job.size)
		)
	}, env)

	# ---- qquantiles -------------------------------------------------------------
	# Return the given quantiles of qtime, weighted by job.size. If no jobs used the
	# queue or their total size was zero (after ignoring any Inf qtimes, if so directed),
	# the returned quantiles are all zero. Note calculate.stats must have been called
	# first. R's 'quantile' function is used in the underlying calculation.
	
	evalq(qquantiles <- function(
		quants = c(0, 0.25, 0.5, 0.75, 0.85, 0.95, 0.99, 1), 
		ignore.inf = FALSE
	) {
		expanded.qtimes = with(qdata, rep(qtime, job.size))
		if (ignore.inf)
			expanded.qtimes = expanded.qtimes[is.finite(expanded.qtimes)]
		if (length(expanded.qtimes) == 0)
			expanded.qtimes = 0		# dummy zero for zero total job size
		return(quantile(expanded.qtimes, quants))	
	}, env)

	# ---- qextent ----------------------------------------------------------------
	# Return the longest qlen observed on this queue. The qlen is expressed in terms
	# of total job.size (as opposed to job count), just as it is in qdata. If the
	# queue was unused then the return value is zero.
	
	evalq(qextent <- function() {
		if (nrow(qdata) == 0) return(0)
		else return(max(qdata$qlen))
	}, env)
	
	# -----------------------------------------------------------------------------
	# On wakeup, resolve the main.server and job.size fields, and intitialise empty qdata
	#
	# The buffer for qdata is pre-allocated to accommodate, if need be, every job of
	# the relevant job.type. This improves efficiency by reducing heap allocations.
	# Any unused rows are trimmed on shutdown. If the simulation allows jobs to be
	# recycled, and the buffer turns out to be too small, R will silently extend it as
	# new rows are assigned to. This will give the correct behaviour but is inefficient.
	
	evalq(wakeup <- function() {
		main.server <<- this.sim[[main.server.name]]
		qqa$check.main.server(this.queue)
		buffer.size = nrow(db[[job.type]])
		qdata <<- data.frame(job = rep(NA, buffer.size), joined = NA, left = NA)
		job.size <<- db[[job.type]]$job.size
		if (is.null(job.size)) job.size <<- rep(1, buffer.size)		# default to 1 if no job.size column
	}, env)

	# -----------------------------------------------------------------------------	
	# On shutdown, perform final calculation of queue stats, with result in qdata
	
	evalq(shutdown <- function() {
		calculate.stats()
	}, env)
	
	# -------------------------- Queue plotting functions -------------------------
	# Note that the various plotting functions require calculate.stats to have been 
	# called *first*. This happens automatically when the queue is shut down, so the
	# user doesn't have to call it directly.

	# ---- plotdata ---------------------------------------------------------------
	# Return a version of 'qdata' with dummy rows for the times in ticks.when.empty.
	# This will give prettier plots, as the qlen and qtime will be shown correctly
	# as hitting zero at the point the queue was cleared (even if no passenger joined
	# at that instant).

	evalq(plotdata <- function() {
		stopifnot(run.status == "run.complete")
		if (length(ticks.when.empty) > 0) {
			dummy.rows = data.frame(
				job = NA, joined = ticks.when.empty, left = ticks.when.empty, 
				job.size = 0, qlen = 0, qtime = 0
			)
			result = rbind(dummy.rows, qdata)
			result = result[order(result$joined, result$left), ]	# sort by time
		} else result = qdata
		return(result)
	}, env)

	# ---- empty.plot -------------------------------------------------------------
	# Plot empty axes suitable for a plot of this queue, where 'measure' is either
	# "qlen" for length of queue (in terms of job.size), or "qtime" for waiting time.
	# The y-axis will be scaled to accommodate the biggest queue in the simulation.
	# Optional args (...) will be passed to the R plot function -- for example, pass
	# 'main' to override the default title and 'ylim' to override the y-axis scale.
	
	evalq(empty.plot <- function(measure = "qtime", ...) {
		stopifnot(measure %in% c("qtime", "qlen"))
		stopifnot(run.status == "run.complete")
		next.legend <<- list(			# set default args for if legend is added later
			x = "topleft",
			bty = "o",					# box around legend
			bg = "white",				# fill box with white
			box.col = "white",			# frame box in white (invisibly)
			inset = 0.04,				# move in 4% of plot area from top left corner
			seg.len = 3
		)
		
		if (measure == "qtime")	max.measures = 
			sapply(all.queues(), function(queue) queue$qmax(ignore.inf = TRUE))
		else max.measures = 
			sapply(all.queues(), function(queue) max(queue$qdata$qlen))
		max.measure = max(max.measures)
		
		default.args = list(
			x = 1,						# dummy data, will plot axes only
			xlim = c(0, clock$ticks),
			ylim = c(0, max.measure),
			main = if (measure == "qtime") "Queue time" else "Queue length",
			ylab = if (measure == "qtime") "Waiting time (ticks)" else "Length of queue (count)",
			xlab = "Elapsed time (ticks)"
		)
		plot.args = list.union(list(...), default.args)		# combine user-supplied with default args 
															# 	- the former take precedence
		plot.args$type = "n"								# always suppress body of plot
		do.call(plot, plot.args)
		abline(v = axTicks(1), lty = 3, col = "lightgrey")	# vertical grid
		abline(h = axTicks(2), lty = 3, col = "lightgrey")	# horizontal grid
	}, env)
	
	# ----- over.plot -------------------------------------------------------------	
	# Add a plot of this queue to pre-existing axes.
	# Optional args (...) will be passed to the R plotting function, and retained for
	# use in the legend if applicable. For example, you can pass 'lwd', 'lty' and 'col'
	# for a custom line format, and 'legend' for a custom label in the legend.
	
	evalq(over.plot <- function(measure = "qtime", ...) {
		stopifnot(measure %in% c("qtime", "qlen"))
		pdat = plotdata()
		default.args = list(
			x = pdat$joined,
			y = pdat[[measure]],
			col = "blue",
			lwd = 2,
			lty = 1,
			type = "l"
		)
		plot.args = list.union(list(...), default.args)		# combine user-supplied with default args 
															# 	- former take precedence
		do.call(points, plot.args)
		plot.args$legend = queue.name						# label for legend entry

		# Add relevant args to next.legend
		lapply(c("legend", "col", "lwd", "lty"), function(argname) {
			next.legend[[argname]] <<- c(next.legend[[argname]], plot.args[[argname]])
		})
		return()
	}, env)

	# ---- new.plot ---------------------------------------------------------------	
	# Plot this queue on new axes. Optional args (...) are handled as for over.plot
	
	evalq(new.plot <- function(measure = "qtime", ...) {
		empty.plot(measure, ...)
		over.plot(measure, ...)
	}, env)

	# ---- add.legend --------------------------------------------------------------
	# Add a legend to an existing plot, based on the next.legend info that was recorded
	# when individual lines were plotted. Optional args (...) are passed to R's 'legend'
	# function.
	
	evalq(add.legend <- function(...) {
		this.sim$add.legend(...)
	}, env)

	return(env)	
}	# end make.queue

# =============================================================================
# 'make.chooser' creates a 'chooser' object, which routes jobs to a choice of
# queues depending on some metric such as the current projected wait time or 
# current queue length. This may be used to simulate balking.
# =============================================================================
#
# Whenever the chooser receives jobs, it will call the function given by metric.name
# on each of the queues given by queue.names. The "winner" is the queue with the 
# smallest value of this function. The jobs will be passed on to the receive.jobs
# function of the winner.
#
# The *first* element of queue.names is treated by some functions as a "default".
# For example, the preferred.by method of a queue object will regard that queue 
# as being "preferred" by any chooser for which it is in this first position.
# Disabling the chooser will route all jobs passing through it to this default 
# queue.
#
# ARGUMENTS
# sim.env:			a simulation environment, as created by new.simulation
# chooser.name:		a textual name for this chooser
# queue.names:		vector of names of queues to choose between
# metric.name:		Name of a function to be called on each queue; the queue 
#					returning the *lowest* value of this function is the "winner".
#					Current suitable functions are "qlen" and "projected.qtime"

make.chooser = function(sim.env, chooser.name, queue.names, metric.name = "projected.qtime") {
	env = new.env(parent = sim.env)
	sim.env[[chooser.name]] = env
	sim.env$routes[[chooser.name]] = env	# add this chooser to the list of available 'routes'
	env$this.chooser = env
	env$chooser.name = chooser.name
	env$queue.names = queue.names
	env$metric.name = metric.name
	
	# placeholders to be resolved on wakeup
	env$queues = "placeholder"        # list of queues that are possible options
	env$metrics = "placeholder"       # list of functions for each queue corresponding to metric.name

	# ---- disable ----------------------------------------------------------------
	# Disabling a chooser forces it to choose always the *first* queue in queue.names

	evalq(disable <- function() {
		queue.names <<- queue.names[1]
		if (typeof(queues) == "list") {		# if post wakeup, update 'queues' and 'metrics'
			queues <<- queues[1]
			metrics <<- metrics[1]
		}
	}, env)
	
	# ---- receive.jobs -----------------------------------------------------------
	# Forward job.indices to the receive.jobs function of the queue with the current 
	# smallest metric.

	evalq(receive.jobs <- function(job.indices) {
	
		# Obtain the result of the metric function (with empty args list) on each queue
		# Then locate the minimum (note if all metrics are Inf, which.min picks the first)
		current.metrics = sapply(metrics, do.call, list())    	
		queue.index = which.min(current.metrics)
		queues[[queue.index]]$receive.jobs(job.indices)		
	}, env)

	# -----------------------------------------------------------------------------
	# Waking up a chooser resolves the 'queues' and 'metrics' fields
	
	evalq(wakeup <- function() {
		qqa$check.metric(this.chooser)
		qqa$check.queues.exist(this.chooser)
		queues <<- lapply(queue.names, function(qname) this.sim[[qname]])
		metrics <<- lapply(queues, function(queue) queue[[metric.name]])
		names(queues) <<- queue.names
		names(metrics) <<- queue.names
	}, env)
	
	return(env)
}	# end make.chooser

# =============================================================================
# 'make.outflow' creates an 'outflow' object, which forms a sink for jobs that 
# have completed their role in the simulation. The outflow maintains a record of 
# which jobs have left, and at what time.
# =============================================================================
#
# Do not call this function directly; it is called automatically when the simulation
# is created, once for each job type in 'db'.
#
# ARGUMENTS
# sim.env:			a simulation environment, as created by new.simulation
# outflow.name:		a textual name for this outflow
# job.type:			the type of job that can leave through this outflow. Must be
#					the	name of a data frame in sim.env$db.

make.outflow = function(sim.env, outflow.name, job.type) {
	env = new.env(parent = sim.env)
	sim.env[[outflow.name]] = env
	sim.env$routes[[outflow.name]] = env	# add this outflow to the list of available routes
	env$outflow.name = outflow.name
	env$job.type = job.type
	env$leavers = "placeholder"		# data frame to record job leaving times; resolved at wakeup
	env$leaver.index = 1            # index in 'leavers' where next leaver will be recorded

	# ---- receive.jobs ----------------------------------------------------------	
	# Record the exit from the simulation of zero or more jobs
	
	evalq(receive.jobs <- function(job.indices) {
		number.of.leavers = length(job.indices)
		if (number.of.leavers > 0) {
			leaver.seq = seq(leaver.index, length = number.of.leavers)
			leavers$job[leaver.seq] <<- job.indices
			leavers$exited[leaver.seq] <<- clock$ticks
			leaver.index <<- leaver.index + number.of.leavers
		}
	}, env)

	# -----------------------------------------------------------------------------	
	# Waking an outflow initialises the 'leavers' data frame
	
	evalq(wakeup <- function() {
		buffer.size = nrow(db[[job.type]])
		leavers <<- data.frame(job = rep(NA, buffer.size), exited = NA)
	}, env)
	
	return(env)	
}	# end make.outflow

# =============================================================================
# 'make.multiserver' creates a 'multiserver' object, containing zero or more 
# servers that can each hold one job at a time, releasing it after a given 
# transaction time.
# =============================================================================

# A multiserver can serve one or more queues. When it is polled, it will seek to 
# pull jobs from these queues in priority order, subject to having free capacity. 
# Completed jobs will be sent to the object given by their 'route', such as another
# queue or an outflow.
#
# A multiserver maintains a record in its 'util' field of how many servers were 
# open and how many were in use over time.
#
# The capacity of a multiserver may be set directly or controlled through 
# 'resources' that it requires to operate (but not both). See 'make.resource'.
#
# ARGUMENTS
# sim.env:			a simulation environment, as created by new.simulation
# multiserver.name:	a textual name for this multiserver, e.g. "customer.service.desk"
# server.type:		a more generic name for the type of server, e.g. "desk"
# capacity:			the number of servers in this multiserver, i.e. the number of 
#					jobs that can be processed in parallel. Note that if the server 
#					requires 'resources', the capacity argument will be overridden 
#					to reflect resource availability. Must be a finite integer.
# job.type:			the type of job that this multiserver can handle. Must be the
#					name of a data frame in sim.env$db.
# queue.names.served:	character vector naming queue objects from which this 
#						multiserver	can pull jobs, in priority order (so, for as 
#						long as the first queue named has jobs, the others won't 
#						be served). Must already exist when the multiserver is 
#						created.
# main.queue.name:		name of the queue object primarily designated for this 
#						multiserver, to be used for estimating its required capacity.
#						Defaults to the	first queue named in queue.names.served.
# fixed.points:		a minimum capacity to be maintained on this multiserver, subject 
#                   to sufficient resources. Soft constraint.
# max.capacity:		a capacity that may never be exceeded, however many resources 
#					are	provided. Defaults to 'capacity', can legally be Inf. Hard 
#					constraint.
# suspended.route.name:	textual name of a route where part-completed jobs may be
#						sent if the multiserver is told to reduce its capacity.
#						If NULL (the default) these jobs will be allowed to run
#						to completion before the capacity reduction takes effect.
# suspended.restart:	boolean for whether suspended jobs have to start over,
#						reincurring their full txtime, on re-entry to this 
#						multiserver. If FALSE, they continue where they left off.

make.multiserver = function(
	sim.env, multiserver.name, server.type, capacity, job.type, queue.names.served, 
	main.queue.name = queue.names.served[1], fixed.points = 0, max.capacity = capacity,
	suspended.route.name = NULL, suspended.restart = FALSE
) {
	env = new.env(parent = sim.env)
	env$this.multiserver = env
	sim.env[[multiserver.name]] = env
	sim.env$multiservers[[multiserver.name]] = env
	env$multiserver.name = multiserver.name
	env$server.type = server.type
	env$capacity = capacity					# i.e. number of servers
	env$job.type = job.type
	qqa$check.job.type(sim.env, multiserver.name, job.type)
	qqa$check.server.type(env)
	env$queue.names.served = queue.names.served
	env$main.queue.name = main.queue.name
	qqa$check.queues.served(env)
	env$fixed.points = fixed.points
	env$max.capacity = max.capacity			# if modified directly, will take effect at the next allocation
	qqa$check.capacity(env)
	qqa$check.fixed.points(env)
	env$queues.served = list()   			# to be populated below with references queues named in queue.names.served
	env$main.queue = "placeholder"			# main queue served by this multiserver; resolved below at creation
	env$residual.time = "placeholder"		# vector of (outstanding) transaction times on jobs of job.type; resolved at wakeup
	env$route.index = "placeholder"			# vector of route indices for next destination of jobs; resolved at wakeup
	env$target.capacity = capacity			# a capacity to be realised later, as soon as ongoing jobs are completed
	env$upper.capacity = max.capacity		# for use by marshal and resource allocators; capacity attainable subject to resources
	env$current.jobs = rep(0, capacity)     # the job.index of the job currently being processed on each server; zero if none
	env$due.at = rep(0, capacity)           # completion times of current jobs, if any
	env$util = "placeholder"				# data frame to hold utilisation; resolved on wakeup
	env$util.index = 1       				# row index into 'util' data frame
	env$last.tick = 0        				# the clock tick when the previous update to 'util' took place
	env$num.tolerance = 1e-06				# floating point tolerance for comparing "equal" ticks
	env$jobs.pulled = "placeholder"			# vector of jobs pulled throughout simulation; resolved on wakeup
	env$jobs.pulled.index = 1				# index in jobs.pulled at which to store next job
	if (!is.null(suspended.route.name)) 
		qqa$check.route.exists(sim.env, suspended.route.name)
	env$suspended.route.name = suspended.route.name
	env$suspended.restart = suspended.restart
	
	# Resolve at creation queues.served, and the served.by fields of these queues
	for (queue.name in queue.names.served) {
		queue = sim.env[[queue.name]]
		queue$served.by[[multiserver.name]] = env$this.multiserver
		env$queues.served[[queue.name]] = queue
	}
	env$main.queue = sim.env[[main.queue.name]]

	# ---- force.empty ------------------------------------------------------------
	# Force the multiserver to have zero capacity immediately. It is illegal to call 
	# this function while the multiserver has uncompleted jobs. Doing so will raise 
	# an error. Note that this function does NOT update the 'util' data frame, as it
	# may be called before the multiserver has been woken up, at which stage 'util' 
	# hasn't yet been initialised.
	
	evalq(force.empty <- function() {
		if (!all(current.jobs == 0))
			stop("Attempting to force ", multiserver.name, " to empty while it has uncompleted jobs")
		capacity <<- 0
		target.capacity <<- 0
		current.jobs <<- numeric(0)
		due.at <<- numeric(0)
	}, env)

	# ---- monopolise.queue -------------------------------------------------------	
	# This function is deprecated and may be removed in a future version, as 
	# suppress.fallback() is a more robust approach when we want to halt fallback
	# service selectively for capacity estimation purposes.
	#
	# Identify any other multiservers specified as able to serve the main.queue of 
	# this multiserver. Override such multiservers so that they can no longer serve it,
	# *unless* they were created earlier than this multiserver.
	
	evalq(monopolise.queue <- function() {
		warning("Deprecated function: monopolise.queue")
		served.by.names = names(main.queue$served.by)				# these are in order of server creation
		served.by.index = match(multiserver.name, served.by.names)	# index of this multiserver in the names
		earlier.names = served.by.names[1:served.by.index]			# servers created before this one, inclusive
		later.names = setdiff(served.by.names, earlier.names)		# servers created after this one, exclusive

		# For those multiservers, created later than this multiserver, that can currently serve main.queue:
		# Override their queues.served and queue.names.served fields so they no longer serve it
		later.multiservers = main.queue$served.by[later.names]
		lapply(later.multiservers, function(ms) {
			stopifnot(length(ms$queue.names.served) > 1)		# error if alternative server would have no queues left
			stopifnot(ms$main.queue.name != main.queue.name)	# error if try to monopolise the *main* queue of alternative server
			ms$queue.names.served = setdiff(ms$queue.names.served, main.queue$queue.name)
			ms$queues.served = ms$queues.served[ms$queue.names.served]
		})
		
		# Override main.queue so that it is served only by the earlier multiservers, including this one
		main.queue$served.by = main.queue$served.by[earlier.names]
	}, env)

	# ---- suppress.fallback ------------------------------------------------------	
	# Identify multiservers that were created later than this.multiserver, and prevent 
	# them from providing fallback service to queues that are neither their main.queue
	# nor for which they are the queue's main.server. This can be used in capacity 
	# estimation when we want certain jobs to go to a designated server rather than 
	# relying on fallback.
	
	evalq(suppress.fallback <- function() {
		# Obtain indices in the 'multiservers' list of those created later than this multiserver
		this.index = match(multiserver.name, names(multiservers))
		later.indices = seq(
			from = this.index + 1, 
			length = length(multiservers) - this.index		# empty seq legal if length zero
		)
		
		# Limit these later multiservers to designated "main" services
		lapply(multiservers[later.indices], function(ms) {
			qnames.to.keep = character(0)
			for (queue in ms$queues.served) {
				is.main.queue = identical(queue, ms$main.queue)
				is.main.server = queue$main.server.name == ms$multiserver.name
				if (is.main.queue || is.main.server)
					qnames.to.keep = c(qnames.to.keep, queue$queue.name)
				else queue$served.by[ms$multiserver.name] = NULL
			}
			ms$queue.names.served = qnames.to.keep
			ms$queues.served = ms$queues.served[qnames.to.keep]
		})
	}, env)

	# ---- suppress.balking -------------------------------------------------------	
	# Identify any choosers for which the main.queue of this multiserver is the 
	# preferred choice. Then, disable all those choosers so they will be forced to 
	# direct all traffic to the main queue. In this way, all traffic that is destined 
	# for this multiserver by default will be forced to use it, without balking. This
	# can be used in capacity estimation when we want a multiserver to be able to 
	# cope without relying on balking.
	
	evalq(suppress.balking <- function() {
		choosers = main.queue$preferred.by()
		lapply(choosers, function(chooser) chooser$disable())
		return()
	}, env)

	# ---- work.in.progress -------------------------------------------------------	
	# Return the amount of outstanding work in server ticks for all active jobs.

	evalq(work.in.progress <- function() {
		sum(pmax(0, due.at - clock$ticks))
	}, env)

	# ---- jobs.on.server ---------------------------------------------------------	
	# The number of active jobs on this multiserver
	
	evalq(jobs.on.server <- function() {
		sum(sign(current.jobs))
	}, env)

	# ---- size.on.server ---------------------------------------------------------	
	# The total size of the active jobs on this multiserver
	
	evalq(size.on.server <- function() {
		sum(main.queue$job.size[current.jobs])
			# note zero indices in current.jobs will select no element from job.size
	}, env)

	# ---- pull.jobs ------------------------------------------------------------	
	# If there are free servers, pull jobs (if available) from queues.served, in 
	# priority order. The 'onto.servers' argument is a Boolean vector of length 
	# 'capacity' showing which servers are free to receive a new job.
	
	evalq(pull.jobs <- function(onto.servers) {
		assignable.servers = which(onto.servers)      		# *indices* of servers that can receive a job
		for (queue in queues.served) {
			free.capacity = length(assignable.servers)
			if (free.capacity == 0) break             		# no (more) servers free, take no more jobs
			new.jobs <<- queue$yield.jobs(free.capacity)
			job.count = length(new.jobs)
			if (job.count != 0) {
				jobs.pulled[seq(jobs.pulled.index, length = job.count)] <<- new.jobs
				jobs.pulled.index <<- jobs.pulled.index + job.count
				due.times = clock$ticks + residual.time[new.jobs]
				servers.assigned = assignable.servers[1:job.count]
				current.jobs[servers.assigned] <<- new.jobs
				due.at[servers.assigned] <<- due.times
				clock$poll.at(this.multiserver, due.times)				 # raise poll event for when these jobs are completed
				assignable.servers = assignable.servers[-(1:job.count)]  # have now been assigned => no longer assignable
			}
		}
	}, env)

	# ---- eject.jobs -------------------------------------------------------------	
	# If some servers have completed jobs, look up the next 'route' for those jobs, 
	# and call receive.jobs on the object where they are to be sent next (e.g. an 
	# outflow). The 'from.servers' argument is a Boolean vector of length 'capacity',
	# showing which servers have completed jobs.
	# NOTE: We don't currently clear the residual.time on completed jobs.
	
	evalq(eject.jobs <- function(from.servers) {
		jobs.to.eject = current.jobs[from.servers]
		current.jobs[from.servers] <<- 0
		### Might be more efficient to pool identical routes, but would need to check
		### for the case of zero jobs to eject.
		for (job in jobs.to.eject)
			routes[[route.index[job]]]$receive.jobs(job)
	}, env)

	# ---- suspend.jobs --------------------------------------------------------
	# Close 'how.many' servers immediately and send their jobs to suspended.route.name.
	# To be called when *all* servers are busy and some are to be forced to suspend
	# their jobs so that resource can be released. If suspended jobs are allowed to
	# continue where they left off (as opposed to restarting), their residual.times
	# are adjusted accordingly.
	# This function is for internal use by the multiserver object; external callers 
	# should use set.future.capacity.
	
	evalq(suspend.jobs <- function(how.many) {
		if (how.many > 0) {
			stopifnot(how.many <= capacity)
			drop.which = 1:how.many						# indices of servers to close
			jobs.to.suspend = current.jobs[drop.which] 	# indices of jobs to suspend
			if (!suspended.restart)
				residual.time[jobs.to.suspend] <<- due.at[drop.which] - clock$ticks
			routes[[suspended.route.name]]$receive.jobs(jobs.to.suspend)
			
			update.util()
			current.jobs <<- current.jobs[-drop.which]
			due.at <<- due.at[-drop.which]
			capacity <<- capacity - how.many
		}
	}, env)

	# ---- drop.idle.capacity -------------------------------------------------------	
	# Close a given number of servers, subject to sufficient servers not being busy.
	# Return the number actually closed. 
	# This function is for internal use by the multiserver object; external callers 
	# should use set.future.capacity.
	
	evalq(drop.idle.capacity <- function(drop.how.many) {
		droppable.servers = which(current.jobs == 0)
		drop.count = min(drop.how.many, length(droppable.servers))
		if (drop.count > 0) {
			update.util()         # reflecting time period *before* we drop servers
			drop.which = droppable.servers[1:drop.count]
			current.jobs <<- current.jobs[-drop.which]
			due.at <<- due.at[-drop.which]
			capacity <<- capacity - drop.count
		}
		return(drop.count)
	}, env)

	# ---- drop.capacity ------------------------------------------------------------	
	# Attempt to close a given number of servers, and return the number actually closed.
	#
	# If job suspension is disabled (i.e. suspended.route.name is NULL) then *only*
	# idle servers are considered for closure, and the number actually closed may be
	# less than what was asked for (and may be zero if all were busy).
	#
	# If job suspension is enabled, it is guaranteed that all the servers asked for
	# will be closed (provided only that they existed). Priority is given to closing
	# idle servers, as before. If there are not enough of these, jobs will be suspended
	# on some of the busy servers.
	#
	# This function is for internal use by the multiserver object; external callers 
	# should use set.future.capacity.
	
	evalq(drop.capacity <- function(drop.how.many) {
		dropped.from.idle = drop.idle.capacity(drop.how.many)
		if (is.null(suspended.route.name)) {
			return(dropped.from.idle)
		} else {
			suspend.how.many = drop.how.many - dropped.from.idle
			if (suspend.how.many > 0)
				suspend.jobs(suspend.how.many)
			return(drop.how.many)
		}
	}, env)

	# ---- gain.capacity ----------------------------------------------------------	
	# Open a given number of extra servers immediately, and ask the clock to poll this
	# multiserver during the current tick (so that any work waiting can be assigned).
	# This function is for internal use by the multiserver object; external callers
	# should use set.future.capacity.
	
	evalq(gain.capacity <- function(gain.how.many) {
		if (gain.how.many > 0) {
			update.util()         # reflecting time period *before* we gain servers
			current.jobs <<- c(current.jobs, rep(0, gain.how.many))
			due.at <<- c(due.at, rep(0, gain.how.many))
			capacity <<- capacity + gain.how.many
			clock$poll.after(this.multiserver, 0)
		}
		return(gain.how.many)
	}, env)

	# ---- set.future.capacity ----------------------------------------------------	
	# Set the future capacity of the multiserver. This is the public interface 
	# for changing the capacity, and the safe way for other objects to communicate
	# with the multiserver to ensure it is maintained in a consistent state.
	#
	# If the argument is GREATER than the current capacity, the capacity will be 
	# increased to the target immediately, subject to the limit imposed by this 
	# multiserver's upper.capacity. A poll event will be raised so that the newly 
	# provided servers can accept any available jobs.
	#
	# If the argument is LESS than the current capacity, the capacity will be 
	# decreased so that it eventually reaches the argument, or the upper.capacity, 
	# if that is lower. If job suspension is enabled, this reduction will take
	# place immediately; the multiserver will for preference close idle servers,
	# and if necessary suspend jobs on busy servers. If job suspension is disabled,
	# the reduction will take place as and when sufficient servers are idle;
	# so some or all of it might happen immediately, and some at a later tick
	# when running jobs are completed.
	#
	# The return value is the actual capacity immediately after the call.
	
	evalq(set.future.capacity <- function(future.capacity) {
		new.capacity = min(upper.capacity, future.capacity)
		if (new.capacity > capacity)
			gain.capacity(new.capacity - capacity)
		else if (new.capacity < capacity)
			drop.capacity(capacity - new.capacity)
		target.capacity <<- new.capacity
		return(capacity)
	}, env)
	
	# ---- relinquish.capacity ------------------------------------------------------------	
	# Check whether any currently free servers need to be closed in order to meet a pre-
	# existing target capacity. If yes, close them immediately and inform the marshal.
	
	evalq(relinquish.capacity <- function() {
		number.dropped = drop.capacity(capacity - target.capacity)
		if (number.dropped > 0)
			marshal$receive.capacity(this.multiserver)
	}, env)
	
	# ---- update.util ------------------------------------------------------------	
	# Record the current status of the multiserver in its util data frame.
	
	evalq(update.util <- function() {
		# Only log state of non-zero duration, within floating point tolerance
		this.tick = clock$ticks
		if (this.tick - last.tick > num.tolerance) {    # this.tick never strictly less than last.tick so no abs()
			servers.idle = (current.jobs == 0)     		# vector of Boolean for servers that are idle
			util[util.index, ] <<- c(last.tick, this.tick, capacity, capacity - sum(servers.idle))
			util.index <<- util.index + 1
			last.tick <<- this.tick
		}
	}, env)

	# ---- condense.util ----------------------------------------------------------	
	# Log final server state in util. This function is called when the multiserver
	# is shut down. Redundant rows in the util data frame are condensed so that it
	# is expressed more concisely.
	
	evalq(condense.util <- function() {
		# Record final state as for update.util, even if zero duration
		this.tick = clock$ticks
		servers.idle = (current.jobs == 0)     # vector of Boolean for servers that are idle
		util[util.index, ] <<- c(last.tick, this.tick, capacity, capacity - sum(servers.idle))

		# Remove NAs
		util <<- util[!is.na(util$from), ]
		
		# Form lag and lead of vectors, using -1 as a dummy boundary value
		nr = nrow(util)
		servers.lag = c(-1, util$servers[-nr])
		busy.lag = c(-1, util$busy[-nr])
		servers.lead = c(util$servers[-1], -1)
		busy.lead = c(util$busy[-1], -1)
		
		# Detect adjacent duplicates, both with previous and next rows
		dup.previous = (util$servers == servers.lag) & (util$busy == busy.lag)
		dup.next = (util$servers == servers.lead) & (util$busy == busy.lead)
		
		# Replace util with de-duped version
		util <<- data.frame(
			from = util$from[!dup.previous],
			till = util$till[!dup.next],           # the 'till' field comes from the *end* of a duplicate run
			servers = util$servers[!dup.previous],
			busy = util$busy[!dup.previous]
		)
		
	}, env)

	# ---- integrated.capacity ---------------------------------------------------	
	# Return the capacity (number of servers) integrated over time, for the duration
	# of util. The condense.util function must have been called first.
	
	evalq(integrated.capacity <- function() {
		with(util, sum((till - from) * servers))
	}, env)

	# ---- work.done --------------------------------------------------------------	
	# Return the total work done (in server-ticks) on this multiserver. The condense.util
	# function must have been called first.
	#
	# The overall utilisation of this multiserver, in terms of its servers that were open
	# (so ignoring e.g. closures due to lack of resources) is given by:
	#		work.done() / integrated.capacity()

	evalq(work.done <- function() {
		with(util, sum((till - from) * busy))
	}, env)

	# -----------------------------------------------------------------------------
	# Polling a multiserver first checks whether any jobs have been completed and if so, 
	# sends them to their next destination. It may then close some newly freed servers to
	# meet any target capacity set earlier. Finally, if some servers are free, it pulls
	# jobs (if available) from the queue(s) served.
	
	evalq(poll.me <- function() {
		update.util()
		
		# Form vectors of Boolean for servers that are (1) idle and (2) hold a completed job
		servers.idle = (current.jobs == 0)     
		servers.completed = (!servers.idle & clock$ticks >= due.at)
		if (any(servers.completed))
			eject.jobs(servers.completed)
		if (capacity > target.capacity)		# Were we waiting on jobs to complete in order to reach target capacity?
			relinquish.capacity()			# If yes, see if we can reduce capacity following any jobs ejected.
		servers.ready = (current.jobs == 0) # vector of Boolean for servers that are now free to receive a job
		if (any(servers.ready))
			pull.jobs(servers.ready)
	}, env)

	# -----------------------------------------------------------------------------
	# Waking up a multiserver resolves the jobs.pulled, residual.time and route.index fields.
	# It initialises the util data frame. Finally it asks to be polled at tick zero.
	
	evalq(wakeup <- function() {
		job.data = db[[job.type]]
		jobs.pulled <<- rep(NA, nrow(job.data))
		txtime.colname = paste(server.type, "txtime", sep = ".")
		residual.time <<- job.data[[txtime.colname]]
		route.colname = paste(server.type, "route", sep = ".")
		route = job.data[[route.colname]]
		route.name = levels(route)[route]
		route.index <<- match(route.name, names(routes))		
		buffer.size = nrow(db[[job.type]])
		util <<- data.frame(from = rep(NA, buffer.size), till = NA, servers = NA, busy = NA)
		util$from[1] <<- 0
		util$servers[1] <<- capacity
		util$busy[1] <<- 0
		clock$poll.at(this.multiserver, 0)
	}, env)
	
	# -----------------------------------------------------------------------------
	# On shutdown, condense the utilisation log and discard unused jobs.pulled entries.
	evalq(shutdown <- function() {
		condense.util()
		jobs.pulled <<- jobs.pulled[!is.na(jobs.pulled)]
	}, env)

	# ------------------------- Multiserver plotting functions --------------------
	# Note that the various plotting functions require condense.util to have been 
	# called *first*. This happens automatically when the multiserver is shut down, 
	# so the user doesn't have to call it directly.

	# ---- plotdata ---------------------------------------------------------------	
	# Return a version of 'util' suitable for plotting: if necessary, a dummy row is 
	# appended, so that the final state will appear in the plot.
	
	evalq(plotdata <- function() {
		stopifnot(run.status == "run.complete")
		dummy.row = tail(util, 1)
		if (dummy.row$from == clock$ticks)
			result = util						# no need to append dummy row, would be duplicate
		else {
			dummy.row$from = clock$ticks		# capture final state in a zero-time interval
			dummy.row$till = clock$ticks
			result = rbind(util, dummy.row)
		}
		return(result)
	}, env)

	# ---- workdata ---------------------------------------------------------------
	# Return aggregated workload arriving at the main queue served by this multiserver
	# Aggregation is into bins of width specified (in ticks) by the 'bin.width' argument.
	#
	# RETURN VALUE
	# A data.frame, whose columns are:
	# 	bin:	the bin number, zero-based
	#	ticks:	the elaspsed simulation time at the start of the bin
	# 	txtime:	the total txtime of all jobs arriving during that bin. i.e. within 
	#			a time interval of [ticks, ticks + bin.width)
	#	demand:	the number of servers (in general, non-integer) that when open for 
	#			bin.width, could process all of txtime. 'demand' treats both servers and
	#			jobs as continuous, not discrete.
	#
	# Note that if one multiserver can serve multiple queues, or vice versa, there may
	# not be a 1:1 correspondence between jobs arriving in the main queue and jobs 
	# actually served.
	
	evalq(workdata <- function(bin.width = 5) {
		stopifnot(run.status == "run.complete")
		qdata = main.queue$qdata
		job.data = db[[job.type]]
		txtime.colname = paste(server.type, "txtime", sep = ".")
		all.jobs.txtime = job.data[[txtime.colname]]
		qdata$txtime = all.jobs.txtime[qdata$job]
		qdata$bin = floor(qdata$joined / bin.width)		# zero-based
		if (nrow(qdata) == 0) qdata[1, ] = 0			# dummy row so can still aggregate if queue never used
		aggdata = aggregate(txtime ~ bin, FUN = sum, data = qdata)
		result = data.frame(
			bin = seq(from = 0, to = floor(clock$ticks / bin.width))
		)
		result$ticks = result$bin * bin.width
		result = merge(result, aggdata, all.x = TRUE)		# left outer join, so retain bins with no joiners...
		result$txtime[is.na(result$txtime)] = 0				# ...which have zero txtime
		result$demand = result$txtime / bin.width			# workload expressed in equivalent servers
		return(result)
	}, env)

	# ---- empty.plot -------------------------------------------------------------
	# Plot empty axes suitable for a plot of this multiserver. Axes will be scaled 
	# for the multiserver with the highest peak servers or demand in the simulation.
	# Any optional args (...) will be passed to the R plotting function and will
	# override the defaults.

	evalq(empty.plot <- function(...) {
		stopifnot(run.status == "run.complete")
		next.legend <<- list(			# set default args for if legend is added later
			x = "topleft",
			bty = "o",					# box around legend
			bg = "white",				# fill box with white
			box.col = "white",			# frame box in white (invisibly)
			inset = 0.04,				# move in 4% of plot area from top left corner
			seg.len = 3
		)	
		max.servers.individual = 		# max number of servers open, by individual multiserver
			sapply(multiservers, function(ms) max(ms$util$servers))
		max.demand.individual =			# max demand on the primary queue, by individual multiserver
			sapply(multiservers, function(ms) max(ms$workdata()$demand))
		max.ylim = max(max.servers.individual, max.demand.individual)	# max for y-axis of plot
		default.args = list(
			x = 1,						# dummy data, will plot axes only
			xlim = c(0, clock$ticks),
			ylim = c(0, max.ylim),
			main = "Servers and workload",
			ylab = "Servers",
			xlab = "Elapsed time (ticks)"
		)
		
		# combine user-supplied with default args (the former take precedence)
		plot.args = list.union(list(...), default.args)			
		plot.args$type = "n"									# always suppress body of plot
		do.call(plot, plot.args)
		abline(v = axTicks(1), lty = 3, col = "lightgrey")		# vertical grid
		abline(h = axTicks(2), lty = 3, col = "lightgrey")		# horizontal grid		
	}, env)

	# ---- over.plot --------------------------------------------------------------
	# Add a plot of this multiserver to pre-existing axes. The 'measure' argument
	# must be one of "servers", "busy" or "demand", selecting what attribute of the
	# multiserver to plot. Any optional args (...) will be passed to the R plotting
	# function. Args such as 'col', 'lwd' and 'lty' may be used to customise the
	# format.
	
	evalq(over.plot <- function(measure = "servers", ...) {
		stopifnot(measure %in% c("servers", "busy", "demand"))
		stopifnot(run.status == "run.complete")
		if (measure == "servers") {
			pdat = plotdata()
			legend.measure = "servers open"
			default.args = list(
				x = pdat$from,
				y = pdat$servers,
				col = "blue",
				lwd = 2,		# line width 2 pixels
				lty = 2,		# dashed line
				type = "s"
			)
		} else if (measure == "busy") {
			pdat = plotdata()
			legend.measure = "servers busy"
			default.args = list(
				x = pdat$from,
				y = pdat$busy,
				col = "blue",
				lwd = 1,		# line width 1 pixel
				lty = 1,		# solid line
				type = "s"
			)
		} else if (measure == "demand") {
			pdat = workdata()
			legend.measure = "demand"
			default.args = list(
				x = pdat$ticks,
				y = pdat$demand,
				col = "blue",
				lwd = 1,		# line width 1 pixel
				lty = 3,		# dotted line
				type = "s"
			)		
		}
		plot.args = list.union(list(...), default.args)
		do.call(points, plot.args)			# add to existing plot
		
		# Add relevant args to next.legend
		plot.args$legend = paste(multiserver.name, legend.measure, sep = " - ")
		lapply(c("legend", "col", "lwd", "lty"), function(argname) {
			next.legend[[argname]] <<- c(next.legend[[argname]], plot.args[[argname]])
		})
		return()
	}, env)

	# ---- new.plot ---------------------------------------------------------------
	# Plot this multiserver on new axes. The 'measure' and the optional args are
	# treated as for over.plot above.
	
	evalq(new.plot <- function(measure = "servers", ...) {
		empty.plot(...)
		over.plot(measure, ...)
	}, env)

	# ---- triple.plot ------------------------------------------------------------
	# Display a combination plot of 'demand', 'servers' and 'busy'. This plot uses
	# preset formats and isn't customisable. If you want a custom-formtted version,
	# you can use new.plot followed by two calls to over.plot to obtain this effect.
	
	evalq(triple.plot <- function() {
		new.plot(measure = "demand", col = "red")
		over.plot(measure = "servers", col = "blue")
		over.plot(measure = "busy", col = "darkgreen")
		add.legend()
	}, env)

	# ---- add.legend ------------------------------------------------------------
	# Add a legend to an existing plot, based on the next.legend info that was recorded
	# when individual lines were plotted. Optional args (...) are passed to R's 'legend'
	# function.
	
	evalq(add.legend <- function(...) {
		this.sim$add.legend(...)
	}, env)
	
	return(env)
}	# end make.multiserver

# =============================================================================
# 'make.resource' creates a 'resource' object, representing a discrete quantity of
# something needed (e.g. people, hardware) by a multiserver in order to operate.
# =============================================================================
#
# A resource can be shared between several multiservers. The resource object tracks
# what quantity of the resource is actually held by each. It also tracks whether
# some multiservers are due to receive, or relinquish, resource pending completion
# of current jobs.
#
# Separately, the *total* quantity of a resource available to be shared out can be
# made to vary over time. The resource object tracks how much currently exists, and 
# how much of this will remain in existence pending completion of current jobs.
#
# The amount of capacity a multiserver can provide need not be linear in the 
# quantity of resource held by it. The user may provide arbitrary functions to 
# translate to and from resource and capacity. 
#
# A multiserver may require more than one resource. If so, its capacity is taken
# to be the MINIMUM of those calculated from its individual resources.
#
# ARGUMENTS
# sim.env:				a simulation environment, as created by new.simulation
# resource.name:		a textual name for this resource, e.g. "staff"
# initial.quantity: 	how many of this resource are initially present
# used.by:				a vector of textual names of multiservers that need some
#						of this resource
# resource2capacity:	a named list, where the names are the server.type, and the
#						elements are functions taking a numeric argument for 
#						resource and returning a numeric value for capacity
# capacity2resource:	a named list, where the names are the server.type, and the
#						elements are functions taking a numeric argument for 
#						capacity and returning a numeric value for resource
#
# Note all the functions supplied in resource2capacity and capacity2resource must be
# vectorised -- i.e. when given a vector argument, they must return a vector result
# made up by converting every element of the input vector individually.

make.resource = function(sim.env, resource.name, initial.quantity, used.by,
	resource2capacity = list(), capacity2resource = list()
){
	env = new.env(parent = sim.env)
	sim.env[[resource.name]] = env
	env$this.resource = env
	env$resource.name = resource.name
	sim.env$resources[[resource.name]] = env
	env$used.by = used.by
	env$resource2capacity = resource2capacity
	env$capacity2resource = capacity2resource
	
	# The 'usage' list is to be resolved on wakeup. Its four elements will each be
	# given one element for each multiserver that requires some of this resource.
	# The constituent lists / vectors will all be named by 'used.by'

	env$usage = list(
		actual = numeric(0),	# quantity of resource currently held by each multiserver
		due = numeric(0), 		# quantity that the allocator says should be held by each
								# multiserver. May differ from 'actual' if awaiting 
								# redistribution.
		r2c = list(), 			# resource2capacity conversion functions
		c2r = list()			# capacity2resource conversion functions
	)
	
	# The 'total' quantity of this resource in circulation is equal to the sum of the actuals
	# on all multiservers, plus any in the free pool not deployed to any multiserver.
	# The 'allocated' quantity is what the scheduler says there should be (or the initial
	# quantity, if there have been no scheduler events). It may differ from 'total' if
	# still awaiting reclaim or distribtuion.

	env$total = initial.quantity		# actual quantity in circulation
	env$allocated = initial.quantity    # target quantity in circulation
	
	# The 'allocator' function does the work of (re) distributing the work between
	# multiservers. Placeholders are resolved below, during creation of the resource.
	# The smoothing and cutoff parameters are used by allocate.min.wait and by
	# allocate.sla

	env$allocator = "placeholder"		# function to update 'due' vector of 'usage'
	env$allocator.name = "placeholder"	# shorthand textual name for the above function
	env$identity = function(x) x		# the identify function: returns its argument unchanged
	env$alpha = 0.75					# exponential smoothing constant, 0 < alpha <= 1
										# 	- the user may modify this directly
										# 	- an alpha of 1.0 means unsmoothed
										#	- an alpha close to zero means highly smoothed
	env$previous.waits = 0				# record of previous 'total.waits.single' used in smoothing
	env$previous.cutoff = 0.05			# proportion of total.waits.single deemed "effectively zero"
										# 	- the user may modify this directly
										# 	- see section 4.1.4 in Methodology Report in BCA004
	env$continuity.threshold = 20		# allocators correct for discrete jobs above threshold
										#	- the user may modify this directly
	
	# Check existence of caller-supplied multiservers and server.types
	qqa$check.resource(env)
	
	# Initialise log to hold history of resource allocation
	log.names = c(
		"tick", "total", "allocated",
		paste("actual", used.by, sep = "."),
		paste("due", used.by, sep = ".")
	)
	# Initialise as matrix so can set 'ncol' programmatically, then convert to data frame
	log.matrix = matrix(0, nrow = 1, ncol = length(log.names))     
	colnames(log.matrix) = log.names
	env$log = data.frame(log.matrix)
	env$log$total = env$total
	env$log$allocated = env$allocated
	env$log[2:2000, ] = NA			# initialise buffer to ease memory allocation; can legally overflow
	env$log.index = 1	
	
	# ---- log.status -------------------------------------------------------------	
	# Write a line to the log, giving the current distribution of this resource
	
	evalq(log.status <- function() {
		log.line = c(clock$ticks, total, allocated, usage$actual, usage$due)
		this.resource$log[log.index, ] = log.line
		log.index <<- log.index + 1
	}, env)

	# ---- is.used.by ------------------------------------------------------------
	# Is this resource used.by the given multiserver? Returns a logical scalar.
	evalq(is.used.by <- function(multiserver) {
		multiserver$multiserver.name %in% used.by
	}, env)

	# ---- update.upper.capacity -------------------------------------------------
	# Update the upper.capacity on each multiserver used.by this resource, to be the 
	# minimum of its current upper.capacity and the capacity that can be supported 
	# by its 'due' quantity of resource.

	evalq(update.upper.capacity <- function() {
		for (i in seq(along = used.by)) {
			multiserver.name = used.by[i]
			multiserver = this.sim[[multiserver.name]]
			due.resource = usage$due[i]
			due.capacity = usage$r2c[[i]](due.resource)
			multiserver$upper.capacity = 
				min(multiserver$upper.capacity, due.capacity)
		}		
	}, env)

	# ---- allocate.scheduled -----------------------------------------------------	
	# Make this resource 'due' according to the predetermined schedule in 
	# this.resource$schedule. Update the upper capacity on affected multiservers
	# accordingly.

	evalq(allocate.scheduled <- function() {
		schedule.row = match(clock$ticks, schedule$times)
		qqa$check.schedule.row(schedule.row, clock$ticks)
		usage$due[] <<- unlist(schedule[schedule.row, -1])		# extract row, excluding time, as a plain vector
		update.upper.capacity()
	}, env)

	# ---- allocate.min.wait ------------------------------------------------------	
	# Distribute the 'allocated' resource so that it becomes 'due' to the multiservers
	# in used.by such that the projected total waiting time summed over all jobs is
	# minimised (approximately). Subject to a hard constraint on the multiservers'
	# upper.capacity and a soft constraint on the multiservers' fixed.points: if the
	# resource is so scarce that not all fixed.points can be covered, this constraint
	# is relaxed. Update the multiservers' upper.capacity to reflect the allocation.
	
	# The algorithm is fairly brute force, although this is mitigated by being highly
	# vectorised. It is O(n ^ (k - 1)) where n is the number of resources to allocate
	# and k is the number of competing multiservers -- so will scale badly if many
	# multiservers want use of the same resource. In that case, it might need to be re-
	# written using a stepwise search strategy.
	
	evalq(allocate.min.wait <- function() {
		
		# If the resource has only one user then only one allocation is possible, so
		# we skip the bulk of the function that is concerned with optimisation.
		if (length(used.by) == 1) usage$due[1] <<- allocated
		
		if (length(used.by) > 1) {

			# Construct list of all possible resource allocations for multiservers in 
			# used.by *except* the first, which is initialised to zero. Each other
			# interested multiserver will be given between 0 and 'allocated', incl.

			resource.ranges = list(0)
			for (i in seq(2, length(used.by)))
				resource.ranges[[i]] = seq(0, allocated)
			names(resource.ranges) = used.by
			
			# Obtain all possible combinations of the options identfied above for the 
			# individual multiservers. Then deduce the allocation for the first
			# multiserver (that was neglected above) as the remainder once all other
			# allocations are taken into account.

			resource.scenarios = do.call(expand.grid, resource.ranges) 		# full factorial 
			resource.scenarios[1] = allocated - rowSums(resource.scenarios)	# first multiserver to take whatever's left...
			rows.to.keep = resource.scenarios[1] >= 0                		# ...but cannot be negative, so...
			resource.scenarios = resource.scenarios[rows.to.keep, ]    		# ...drop illegal rows
			
			# Convert the resource scenarios to capacity scenarios, using the 
			# resource2capacity functions of the relevant multiservers. Identify
			# which capacity scenarios comply with fixed points.

			capacity.scenarios = resource.scenarios     					# initialise to required dimensions
			fixed.points.compliant = rep(TRUE, nrow(capacity.scenarios))	# initialise vector of flags
			for (i in 1:ncol(capacity.scenarios)) {
				multiserver.name = used.by[i]
				multiserver = this.sim[[multiserver.name]]
				capacity.scenarios[i] = pmin(
					usage$r2c[[i]](resource.scenarios[[i]]),			# convert resource to capacity
					multiserver$upper.capacity							# but cap it by upper.capacity
				)
				fixed.points.compliant = fixed.points.compliant & 		# update flags for fixed points
					capacity.scenarios[[i]] >= multiserver$fixed.points
			}

			# Eliminate resource and capacity scenarios that fail to provide for 
			# fixed points, UNLESS not even one scenario does this. In that case, 
			# fixed point requirements are relaxed.

			if (any(fixed.points.compliant)) {
				resource.scenarios = resource.scenarios[fixed.points.compliant, ]
				capacity.scenarios = capacity.scenarios[fixed.points.compliant, ]
			}
			### TODO Some sort of warning (but not an R warning() as might generate thousands) if fixed points not met
			
			# Estimate total waiting times on each multiserver in used.by, if that
			# multiserver had a capacity of *one* server. Note that:
			#	- We treat each multiserver as serving only its main.queue
			#	- We exclude time already waited, as that is a sunk cost
			#	- We approximate workload as continuous and txtime as uniform
			#	- We include work in progress on the server in the workload, although
			#	  not technically part of the queuing time. This is more robust in a
			#	  low queue but high throughput scenario.
			
			total.waits.single = rep(0, length(used.by))	# init vector to hold the waits
			names(total.waits.single) = used.by
			for (i in seq(along = used.by)) {
				multiserver.name = used.by[i]
				multiserver = this.sim[[multiserver.name]]
				main.queue = multiserver$main.queue
				jobs.count = main.queue$qtail - main.queue$qhead    # number of jobs currently in main.queue
				if (jobs.count == 0) {       						# handle empty queue separately
					size.in.queue = 0
					work.in.queue = 0
				} else {
					jobs.in.queue = with(main.queue, qdata$job[qhead:(qtail - 1)])	# job indices
					size.in.queue = sum(main.queue$job.size[jobs.in.queue])			# total job.size of jobs in queue
					work.in.queue = sum(multiserver$residual.time[jobs.in.queue])
				}
				size.on.server = multiserver$size.on.server()
				work.on.server = multiserver$work.in.progress()
				clearance.time = 
					work.in.queue + work.on.server	# with a single server, time to clear = work (in ticks)
				mean.wait = 0.5 * clearance.time	# with given approx, mean wait is half the clearance time
				total.size = size.in.queue + size.on.server
				total.wait = total.size * mean.wait  
				total.waits.single[i] = total.wait
					# the total waiting time experienced by 'total.size' units

				# If the total wait exceeds the continuity.threshold, cap the capacity
				# scenarios at the number of discrete jobs in the queue plus on the 
				# multiserver. This is to mitigate the continuous workload assumption 
				# above.
					
				if (total.wait > continuity.threshold) {
					utilisable.capacity = jobs.count + multiserver$jobs.on.server()
					capacity.scenarios[i] = pmin(
						capacity.scenarios[[i]], 
						utilisable.capacity
					)
				}
			}
			
			# Next we apply exponential smoothing to total.waits.single. This is to 
			# prevent the allocation from fluctuating wildly under varying workload,
			# especially when some queues are close to empty. Smoothing can be turned
			# off by setting the $alpha field of the resource to 1.
			
			total.waits.single = alpha * total.waits.single + (1 - alpha) * previous.waits
			
			# Special case for no work waiting on any multiserver: create a dummy wait of
			# one tick so that capacity will be evenly distributed (subject to fixed.points
			# and upper.capacity constraints). Note prop.table below would yield NaNs if
			# all elements remained at zero.
			
			if (all(total.waits.single == 0))
				total.waits.single[] = 1            # set all elements to 1 (tick)

			# Update the stored value of previous.waits.

			# Since the lagged contributions in exponential smoothing never decay entirely 
			# to zero, we would risk keeping a single server open indefinitely for zero work. 
			# To prevent this, we override multiservers in previous.waits to zero if they
			# are responsible for only a "small" proportion (as measured by previous.cutoff,
			# typically 10%, adjustable by user) of the total wait.
			
			previous.waits <<- 
				ifelse(prop.table(total.waits.single) < previous.cutoff, 0, total.waits.single)
	
			# We now scale our waiting time estimates, which so far all relate to a capacity
			# of *one* server, to cover all the capacity scenarios identfied earlier.
			# Waiting times at a multiserver are inversely proportional to its capacity.
	
			wait.scenarios = capacity.scenarios     # initialise to required dimensions
			for (i in 1:ncol(wait.scenarios))
				wait.scenarios[i] = zdivide(total.waits.single[i], capacity.scenarios[[i]])
						
			# Finally we can estimate the total waiting time (taking row sums across all of
			# used.by) for every combination of resource allocations. Hence we pick the optimum.
			
			wait.all.queues = rowSums(wait.scenarios)
			opt.scenario.index = which.min(wait.all.queues)
			
			# We update the 'due' amounts of resource on each of used.by to the new optimum
			
			for (i in seq(along = used.by))
				usage$due[i] <<- resource.scenarios[opt.scenario.index, i]	
		
		} # end of optimisation case

		update.upper.capacity()
		
	}, env) 	# end allocate.min.wait
	
	# ---- which.dominant ---------------------------------------------------------	
	# For a given index row in numeric data frame 'rowdata': which other row of 
	# 'rowdata' most dominates it? This is a helper function for allocate.sla, where
	# it is used to ensure we prefer efficiency over stict minimisation of variance
	# in performance.
	#
	# Row A is said to "dominate" row B iff:
	#	- every element in row A is greater than or equal to the corresponding 
	#	  element in row B; AND 
	#	- at least one such element is *strictly* greater.
	#
	# If more than one row dominates the index row, the one with the highest row
	# sum wins. If two or more have a joint highest row sum, the first wins.
	#
	# The return value is the row index of the winning dominant row, if one was
	# found; or the original row.index, if no row dominated it.

	evalq(which.dominant <- function(rowdata, row.index) {
		
		# Perform initial screening based on row sums
		row.sums = rowSums(rowdata)
		names(row.sums) = NULL										# tidy away irrelevant names
		dominant.candidates = which(row.sums > row.sums[row.index])	# indices in original rowdata
		
		# If any rows passed initial screening, check to see whether any are actually dominant
		if (length(dominant.candidates) == 0) result = row.index
		else {
			candidate.data = rowdata[dominant.candidates, ]
			comparator.data = candidate.data			# initialise comparator to same dimensions
			comparator.data[] = rowdata[row.index, ]	# all rows of comparator become index row
			is.dominant = apply(candidate.data >= comparator.data, MARGIN = 1, FUN = all)
				# Note any candidate rows *identical* to index row were dropped during screening above
			if (any(is.dominant)) {
				dominant.confirmed = dominant.candidates[is.dominant]
				result = dominant.confirmed[which.max(row.sums[dominant.confirmed])]
			} else result = row.index
		}
		return(result)
	}, env)
	
	# ---- allocate.sla -----------------------------------------------------------		
	# Distribute the 'allocated' resource so that it becomes 'due' to the multiservers
	# in used.by such that the projected mean waiting times of each queue fall as 
	# closely as possible to in the ratio of their SLAs. This function is very similar
	# to allocate.min.wait, but its optimisation criterion is based on SLAs rather
	# than total waiting time.
	
	# Constraints on fixed.points and upper.capacity are observed as for allocate.min.wait.
	# The latter is updated to reflect the new allocation.
	
	evalq(allocate.sla <- function() {
	
		# If the resource has only one user then only one allocation is possible, so
		# we skip the bulk of the function that is concerned with optimisation.	
		if (length(used.by) == 1) usage$due[1] <<- allocated
		
		if (length(used.by) > 1) {

			# Construct list of all possible resource allocations for multiservers in 
			# used.by *except* the first, which is initialised to zero. Each other
			# interested multiserver will be given between 0 and 'allocated', incl.

			resource.ranges = list(0)
			for (i in seq(2, length(used.by)))
				resource.ranges[[i]] = seq(0, allocated)
			names(resource.ranges) = used.by
			
			# Obtain all possible combinations of the options identfied above for the 
			# individual multiservers. Then deduce the allocation for the first
			# multiserver (that was neglected above) as the remainder once all other
			# allocations are taken into account.
			
			resource.scenarios = do.call(expand.grid, resource.ranges)        # full factorial 
			resource.scenarios[1] = allocated - rowSums(resource.scenarios)   # first multiserver to take whatever's left...
			rows.to.keep = resource.scenarios[1] >= 0                         # ...but cannot be negative, so...
			resource.scenarios = resource.scenarios[rows.to.keep, ]           # ...drop illegal rows

			# Convert the resource scenarios to capacity scenarios, using the 
			# resource2capacity functions of the relevant multiservers. Identify
			# which capacity scenarios comply with fixed points.

			capacity.scenarios = resource.scenarios     					# initialise to required dimensions
			fixed.points.compliant = rep(TRUE, nrow(capacity.scenarios))	# initialise vector of flags
			for (i in 1:ncol(capacity.scenarios)) {
				multiserver.name = used.by[i]
				multiserver = this.sim[[multiserver.name]]
				capacity.scenarios[i] = pmin(
					usage$r2c[[i]](resource.scenarios[[i]]), 		  # convert resource to capacity
					multiserver$upper.capacity						  # but cap it by upper.capacity
				)
				fixed.points.compliant = fixed.points.compliant & 	  # update flags for fixed points
					capacity.scenarios[[i]] >= multiserver$fixed.points
			}

			# Eliminate resource and capacity scenarios that fail to provide for 
			# fixed points, UNLESS not even one scenario does this. In that case, 
			# fixed point requirements are relaxed.
			
			if (any(fixed.points.compliant)) {
				resource.scenarios = resource.scenarios[fixed.points.compliant, ]
				capacity.scenarios = capacity.scenarios[fixed.points.compliant, ]
			}
			### TODO Some sort of warning (but not an R warning() as might generate thousands) if fixed points not met

			# Estimate mean waiting times on each multiserver in used.by, if that
			# multiserver had a capacity of *one* server. The waiting times are 
			# expressed as a proportion of SLA. Note that:
			#	- We treat each multiserver as serving only its main.queue
			#	- We approximate workload as continuous and txtime as uniform
			#	- We include work in progress on the server in the workload, although
			#	  not technically part of the queuing time. This is more robust in a
			#	  low queue but high throughput scenario.
			
			proportions.sla.single = rep(0, length(used.by))	# init vector to hold the waits
			names(proportions.sla.single) = used.by
			for (i in seq(along = used.by)) {
				multiserver.name = used.by[i]
				multiserver = this.sim[[multiserver.name]]
				main.queue = multiserver$main.queue
				jobs.count = main.queue$qtail - main.queue$qhead    # number of jobs currently in main.queue
				if (jobs.count == 0) {       						# handle empty queue separately
					size.in.queue = 0
					work.in.queue = 0
				} else {
					jobs.in.queue = with(main.queue, qdata$job[qhead:(qtail - 1)])	# job indices
					size.in.queue = sum(main.queue$job.size[jobs.in.queue])			# total job.size of jobs in queue
					work.in.queue = sum(multiserver$residual.time[jobs.in.queue])
				}
				size.on.server = multiserver$size.on.server()
				work.on.server = multiserver$work.in.progress()
				clearance.time = 
					work.in.queue + work.on.server	# with a single server, time to clear = work (in ticks)
				mean.wait = 0.5 * clearance.time	# with given approx, mean wait is half the clearance time
				total.size = size.in.queue + size.on.server
				total.wait = total.size * mean.wait  
				if (main.queue$sla == 0) qqa$error.zero.sla(main.queue)
				proportions.sla.single[i] = mean.wait / main.queue$sla

				# If the total wait exceeds the continuity.threshold, cap the capacity
				# scenarios at the number of discrete jobs in the queue plus on the 
				# multiserver. This is to mitigate the continuous workload assumption 
				# above.
					
				if (total.wait > continuity.threshold) {
					utilisable.capacity = jobs.count + multiserver$jobs.on.server()
					capacity.scenarios[i] = pmin(
						capacity.scenarios[[i]], 
						utilisable.capacity
					)
				}
			}
			
			# Next we apply exponential smoothing to proportions.sla.single. This is to 
			# prevent the allocation from fluctuating wildly under varying workload,
			# especially when some queues are close to empty. Smoothing can be turned
			# off by setting the $alpha field of the resource to 1.
			
			proportions.sla.single = alpha * proportions.sla.single + (1 - alpha) * previous.waits
			
			# Special case for no queued work on any multiserver: create a dummy wait of
			# 0.01 SLA so that capacity will be evenly distributed (subject to fixed.points
			# and upper.capacity constraints). Note prop.table below would yield NaNs if
			# all elements remained at zero.
			
			if (all(proportions.sla.single == 0))
				proportions.sla.single[] = 0.01			# set all elements to 0.01 (SLA)

			# Update the stored value of previous waits.
						
			# Since the lagged contributions in exponential smoothing never decay entirely 
			# to zero, we would risk keeping a single server open indefinitely for zero work. 
			# To prevent this, we override multiservers in previous.waits to zero if they
			# are responsible for only a "small" proportion (as measured by previous.cutoff,
			# typically 10%, adjustable by user) of the total wait.
			
			previous.waits <<- 
				ifelse(prop.table(proportions.sla.single) < previous.cutoff, 0, proportions.sla.single)

			# We now scale our waiting time estimates, which so far all relate to a capacity
			# of *one* server, to cover all the capacity scenarios identfied earlier.
			# Waiting times at a multiserver are inversely proportional to its capacity.

			wait.scenarios = capacity.scenarios     # initialise to required dimensions
			for (i in 1:ncol(wait.scenarios))
				wait.scenarios[i] = zdivide(proportions.sla.single[i], capacity.scenarios[[i]])
						
			# We calculate the variance in waiting time (still expressed as proportion
			# of SLA) across multiservers. For rows (scenarios) where one or more queue
			# is infinite, the 'var' function in R will return NaN. We replace these
			# NaN with Inf, treating these scenarios as having infinite variance.
			
			variance.all.queues = apply(
				wait.scenarios, MARGIN = 1, FUN = var	# MARGIN 1 => row summaries
			)
			variance.all.queues[is.nan(variance.all.queues)] = Inf

			# We pick the optimum scenario as the one which minimises the variance in 
			# proportion of SLA waited, across multiservers. However if this optimum
			# is dominated in capacity (see which.dominant, above) by some other scenario,
			# we pick the dominant scenario instead to become the replacement optimum.
	
			opt.scenario.index = which.min(variance.all.queues)
			opt.scenario.index = which.dominant(capacity.scenarios, opt.scenario.index)
			
			# We update the 'due' amounts of resource on each of used.by to the optimum
			
			for (i in seq(along = used.by))
				usage$due[i] <<- resource.scenarios[opt.scenario.index, i]	
		
		} # end of optimisation case
		
		update.upper.capacity()
		
	}, env)		# end allocate.sla
	
	# Resolve allocator and allocator.name fields of 'env' during execution of make.resource
	env$allocator = env$allocate.sla		# default allocator
	env$allocator.name = "sla"
	
	# ---- set.allocated ----------------------------------------------------------	
	# Naive setter function for 'allocated'. This function only tells the resource 
	# what quantity it is supposed to hold. No clawback or redistribution will take
	# place yet.
	
	evalq(set.allocated <- function(new.allocation) {
		allocated <<- new.allocation
	}, env)

	# ---- calculate.schedule --------------------------------------------------------	
	# Calculate a schedule for this resource based on the capacity.schedule in 'marshal'.
	# The schedule calculated here is more granular than the one in a 'scheduler' object:
	# it contains a column giving the resource quantitiy for every multiserver in used.by,
	# as opposed to an aggregate resource for all of used.by.

	evalq(calculate.schedule <- function() {
		qqa$check.schedule.exists(marshal)		# ensure marshal has a capacity schedule
		times = marshal$capacity.schedule$times
		
		# Initialise 'quantities' data frame to capacities of relevant multiservers (one per
		# column). Then convert each column from a capacity to the corresponding resource 
		# quantity.
		
		quantities = marshal$capacity.schedule[used.by]		
		for(multiserver.name in used.by) {
			multiserver = this.sim[[multiserver.name]]
			c2r = capacity2resource[[multiserver$server.type]]
			if(is.null(c2r)) c2r = identity		# if no bespoke conversion, just use identity
			quantities[multiserver.name] = c2r(quantities[multiserver.name])
		}
		
		this.resource$schedule = data.frame(times = times, quantities)
	}, env)

	# ---- fixed.point.coverage ---------------------------------------------------	
	# How much of this resource is required in total to cover the fixed.points of all
	# multiservers that the resource is used.by?
	
	evalq(fixed.point.coverage <- function() {
		quantity = 0
		for(multiserver.name in used.by) {
			multiserver = this.sim[[multiserver.name]]
			c2r = capacity2resource[[multiserver$server.type]]
			if(is.null(c2r)) c2r = identity		# if no bespoke conversion, just use identity
			quantity = quantity + c2r(multiserver$fixed.points)
		}
		return(quantity)
	}, env)
		
	# ---- release.slack ----------------------------------------------------------	
	# Where applicable, reduce the 'actual' resource on each multiserver in used.by,
	# so that it does not exceed both the amount 'due' AND the amount required to
	# support the current multiserver capacity.
	evalq(release.slack <- function() {
		for (i in seq(along = used.by)) {
			overdue = usage$actual[i] - usage$due[i]
			if (overdue > 0) {
				multiserver.name = used.by[i]
				multiserver = this.sim[[multiserver.name]]
				capacity = multiserver$capacity
				bound.resource = usage$c2r[[i]](capacity)
				usage$actual[i] <<- max(bound.resource, usage$due[i])
			}
		}
	}, env)
	
	# -----------------------------------------------------------------------------	
	# Polling a resource first releases any 'actual' that is slack (see release.slack).
	# It then reclaims from 'total' any excess above what is 'allocated', subject to
	# sufficient slack. Hence, it calculates the size of the free pool available for
	# redistribution to multiservers holding less than their due.
	
	# These multiservers are then visited in turn and topped up, if applicable, until
	# the free pool is exhausted. The capacity of any multiserver that has been topped
	# up is increased accordingly, subject to any constraints imposed by other resources
	# that that multiserver may use.
	
	evalq(poll.me <- function() {
		release.slack()						# update 'actual' to reflect resource no longer needed
		pool = total - sum(usage$actual)    # amount of resource held, but not assigned to a multiserver
		
		# Update 'total' and 'pool' if applicable
		if (total > allocated) {				    # holding more resource than allocated, try to reclaim
			reclaim = min(pool, total - allocated)  # amount to reclaim, subject to availability in pool
			pool = pool - reclaim
			total <<- total - reclaim
		} else {								    # holding same or less resource than allocated
			pool = pool + (allocated - total)		# can increase the total and the pool
			total <<- allocated
		}
		
		# Distribute the pool among any multiservers that are under their 'due' allocation
		for (i in seq(along = used.by)) {
			if (pool == 0) break					# no more pool to distribute
			underdue = usage$due[i] - usage$actual[i]
			if (underdue > 0) {
				topup = min(pool, underdue)
				pool = pool - topup
				usage$actual[i] <<- usage$actual[i] + topup
				multiserver.name = used.by[i]
				marshal$set.resourced.capacity(multiserver.name)
			}
		}
		log.status()
		
		this.sim$allocation.pending = -1	# clear the flag when any resource is polled
			# Note if there is >1 resource, polls raised by the marshal following
			# allocation are contiguous
	}, env)
	
	# -----------------------------------------------------------------------------	
	# Waking up a resource initialises the actual, due, r2c and c2r fields of 'usage'.
	# The 'actual' and 'due' quantities are both set to zero, pending an allocator 
	# being called. The capacity of any multiserver using this resource is overridden
	# to zero.
	
	evalq(wakeup <- function() {
		for(i in seq(along = used.by)) {
			usage$actual[i] <<- 0
			usage$due[i] <<- 0
			multiserver.name = used.by[i]
			multiserver = this.sim[[multiserver.name]]
			multiserver$force.empty()         	# override to zero pending allocation
			r2c = resource2capacity[[multiserver$server.type]]
			c2r = capacity2resource[[multiserver$server.type]]
			if(is.null(r2c)) r2c = identity		# if no bespoke conversion, just use identity
			if(is.null(c2r)) c2r = identity		# if no bespoke conversion, just use identity
			usage$r2c[[i]] <<- r2c
			usage$c2r[[i]] <<- c2r
			
		}
		for (j in seq(along = usage))
			names(usage[[j]]) <<- used.by
	}, env)

	# -----------------------------------------------------------------------------	
	# On shutdown of a resource, trim unused lines from the log.
	
	evalq(shutdown <- function() {
		log <<- log[!is.na(log$tick), ]
	}, env)

	return(env)
}	# end make.resource

# -----------------------------------------------------------------------------
# 'make.scheduler' creates a 'scheduler' object, which causes the quantity of a
# given resource to be adjusted at scheduled times.
#
# ARGUMENTS
# sim.env:				a simulation environment, as created by new.simulation
# for.resource.name:	the name (as text) of the resource to be scheduled
# times:				non-empty vector of times in ticks at which changes take place
# quantities:			vector parallel to 'times' giving quantity of resource at each time
# overwrite:			logical: should we allow overwriting any existing scheduler
#						for the same resource?

make.scheduler = function(
	sim.env, for.resource.name, times, quantities, overwrite = FALSE
) {
	env = new.env(parent = sim.env)
	scheduler.name = paste("scheduler", for.resource.name, sep = ".")
	if (!overwrite)
		qqa$check.duplicate.scheduler(sim.env, scheduler.name)
	sim.env[[scheduler.name]] = env
	env$this.scheduler = env
	env$for.resource.name = for.resource.name
	env$times = times             # must have at least one time
	env$quantities = quantities   # vector of same length as times
	env$resource = "placeholder"  # to be resolved at wakeup based on for.resource.name
	env$time.index = 1
	qqa$check.scheduler(env)
	
	# -----------------------------------------------------------------------------	
	# Polling a scheduler sets the associated resource to its next quantity.
	# Actual allocation will take place on the next poll of the marshal.

	evalq(poll.me <- function() {
		resource$set.allocated(quantities[time.index])
		time.index <<- time.index + 1
		if (time.index <= length(times)) {
			clock$poll.at(this.scheduler, times[time.index])
			clock$poll.at.unique(marshal, times[time.index])    
			# 'unique' prevents duplicate polls of marshal, and ensures marshalling
			# always occurs immediately *after* scheduling, even though at same tick.
		}
	}, env)

	# -----------------------------------------------------------------------------	
	# Waking a scheduler resolves for.resource.name, and raises poll events for this
	# scheduler and for the marshal at the first time in in 'times'.
	# If the first time is zero ticks, the allocation pending flag is set.
	
	evalq(wakeup <- function() {
		resource <<- this.sim[[for.resource.name]]
		clock$poll.at(this.scheduler, times[1])
		clock$poll.at.unique(marshal, times[1])
		if (times[1] == 0) this.sim$allocation.pending = 0
	}, env)
	
	return(env)
}	# end make.scheduler

# -----------------------------------------------------------------------------
# 'make.intervener' creates an 'intervener' object, which runs a function specified
# by the user at a given time. This can be used to apply bespoke logic to the 
# simulation while it is in flight.
#
# ARGUMENTS
# sim.env:				a simulation environment, as created by new.simulation
# intervener.name		a textual name for this intervener
# at.time:				the first time, in ticks, at which to run 'intervention'
# intervention:			an R function of no args, to run at the given time
#
# The intervention function by default is only run once, at 'at.time'. However, it
# can ask the clock to poll its intervener at at a future tick, at which point it 
# will be run again. If using this feature to set a recurring intervener, be sure to
# stop it asking for more poll events at some time limit. Otherwise, the simulation
# will never terminate.

make.intervener = function(
	sim.env, intervener.name, at.time, intervention
) {
	env = new.env(parent = sim.env)
	sim.env[[intervener.name]] = env
	env$this.intervener = env
	env$intervener.name = intervener.name
	env$first.time = at.time
	env$intervention = intervention
	stopifnot(typeof(intervention) == "closure")
	
	# polling an intervener causes its 'intervention' function to be run
	evalq(poll.me <- function() {
		intervention()
	}, env)
	
	# upon wakeup, arrange for the intervener to be polled at the given time
	evalq(wakeup <- function() {
		clock$poll.at(this.intervener, first.time)
	}, env)

	return(env)
}	# end 'make.intervener'

# -----------------------------------------------------------------------------
# 'make.marshal' creates a 'marshal' object, which is responsible for coordinating
# the activities of resources and multiservers. Each simulation has exactly one
# marshal. However if multiserver capacity is fixed from the outset (not subject to
# resources), the marshal's methods simply leave it unchanged.
#
# The marshal is polled every 'interval' ticks while the simulation is running. It 
# is also polled in response to each scheduler event. When polled, it will call
# the $allocator function on each resource, to distribute resources dynamically.
#
# A zero 'interval' has a special meaning: do not poll the marshal regularly, but
# only in response to scheduler events.
#
# ARGUMENTS
# sim.env:		a simulation environment, as created by new.simulation
# interval:		the number of ticks between (dynamic) allocations of resources
#				(unless zero, see above)

make.marshal = function(sim.env, interval = 5) {
	env = new.env(parent = sim.env)
	sim.env[["marshal"]] = env
	env$this.marshal = env
	env$interval = interval				# number of ticks between regular polls
	env$next.tick = 0					# clock tick when next interval-driven poll is due
	env$managed.multiservers = list()	# list of multiservers whose resources need to be marshalled;
										# resolved at wakeup
	
	# ---- set.interval -----------------------------------------------------------	
	# Set a new interval (in ticks) between allocations. This will take effect
	# after the next poll.
	
	evalq(set.interval <- function(new.interval) {
		interval <<- new.interval
	}, env)
	
	# ---- receive.capacity -------------------------------------------------------	
	# Receive notice that the given multiserver has released some capacity, as a
	# result of an earlier clawback demand. Raise poll events on all interested
	# resources.
	
	evalq(receive.capacity <- function(multiserver) {
		for (resource in resources)
			if (resource$is.used.by(multiserver))
				clock$poll.after(resource, 0)
	}, env)
	
	# ---- clawback ---------------------------------------------------------------	
	# Identify any managed multiservers whose current capacity exceeds their
	# upper.capacity. Tell any such to reduce it to upper.capacity.
	evalq(clawback <- function() {
		for (ms in managed.multiservers) {
			old.capacity = ms$capacity
			upper.capacity = ms$upper.capacity
			if (old.capacity > upper.capacity) {
				ms$set.future.capacity(upper.capacity)
			}
		}
	}, env)
	
	# ---- set.resourced.capacity -------------------------------------------------
	# Set the capacity of the given multiserver according to whichever of the 
	# resources it uses is binding, respecting upper.capacity. The 'actuals' are
	# used to determine the level of each resource.
	
	evalq(set.resourced.capacity <- function(multiserver.name) {
		new.capacity = Inf
		for (resource in resources) {
			if (multiserver.name %in% resource$used.by) {
				actual.quantity = resource$usage$actual[multiserver.name]
				new.capacity = min(new.capacity, 
					resource$usage$r2c[[multiserver.name]](actual.quantity)
				)
			}
		}
		stopifnot(is.finite(new.capacity))
		this.sim[[multiserver.name]]$set.future.capacity(new.capacity)	# respects upper.capacity
	}, env)
	
	# ---- init.capacity.schedule -------------------------------------------------	
	# Initialise and return a capacity schedule based on the max.capacity (if finite)
	# or current capacity (otherwise) of each multiserver that uses resources. The
	# capacity.schedule will be a data frame in which the first column is given by
	# 'times' and the subsequent columns are named for the relevant multiservers.
	# The schedule does not become effective until the 'impose' function is called.
	
	evalq(init.capacity.schedule <- function(times) {
		schedule = data.frame(times = times)
		
		# Get names of all multiservers that use one or more resources
		resource.users = character(0)
		for (resource in resources)
			resource.users = union(resource.users, resource$used.by)
		
		# Create a column in the capacity schedule for each multiserver, skipping
		# those that use no resources
		for (ms in multiservers) {
			if (!(ms$multiserver.name %in% resource.users)) next
			capacity = 
				if (is.finite(ms$max.capacity)) ms$max.capacity 
				else ms$capacity
			schedule[ms$multiserver.name] = capacity
		}
		
		this.marshal$capacity.schedule = schedule
		return(schedule)
	}, env)

	# ---- impose.capacity.schedule -----------------------------------------------	
	# Create or update the schedules on all resources to reflect the current
	# capacity.schedule. Any existing resource schedulers in the simulation will be
	# replaced with new ones, reflecting the updated capacity schedule. The allocators
	# on all resources will be set to allocate.scheduled, and interval-driven 
	# marshalling will be suppressed. If applicable, the max.capacity fields of
	# multiservers will be increased to accommodate the new schedule.

	evalq(impose.capacity.schedule <- function() {
	
		qqa$check.capacity.schedule(this.marshal)
	
		# Iterate over capacity columns of the schedule and override max.capacity where required
		# Omit i = 1, as that is the 'times' column
		for (i in seq(length = ncol(capacity.schedule))[-1]) {
			multiserver.name = colnames(capacity.schedule)[i]
			multiserver = this.sim[[multiserver.name]]
			override.max = max(multiserver$max.capacity, capacity.schedule[[i]])
			multiserver$max.capacity = override.max
		}
		
		# Create new schedulers and set the allocator function for all resources
		for (resource in resources) {
			resource$calculate.schedule()	# how much resource achieves required capacity?
			resource$allocator = resource$allocate.scheduled
			resource$allocator.name = "scheduled"
			times = resource$schedule$times
			quantities = rowSums(resource$schedule[-1])		# omit the 'times' column from row sums
			make.scheduler(this.sim, 
				resource$resource.name, times, quantities, overwrite = TRUE
			)
		}
		
		# Suppress interval-driven allocations: only poll marshal at times in schedulers
		set.interval(0)
	}, env)
	
	# ---- set.capacity.schedule --------------------------------------------------	
	# Set a capacity.schedule field on this marshal to the given schedule, and 'impose'
	# it (see comments for 'impose' above). The schedule argument must be a data frame,
	# whose first column is 'times'. It must have a further column for every multiserver
	# that uses resources, whose column name is the name of the multiserver, and whose
	# values are the capacities of that multiserver at the given 'times'.

	evalq(set.capacity.schedule <- function(schedule) {
		this.marshal$capacity.schedule = schedule
		impose.capacity.schedule()
	}, env)
	
	# ---- set.allocation.method --------------------------------------------------	
	# Set the allocation method to one of "scheduled", "sla" or "wait"
	# For "scheduled", a capacity schedule must already exist, and any existing resource
	# schedulers will be removed as per impose.capacity.schedule. For the other two,
	# any existing schedulers are kept, and polling is set to the given interval.
	
	evalq(set.allocation.method <- function(method, new.interval = 0) {
		qqa$check.allocation.method(method, new.interval)
		if (method == "scheduled") {
			qqa$check.schedule.exists(this.marshal)
			impose.capacity.schedule()
		} else {
			for (resource in resources) {
				if (method == "sla") {
					resource$allocator = resource$allocate.sla
					resource$allocator.name = "sla"
				} else {
					resource$allocator = resource$allocate.min.wait
					resource$allocator.name = "wait"
				}
			}
			set.interval(new.interval)
		}
	}, env)
	
	# -----------------------------------------------------------------------------	
	# Polling the marshal allocates all resources in the simulation.
	# If appropriate, it then raises a new poll event for a future tick.
	
	evalq(poll.me <- function() {
		
		simulation.active = clock$has.events()

		# Initialise the upper.capacity to max.capacity on all managed multiservers.
		# It will potentially be reduced each time a resource is allocated, so its 
		# final value is equal to the capacity attainable with whichever resource is
		# binding.
		
		lapply(managed.multiservers, function(ms) ms$upper.capacity = ms$max.capacity)
		lapply(resources, function(resource) resource$allocator())
		
		# Claw back capacity from any multiservers now holding more than the 'due'
		# resource allocation will support. Raise poll events on all resources to 
		# redistribute them as per new allocation.
		
		clawback()
		lapply(resources, function(resource) clock$poll.after(resource, 0))
	
		# If there is at least one resource, set the allocation.pending flag
		if (length(resources) >= 1) this.sim$allocation.pending = clock$ticks
	
		# If the clock has reached the specified 'next.tick', raise a new poll event
		# for one 'interval' hence. Otherwise, this is deemed to be an off-interval
		# poll (e.g. instigated by a scheduler) and no extra poll event is needed.
		# For the case interval == 0, there is no interval-driven polling, so no event
		# is raised. Likewise, if the simulation is deemed no longer active.
		
		if (clock$ticks == next.tick && interval != 0 && simulation.active) {
			next.tick <<- next.tick + interval
			clock$poll.at.unique(this.marshal, next.tick)
		}
	}, env)
	
	# -----------------------------------------------------------------------------	
	# Waking up the marshal resolves managed.multiservers, and raises a poll event
	
	evalq(wakeup <- function() {
	
		# Determine which multiservers use resources, and therefore need managing by marshal
		multiserver.names = character(0)
		for (resource in resources)
			multiserver.names = union(multiserver.names, resource$used.by)
		managed.multiservers <<- lapply(multiserver.names, function(msn) this.sim[[msn]])
		names(managed.multiservers) <<- multiserver.names
	
		# Raise poll event, unless no interval-driven polling
		if (interval != 0) {
			clock$poll.at.unique(this.marshal, 0)
			# 'unique' ensures that if there are scheduler(s) also polled at time zero,
			# and objects wake up in a non-deterministic order, the marshal is polled
			# just once, and this occurs *after* all the schedulers are polled.
			
			# If there is at least one resource, set the allocation pending flag
			if (length(resources) >= 1) this.sim$allocation.pending = 0
		}
	}, env)

	return(env)
}	# end make.marshal

# -----------------------------------------------------------------------------
# 'make.clock' creates the simulation's 'clock' object, which is responsible for
# maintaining the event list and polling each simulation object at the appropriate
# times. Time is measured in 'ticks' which typically might correspond to minutes,
# but could be any time unit. Ticks do not have to be integers.

make.clock = function(sim.env) {
	env = new.env(parent = sim.env)
	sim.env[["clock"]] = env
	env$ticks = 0             # elapsed simulation time
	env$when = numeric(0)     # vector of future times at which to poll objects
	env$what = list()         # list of objects to poll at times given in 'when'
	
	# ---- has.events -------------------------------------------------------------	
	# Are there still objects waiting to be polled? If FALSE, simulation has ended.
	
	evalq(has.events <- function() {
		length(when) > 0
	}, env)

	# ---- poll.at ----------------------------------------------------------------	
	# 'poll.at' arranges to poll the given object at some future time(s)
	#
	# ARGUMENTS
	# what.to.poll:	 	object to poll (an environment, can't be vectorised)
	# at.what.times:	vector of time(s), in ticks, at which to poll that object
	
	evalq(poll.at <- function(what.to.poll, at.what.times) {
	if (length(at.what.times) == 1) {            	# efficient path for scalar case
			when <<- c(when, at.what.times)
			what <<- c(what, what.to.poll)
		} else {                                    # general vector case
			at.what.times = unique(at.what.times)   # merge coincident poll events
			poll.count = length(at.what.times)
			when <<- c(when, at.what.times)
			for (i in seq(length = poll.count))
				what <<- c(what, what.to.poll)
		}
	}, env)
	
	# ---- poll.after -------------------------------------------------------------	
	# 'poll.after' is essentially the same as 'poll.at', but with time(s) expressed
	# relative to the current tick.
	#
	# ARGUMENTS
	# what.to.poll:		object to poll (an environment, can't be vectorised)
	# after.how.long:	vector of elapsed time(s) after current tick at which to poll that object
	
	evalq(poll.after <- function(what.to.poll, after.how.long = 0) {
		if (length(after.how.long) == 1) {            # efficient path for scalar case
			when <<- c(when, ticks + after.how.long)
			what <<- c(what, what.to.poll)
		} else {                                      # general vector case
			after.how.long = unique(after.how.long)   # merge coincident poll events
			poll.count = length(after.how.long)
			when <<- c(when, ticks + after.how.long)
			for (i in seq(length = poll.count))
				what <<- c(what, what.to.poll)
		}
	}, env)
	
	# ---- unpoll.at --------------------------------------------------------------	
	# 'unpoll.at' cancels any outstanding poll events for the given object and time.
	# If the object has no events scheduled for that time, this has no effect.
	#
	# ARGUMENTS
	# what.to.cancel:	object whose poll event(s) are to be cancelled
	# at.what.time:		scalar time of event(s) to be cancelled; events scheduled for
	#                   other times are unaffected.
	
	evalq(unpoll.at <- function(what.to.cancel, at.what.time) {
		
		if (length(when) > 0) {
		
			# We form boolean vectors of where the object and time, respectively,
			# match those currently on the list. Then we drop events that match
			# on *both*.
			obj.match = sapply(what, function(obj) identical(obj, what.to.cancel))
			time.match = (when == at.what.time)
			indices.to.cancel = which(obj.match & time.match)
			if (length(indices.to.cancel) > 0) {
				when <<- when[-indices.to.cancel]
				what <<- what[-indices.to.cancel]
			}
		}
	}, env)
	
	# -----------------------------------------------------------------------------	
	### DEPRECATED
	# 'unpoll' cancels any outstanding poll events regardless of time for the given object.
	evalq(unpoll <- function(obj.to.cancel) {
		if (length(what) > 0) {
			obj.match = sapply(what, function(obj) identical(obj, obj.to.cancel))
			if (any(obj.match)) {
				when <<- when[!obj.match]
				what <<- what[!obj.match]
			}
		}
	}, env)
	
	# ---- poll.at.unique ---------------------------------------------------------	
	# 'poll.at.unique' first cancels any existing poll events for the given object
	# and time. It then schedules a single new poll event at that time. This event
	# will occur *after* the events for any other objects on the list which are
	# already scheduled for a "simultaneous" time.
	#
	# ARGUMENTS
	# what.to.poll:		object to poll (an environment)
	# at.what.time:		a single scalar time at which to poll the object
	
	evalq(poll.at.unique <- function(what.to.poll, at.what.time) {
		unpoll.at(what.to.poll, at.what.time)
		poll.at(what.to.poll, at.what.time)
	}, env)
	
	# ---- run --------------------------------------------------------------------	
	# Start the clock. Requires other simulation objects to be present and woken up first.
	# The clock, and therefore the simulation, will run until no more objects wish to be
	# polled, unless an error or special intervention terminates it early.
	
	evalq(run <- function() {
		while (length(when) > 0) {
			poll.index = which.min(when)	# soonest due event becomes current event
											# in case of ties, earliest added event wins
			ticks <<- when[poll.index]		# advance the clock to current event
			obj = what[[poll.index]]		# identify object to poll
			when <<- when[-poll.index]		# remove time from event queue
			what <<- what[-poll.index]		# remove object from event queue
			obj$poll.me()					# poll it
		}
	}, env)
	
	return(env)
}	# end make.clock

# =============================================================================
#	Standalone top level functions
# =============================================================================

# -----------------------------------------------------------------------------
# 'group.jobs' takes a data.frame in the format expected by sim$db and groups jobs
# such that the approximate total txtime of the group is the given group.txtime.
# This trades off accuracy for speed when running the simulation.
# A job.size column will be added. Any pre-existing job.size is ignored.

group.jobs = function(jobdata, txtime.colname, group.duration) {
	stopifnot(txtime.colname %in% colnames(jobdata))
	stopifnot("arrival.time" %in% colnames(jobdata))
	
	# Group jobdata by jobs with identical routes.
	# We paste the values in the ".route" columns together, and then sort
	# on the resulting combined 'routing' followed by arrival time.
	
	route.colnames = grep("\\.route$", colnames(jobdata), value = TRUE)
	jobdata$routing = factor(
		apply(
			jobdata[route.colnames], 
			MARGIN = 1, 
			FUN = paste, 
			collapse = "-"
	))
	jobdata = jobdata[order(jobdata$routing, jobdata$arrival.time), ]
	
	# Group again into bins whose total txtime is approximately group.txtime
	# A small number of bins will (harmlessly) span different routings
	
	cum.txtime = cumsum(jobdata[[txtime.colname]])
	jobdata$cum.txtime.bin = cum.txtime %/% group.duration
	
	# Aggregate jobdata by routing and bin:
	# 	for txtimes, we take the sum
	#	for arrival times and routes, we take the first: head(x, 1)
	#	for job.size, we take the count: length(x)
	
	all.txtime.colnames = grep("\\.txtime$", colnames(jobdata), value = TRUE)
	grouping.data = jobdata[c("routing", "cum.txtime.bin")]
	grouped.txtimes = aggregate(jobdata[all.txtime.colnames], by = grouping.data, FUN = sum)
	grouped.routes = aggregate(jobdata[c("arrival.time", route.colnames)], by = grouping.data, FUN = head, 1)
	grouped.sizes = aggregate(jobdata["arrival.time"], by = grouping.data, FUN = length)
	names(grouped.sizes) = sub("arrival.time", "job.size", names(grouped.sizes))

	# Combine aggregated data and sort back into order of arrival time
	# Drop the grouping columns (1:2)
	
	result = cbind(grouped.sizes[-(1:2)], grouped.routes[-(1:2)])
	result = cbind(result, grouped.txtimes[-(1:2)])
	result = result[order(result$arrival.time), ]
	return(result)
}

# -----------------------------------------------------------------------------	
# 'deep.copy' returns an independent copy of an R object (e.g. a simulation 
# environment). Modifications made to the copy will not affect the original. 
#
# Assignment in R is usually by value, not by reference, so for the most part, 
# simple assigment (using '=' or '<-') automatically implies copying. Special 
# treatment must be given to 'environments', however, since these *are* passed by 
# reference. This function therefore makes a recursive copy of all the elements 
# in an environment, or in a list (as lists may contain environments).
#
# To prevent duplicates where the same environment is referenced multiple times, 
# this function maintains a map of environments already encountered to copies 
# already made. This also guards against circular references, i.e. where an 
# environment references itself.
#
# When the object to be copied is a function, its environment (if present in the map)
# is updated in the copy.

deep.copy = function(obj, env.map = new.env()) {
	
	# Initialise env.map, if currently empty
	if (length(env.map) == 0) {
		env.map$env.old = list()
		env.map$env.new = list()
	}

	# helper function to look up an environment in the map; returns NULL if not found
	mapped.env = function(e) {
		e.match = sapply(env.map$env.old, function(x) identical(e, x))
		if (any(e.match)) result = env.map$env.new[[which(e.match)]]
		else result = NULL
		return(result)
	}
	
	# If obj is an environment
	# 	- if it's already in the map, simply return the mapped copy
	#	- otherwise make a new copy, and store it in the map
	if (is.environment(obj)) {
		obj.parent = parent.env(obj)
		obj.mapped = mapped.env(obj)
		if (!is.null(obj.mapped)) result = obj.mapped
		else {
			# obj not in map, so create a new environment and copy obj into it
			# if the parent of obj is in the map, this will supply the parent of the copy
			parent.mapped = mapped.env(obj.parent)
			if (is.null(parent.mapped)) result = new.env()
			else result = new.env(parent = parent.mapped)
			
			# Add mapping from 'obj' to 'result'
			env.map$env.old = append(env.map$env.old, obj)
			env.map$env.new = append(env.map$env.new, result)
			
			# Recursively copy the elements of obj
			for (elt.name in names(obj))
				result[[elt.name]] = deep.copy(obj[[elt.name]], env.map)
		}
		
	# Lists are simpler as cannot involve circular references
	# We simply deep copy all elements of the list recursively (in case some are env's)
	} else if (is.list(obj) && !is.data.frame(obj)) {
		result = list()
		for (i in seq(length = length(obj)))
			result[[i]] = deep.copy(obj[[i]], env.map)
		names(result) = names(obj)
	
	# For functions, we map the function's environment, but only if already in env.map
	} else if (is.function(obj)) {
		obj.env = mapped.env(environment(obj))
		if (!is.null(obj.env)) environment(obj) = obj.env	# note this does NOT change the caller's obj
		result = obj
		
	# Other R objects neither are, nor contain, environments and are simply returned by value
	} else result = obj
	return(result)
}

