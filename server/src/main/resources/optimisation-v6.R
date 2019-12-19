# Branch and bound optimisation
# -----------------------------

# Changes in v6 26/08/2015:

# rolling.fair.xmax upgraded so that it takes into account the backlog
# of work between successive windows. See comments in its definition.
# The original version, for greater compatibility with live DRT, has been
# renamed legacy.rolling.fair.xmax

# Changes in v5 19/05/2015:

# Removed rolling.fair.xmin (was deprecated in previous version)

# Bug fixes in fair.xmax:

# Prevented it from returning a vector (should always return scalar) for
# case where xmin is a vector and initial guess <= some xmin

# Prevented it from reducing xmax to zero when there is finite work (which
# resulted in no breach being detected, as there's only an excess wait
# once you process a passenger!).

# Changes in v4 12/05/2015:

# Backported rolling.fair.xmax from live DRT.
# Backported functions to handle the case where a user supplies an xmin
# of zero, to avoid perverse situation where no desks are open and passengers
# have to wait until the next slot.

# Changes in v3 03/03/2015:

# Backported vectorised xmax & xmin
# Backported extrapolation (in the cost function) beyond the time slot being optimised

# Removed recording of "wait" from process.work as it plays no role in
# optimisation. This is a stripped down version to feed the optimiser
# and needs to run as fast as possible. To model detailed queuing stats,
# use the QSci code which outputs a QStats object containing proper waiting
# times.

# v2 03/02/2014 with minor modifications:
# Bug fixes in running.avg and block.mean - previous version incorporated
# unnecessary, and undocumented, rounding of the inputs.

# v1 04/09/2013
# This was based on the final version received back from JZ, with
# AH modifications


running.avg = function(x, npoint)
# -------------------------------
# Apply an asymmetric npoint running average to x
# Each output value is the mean of the preceding npoint input values, inclusive
# The first npoint values are all set to their common mean, to avoid NAs
# If x is too short for npoint, all output values are set to the common mean
# Updated in v3 to handle the case of zero length x
{
    if (length(x) == 0) return(x)
    npoint = min(npoint, length(x))
    y = as.numeric(filter(x, rep(1 / npoint, npoint), method = "convolution", sides = 1))
    y[1 : npoint] = mean(x[1 : npoint])
    return(y)
}


block.mean = function(x, n)
# -------------------------
# Divide x into blocks of size n and return a vector the same
# length as x, where each element is the mean of the block
{
    block.number = rep(1 : length(x), each = n, length.out = length(x))
    ave(x, block.number, FUN = mean)
}


block.max = function(x, n)
# ------------------------
# Divide x into blocks of size n and return a vector the same
# length as x, where each element is the max of the block
{
    block.number = rep(1 : length(x), each = n, length.out = length(x))
    ave(x, block.number, FUN = max)
}


process.work = function(work, capacity, sla, qstart)
# -------------------------------------------------

# This is based on the simul function from ContES.R
# Simulate a simple queue by continuous event simulation

# Inputs:
# work - vector of quantity of workload arriving for some series of time slots
# (typically 1 slot = 1 minute)
# capacity - vector of available service capacity in the same time slots
# sla - service level agreement in time slots (typically minutes)
# qstart - vector of work in pre-existing queue by ascending age; typically,
#    the "residual" from a previous call to this function

# Returns a list with elements:
# wait - vector of waiting times for the oldest work in the queue at end of each slot
#    Note this just gives a snapshot per slot and is unweighted, so should NOT be
#    averaged to get an average queuing time.
# util - vector of utilisation of service capacity each slot
# residual - vector giving the remaining queue at end of final slot
# total.wait - the total waiting time, in units of work times slots waited
# excess.wait - that part of total.weight where the waiting time exceeded sla
# (the "wait" vector has been removed in v3)
{
    stopifnot(length(work) == length(capacity))
    if (length(work) == 0)# handle the empty case (bug fix in v3 to return list as specified)
    return(list(util = numeric(0), wait = integer(0), residual = qstart, total.wait = 0, excess.wait = 0))

    wait = work
    q = qstart                   # vector representing queued workload
    wait = integer(length(work)) # vector to hold waiting times
    util = numeric(length(work)) # vector to hold utilisation rates
    total.wait = 0      # waiting time, in workload-minutes
    excess.wait = 0     # amount of total.wait where time greater than sla, in workload-minutes

    for (t in 1 : length(work))
    {
        q = c(work[t], q)         # add workload arriving in current time slot to queue

        resource = capacity[t]    # resource available in the current time slot
        age = length(q)           # oldest work on the queue, where age 1 is new work this slot
        while (age > 0)
        {
            surplus = resource - q[age]   # how much resource would be left after processing oldest work
            if (surplus >= 0)# can process oldest work completely
            {
                total.wait = total.wait + q[age] * (age - 1)
                if (age - 1 >= sla)excess.wait = excess.wait + q[age] * (age - 1)
                q = q[- age]         # remove oldest work from queue
                resource = surplus  # update remaining resource available this slot
            }
            else
            {
                total.wait = total.wait + resource * (age - 1)
                if (age - 1 >= sla)excess.wait = excess.wait + resource * (age - 1)
                q[age] = q[age] - resource   # process as much of the oldest work as possible
                resource = 0                 # no more resource left in this time slot, so...
                break                        # ...terminate while loop - can process no more of the queue
            }
            age = age - 1           # loop over more recent work in queue
        }
        wait[t] = length(q)        # how long the oldest work in the queue has been waiting so far
        util[t] = 1 - resource / capacity[t]   # proportion of capacity that was used this slot
    }
    util[! is.finite(util)] = 0    # replace NaNs by 0 (where was zero capacity)

    return(list(util = util, wait = wait, residual = q, total.wait = total.wait, excess.wait = excess.wait))
}

### Note total.wait needs to be divided by workload per passenger if it is to be
### interpreted as total passenger-minutes lost.


churn = function(churn.start, x)
# -----------------
# sum all the positive differences of x, with the convention x[0] = churn.start
{
    x.lag = c(churn.start, x)
    x = c(x, - Inf)
    d = x - x.lag
    d = d[d > 0]
    sum(d)
}


cost = function(work, capacity, sla, weight.pax, weight.staff, weight.churn, weight.sla, overrun = NULL, qstart, churn.start)
# --------------------------------------------

# Evaluate a cost function for processing a queue, with the given weights.
# In the event of a residual queue at the end, the capacity is assumed
# to be maintained at its final value to clear the backlog, UNLESS final
# capacity was zero in which case it is assumed one desk is (re)opened to
# clear the backlog.

# Passenger waiting time is counted for all the passengers in "work",
# and for any in qstart (the latter not documented prior to v3).
# Staff idle time is only counted for the time span corresponding to the
# capacity vector. Churn is counted throughout.

# From v3, the overrun parameter is IGNORED. Overrun is now calculated
# (with slight approximation) by extrapolation instead of by simulating
# an overrun buffer explicitly. This avoids the problem where a buffer overrun
# would cause an assertion failure.

# The null case (empty work vector) returns costs all zero even if there
# is work in qstart.

{
    # Handle the null case
    if (length(work) == 0) return(list(pax = 0, sla.p = 0, staff = 0, churn = 0, total = 0))

    # Run simulation for the time period covered by work and capacity
    simres = process.work(work, capacity, sla, qstart)

    # If there is a residual queue, estimate additional waiting times
    # by extrapolation

    final.capacity = tail(capacity, 1)
    backlog = rev(simres$residual)  # the residual queue, reversed so in the order work is processed
    total.backlog = sum(backlog)    # total amount of work in the residual queue
    if (total.backlog > 0)
    # Calculate the additional passenger waiting time due to the residual queue
    {
        final.capacity = max(final.capacity, 1)
        # if capacity in last slot was zero, assume 1 desk opened to process residual queue
        # otherwise, cost would be infinite
        cum.backlog = cumsum(backlog)
        cum.capacity = seq(from = 0, by = final.capacity, length = ceiling(total.backlog / final.capacity) + 1)
        overrun.slots = (1 : length(cum.capacity)) - 1
        # time slots at which overrun capacity becomes available
        backlog.boundaries = approx(cum.capacity, overrun.slots, cum.backlog, rule = 2)$y
        # the times (in slots) taken to clear successive elements of backlog
        # by linear interpolation using the R "approx" function
        # rule = 2 so robust to any rounding errors at the boundaries
        start.slots = c(0, floor(backlog.boundaries[- length(backlog.boundaries)]))
        # the times in completed slots to start clearing successive elements of backlog
        end.slots = floor(backlog.boundaries)
        # the times in completed slots to finish clearing successive elements of backlog
        already.waited = rev(seq_along(backlog))
        # the times (in slots) backlog elements had already waited as of first slot of overrun
        mean.waits = already.waited + (start.slots + end.slots) / 2
        excess.filter = mean.waits > sla
        # identify those work elements that waited (on average) longer than sla
        # this is an approximation for those elements where some pax waited longer and
        # some didn't (sometimes not a very good approximation!)
        # Add waiting times due to backlog to previous running totals from simulation
        simres$total.wait = simres$total.wait + sum(backlog * mean.waits)
        # if (ceiling(simres$total.wait) != simres$total.wait) print(sprintf("got %f", simres$total.wait))

        simres$excess.wait = simres$excess.wait + sum(backlog[excess.filter] * mean.waits[excess.filter])
    }

    pax.penalty = simres$total.wait
    sla.penalty = simres$excess.wait
    staff.penalty = sum((1 - simres$util[1 : length(capacity)]) * capacity)

    churn.penalty = churn(churn.start, c(capacity, final.capacity))
    # concatenate final capacity for churn calculation in case a desk was re-opened for backlog
    total.penalty = weight.pax * pax.penalty +
        weight.staff * staff.penalty +
        weight.churn * churn.penalty +
        weight.sla * sla.penalty

    # print(sprintf("%f * %f + %f * %f + %f * %f + %f * %f = %f", weight.pax, pax.penalty, weight.staff, staff.penalty, weight.churn, churn.penalty, weight.sla, sla.penalty, total.penalty))

    return(list(pax = pax.penalty, sla.p = sla.penalty, staff = staff.penalty, churn = churn.penalty, total = total.penalty))
}


neighbouring.points = function(x0, xmin, xmax)
# --------------------------------------------
# return vector of integers between xmin and xmax in order of distance from x0
# excluding x0 itself; takes scalar arguments only
{
    x = xmin : xmax
    x = x[(x != x0)]
    x[order(abs(x - x0))]
}


branch.bound = function(starting.x, objective.func, xmin, xmax, concavity.limit)
# ----------------------------------
# Estimate the (vector) x which minimises objective.func(x),
# subject to constraints xmin and xmax, and integer x.
# As of v3, xmin and xmax may be either scalars or vectors of same length as x.

# A modified branch and bound algorithm is used, and is suited to the situation
# where the x[i] form a time series, and values of x having "small" separation
# in Euclidean space tend to have only "small" differences in the objective
# function.

# The algorithm is designed to be used when no suitable bounding function is
# available. Instead, subtrees are discarded if the objective function at the
# initial node exceeds the best feasible solution so far by more than
# the concavity.limit parameter. A concavity.limit of Inf would correspond to
# an exhaustive search and would take exponential time; a concavity.limit of
# zero would dispose of unpromising subtrees rather quickly, and would in fact
# sometimes miss minima even for a convex objective.func.

# Branching is on x[1] first, then on x[2], etc.
# A further modification to standard branch & bound is that for each x[1:k]
# considered, the best solution found so far in previous subtrees for x[(k+1):n]
# is used as the starting value. The rationale is that "quite good" values of
# downstream x-values are likely to remain "quite good" under small modifications
# upstream.

{
    x = incumbent = starting.x  # starting estimate, to be optimised
    n = length(x)               # number of x[i] to be varied
    xmin[1 : n] = xmin        # recycle xmin if it's a scalar
    xmax[1 : n] = xmax        # ditto xmax
    best.so.far = objective.func(incumbent)
    candidates = list()
    # list to be populated with feasible x[i] to be explored, for each i
    for (i in 1 : n)
    candidates[[i]] = neighbouring.points(starting.x[i], xmin[i], xmax[i])

    cursor = n             # index into the x vector
    while (cursor > 0)# once cursor under-runs, all subtrees have been explored, so stop
    {
        while (length(candidates[[cursor]]) > 0)# any more candidates at current cursor position?
        {
            x[cursor] = candidates[[cursor]][1]    # pop the next candidate from the list
            candidates[[cursor]] = candidates[[cursor]][- 1]
            # cat(x, "\n")
            trial.z = objective.func(x)

            if (trial.z > best.so.far + concavity.limit)### * (n - cursor + 1))
            {
                # current x is so bad it is not worth exploring its subtree any further
                # so discard all candidates more extreme than the current one
                if (x[cursor] > incumbent[cursor])
                candidates[[cursor]] = candidates[[cursor]][candidates[[cursor]] < x[cursor]]
                else
                candidates[[cursor]] = candidates[[cursor]][candidates[[cursor]] > x[cursor]]
                next   # proceed to next iteration of "while" loop over candidates
            }
            if (trial.z < best.so.far)
            {
                # current solution is better than the previous best, so update
                incumbent = x
                best.so.far = trial.z
            }
            if (cursor < n)cursor = cursor + 1   # subtree requires exploration, so move cursor downstream
        }
        # When inner while loop terminates, all candidates downstream of cursor have been explored,
        # so reinstate list of candidates for next exploration, and move cursor one place upstream.
        candidates[[cursor]] = neighbouring.points(incumbent[cursor], xmin[cursor], xmax[cursor])
        x[cursor] = incumbent[cursor]
        cursor = cursor - 1
    }

    return(x)
}


optimise.win = function(work, sla = 25, weight.sla = 10, weight.pax = 0.3,
weight.staff = 3, weight.churn = 45, concavity.limit = 30, win.width = 90,
win.step=60, block.width=15, xmin = 1, xmax = 6, starting.est=NULL, quiet=T)
# ----------------------------------------------------------------------------
# Obtain an optimised vector of desks for the given vector of workload,
# each with one element per time slot. The length of the work vector must be
# an exact multiple of the block.width, which gives the time resolution for
# changing staffing levels. And the window width, win.width, must be an even
# multiple of block.width.
# xmin and xmax may be either scalars, or vectors of same length as work.
{
    smoothing.width = block.width
    win.width = min(win.width, length(work))
    stopifnot(win.width %% (2 * block.width) == 0)
    stopifnot(length(work) %% block.width == 0)
    stopifnot(xmax >= xmin)
    stopifnot(length(xmin) %in% c(1, length(work)))  # either a scalar, or vector same length as work
    stopifnot(length(xmax) %in% c(1, length(work)))
    # If xmin is non-scalar, assert it has consistent values within each block. Ditto xmax.
    if (length(xmin) > 1) stopifnot(all.equal(xmin, block.mean(xmin, block.width)))
    if (length(xmax) > 1) stopifnot(all.equal(xmax, block.mean(xmax, block.width)))

    win.start = 1             # initial window position
    win.stop = win.width
    qstart = 0                # assume initially no queue in the system...
    churn.start = 0           # ...and no desks yet open

    # Initial estimate of number of desks, in every time slot
    if (is.null(starting.est))
    {
        desks = ceiling(block.mean(running.avg(work, smoothing.width), block.width))
        desks = pmax(desks, xmin)
        desks = pmin(desks, xmax)
    }
    else
    {
        desks = starting.est
        stopifnot(length(desks) == length(work))
    }

    # Curry the cost function to form an objective function, z,
    # taking a single vector parameter.
    z = function(x) cost(current.work, rep(x, each = block.width), sla, weight.pax, weight.staff,
    weight.churn, weight.sla, qstart = qstart, churn.start = churn.start)$total

    repeat
    {
        current.work = work[win.start : win.stop]
        block.guess = desks[seq(win.start, win.stop, block.width)]
        # starting value of desks for branch and bound, condensed so one element per block
        # e.g. if block length is 2, the vector c(4,4,1,1,3,3) condenses to c(4,1,3)

        # Condense xmin and xmax unless they are scalars
        if (length(xmin) > 1)xmin.condensed = xmin[seq(win.start, win.stop, block.width)]
        else xmin.condensed = xmin
        if (length(xmax) > 1)xmax.condensed = xmax[seq(win.start, win.stop, block.width)]
        else xmax.condensed = xmax

        block.optimum = branch.bound(block.guess, z, xmin.condensed, xmax.condensed, concavity.limit)
        desks[win.start : win.stop] = rep(block.optimum, each = block.width)
        if (! quiet) { cat(block.optimum, "\n"); flush.console()}
        if (win.stop == length(work))break
        qstart = process.work(
        work[win.start : (win.start + win.step - 1)],
        desks[win.start : (win.start + win.step - 1)],
        sla, qstart)$residual
        churn.start = desks[win.start + win.step - 1]
        win.start = win.start + win.step
        win.stop = min(win.stop + win.step, length(work))
    }

    return(desks)
}


leftward.desks = function(work, xmin=1, xmax=6, block.size=15, backlog=0)
# -----------------------------------------------------------------------

# For the case xmin=0, determine a vector of desks to process the
# work vector plus any pre-existing backlog, such that all work is
# processed as soon as possible, but subject to:
#     (a) desks <= xmax
#     (b) no desks ever idle
#     (c) desks remain constant within blocks of length block.size

# If xmin > 0 then condition (b) is locally relaxed where required.

# This function (unlike some of the others in the R implementation)
# can cope with either scalar or vector xmin and/or xmax

# The length of work must be a multiple of block.size

# Return value is a list with elements $desks giving the vector of
# desks, and $backlog giving the amount of work left over after
# processing with these desks.

{
    xmin[1 : length(work)] = xmin   # recycle xmin if a scalar was passed in
    xmax[1 : length(work)] = xmax
    stopifnot(length(xmin) == length(xmax))
    stopifnot(length(xmin) == length(work))      # assert vectors now all the same length
    stopifnot(length(work) %% block.size == 0) # assert work length multiple of block.size
    stopifnot(xmin == block.mean(xmin, block.size))
    stopifnot(xmax == block.mean(xmax, block.size)) # xmin & xmax not allowed to vary within a block
    stopifnot(xmax >= xmin)

    desks = numeric(length(work))              # initialise vector to hold results
    for (i in seq(1, length(work), block.size))# loop over starting indices of blocks
    {
        work.block = work[i : (i + block.size - 1)]            # vector of work in the current block
        guess = (backlog + sum(work.block)) %/% block.size   # starting guess for desks
        guess = min(guess, xmax[i])                          # ensure within xmax
        while (min(backlog + cumsum(work.block - guess)) < 0) {
            guess = guess - 1          # reduce guess for number of desks until desks never idle
        }
        guess = max(guess, xmin[i])   # but top up the desks if they are now below xmin

        for (j in 1 : block.size)# update backlog after processing current block
        backlog = max(backlog + work.block[j] - guess, 0)

        desks[i : (i + block.size - 1)] = guess   # update desks vector with final guess for the block
    }

    return(list(desks = desks, backlog = backlog))
    # Note a backlog is permitted to remain at the end of the period.
    # If no further optimisation is carried out on the results of leftward.desks,
    # this backlog might cause a breach.
}


fair.xmax = function(work, xmin=1, block.size=15, sla=25)
# -------------------------------------------------------
# Find the lowest (scalar) xmax for work, such that the desks returned by
# leftward.desks just avoid a breach; zero backlog assumed.

# In principle, the "desks never idle" rule means there are some combinations
# of xmin and work for which leftward.desks would always breach, even with
# xmax = Inf. To avoid this and other obscure wild cases, the fair.xmax is
# constrained never to exceed the greatest average work within any period of
# length block.size, or the maximum within any period of length sla, if that
# is shorter than the block.size. All subject to xmin.

# Empirically, this function tends to return quite a generous xmax, especially
# if the block.size is not much shorter than the sla. Experiments with one
# week's data suggest that calling this function with an xmin of 1 (even if
# the real xmin is 0) and a block.size of 5 (instead of the default 15) minutes
# gives a pragmatically sensible xmax.

{
    guess.max = ceiling(max(running.avg(work, min(block.size, sla))))
    # initial guess; only values of xmax less than or equal to this will be considered
    lower.limit = max(xmin)   # xmax can't be less than xmin (or highest value in xmin if vector)
    if (guess.max <= lower.limit) return(lower.limit)  # return immediately if already hit lower limit

    xmax = guess.max
    repeat   # successively reduce xmax until we get a breach or hit some other limiting case
    {
        trial.desks = leftward.desks(work, xmin, xmax, block.size, backlog = 0)$desks
        trial.process = process.work(work, trial.desks, sla, 0)
        if (trial.process$excess.wait > 0)# have a breach, so revert to previous xmax and exit
        { xmax = min(xmax + 1, guess.max); break}
        if (xmax <= lower.limit)break       # no breach, but have reached lower limit
        xmax = xmax - 1                     # reduce xmax to see if it now breaches...
        if (xmax == 0) { xmax = 1; break}   # ...but not if have hit zero.
        # There must be finite work to get this far, so if xmax reaches zero we revert to one.
        # Note no breach would be detected next time round the loop with an xmax of zero,
        # because no excess wait is incurred until the pax are actually processed.
    }
    return(xmax)
}


legacy.rolling.fair.xmax = function(work, xmin=1, block.size=5, sla=0.75 * 25,
target.width=60, rolling.buffer=120)
# -------------------------------------------------------
# Legacy version of rolling.fair.xmax, for closer compatibility (at time of
# writing, 26/08/2015) with live DRT. The main difference from the current version
# is that it ignores any carry-over queue between windows, so a conservative
# sla (typically 75% of the real sla) should be used. Also a longer rolling.buffer
# (default 120) is needed, especially for NEEA.

{
    xmin[1 : length(work)] = xmin   # recycle xmin if it's a scalar
    xmax = numeric(length(xmin))  # create vector to populate with rolling max values
    stopifnot(length(work) %% target.width == 0)
    # length of work must be multiple of target.width
    for (start.slot in seq(1, length(work) - 1, target.width))
    {
        win.start = max(start.slot - rolling.buffer, 1)
        win.stop = min(start.slot + target.width + rolling.buffer - 1, length(work))
        xmax[seq(start.slot, length = target.width)] =
        fair.xmax(work[win.start : win.stop], xmin[win.start : win.stop], block.size, sla)
    }
    return(xmax)
}


rolling.fair.xmax = function(work, xmin=1, block.size=5, sla=25,
target.width=60, rolling.buffer=60)
# ----------------------------------------------------
# As for fair.xmax but on a rolling basis, where the xmax in each region of
# width target.width (in slots) is calculated on the basis of a surrounding
# window which extends by rolling.buffer on either side of the target.
# e.g. with target.width and rolling.buffer both 60, the xmax for hour 5 is
# based on the work in hours 4, 5 and 6.

# Upgraded in v6 to take into account carry-over work between windows, so you
# no longer need the fudge of setting the sla to 75% of its true value. Also
# it now prevents the xmax ever dropping below the mean level of work in each
# window.

# Experiments suggest that a block.size of 5 gives a smoother xmax than
# the value of 15 used in live DRT (at time of writing). Also the xmax
# will avoid some unnecessarily large peaks if you call this function with an
# xmin of 1 or more, even if the real xmin is zero.

# Experiments also suggest that the rolling.buffer can now be 60 mins for
# both EEA and NEEA, instead of 120 mins in the legacy version. This allows
# the fair xmax to follow the work more closely.

{
    xmin[1 : length(work)] = xmin   # recycle xmin if it's a scalar
    stopifnot(length(work) %% target.width == 0)
    # length of work must be multiple of target.width
    stopifnot(rolling.buffer %% target.width == 0)
    # this is to ensure backlog calculation can work with discrete multiples of target.width
    stopifnot(target.width > sla)
    # this is to prevent the backlog being procrastinated indefinitely, since the
    # clock is effectively reset on the backlog at each iteration

    overrun = seq(length(work) + 1, length = target.width)
    # indices for an overrun period, to ensure work in the last slot can be processed
    work[overrun] = 0        # extend work with an overrun, where no new work arrives
    xmin[overrun] = tail(xmin, 1)  # extend xmin as well, repeating its final value
    xmax = numeric(length(xmin))   # create vector to populate with rolling xmax values

    for (start.slot in seq(1, length(work) - 1, target.width))
    {
        win.start = max(start.slot - rolling.buffer, 1)
        win.stop = min(start.slot + target.width + rolling.buffer - 1, length(work))
        win.work = work[win.start : win.stop]
        win.xmin = xmin[win.start : win.stop]
        if (win.start == 1)backlog = 0   # (re-)zero backlog if window still at start of work

        guess.max = ceiling(max(running.avg(win.work, min(block.size, sla))))
        # initial guess; only values of xmax less than or equal to this will be considered
        # Set a lower limit so that xmax can't be less than xmin (or highest value in xmin, if
        # it's a vector). Also do not allow xmax to be less than even the mean work in the window.
        lower.limit = max(win.xmin)
        lower.limit = max(lower.limit, ceiling(mean(win.work)))

        if (guess.max <= lower.limit)
        win.xmax = lower.limit
        else
        {
            win.xmax = guess.max
            repeat
            # successively reduce win.xmax until we get a breach or hit some other limiting case
            {
                trial = leftward.desks(win.work, win.xmin, win.xmax, block.size, backlog)
                trial.process = process.work(win.work, trial$desks, sla, 0)
                if (trial.process$excess.wait > 0)
                # have a breach, so revert to previous xmax and terminate repeat loop
                {
                    win.xmax = min(win.xmax + 1, guess.max);
                    break
                }
                if (win.xmax <= lower.limit)
                break   # no breach, but have reached lower limit
                win.xmax = win.xmax - 1             # reduce xmax and loop
            }
        }
        xmax[seq(start.slot, length = target.width)] = win.xmax

        # Update backlog, assuming xmax desks during the relevant time period.
        # We need the backlog after the first target.width time slots (not
        # the entire window), ready for the next iteration of start.slot
        for (j in 1 : target.width)
        backlog = max(backlog + win.work[j] - xmax[win.start], 0)
    }

    xmax = xmax[- overrun]   # drop the overrun period
    return(xmax)
}


# Experience shows that some awkward edge cases can arise if xmin is zero
# in some time slots. The two functions below are to make optimisation more
# robust in these cases. First, you should supply the result of pre.opt.xmin
# to the optimiser, as opposed to the original xmin supplied by the end user.
# Then, you can call finalise.zero.desks on the optimised desks vector in
# order to reinstate zeros where it would make sense to do so.


pre.opt.xmin = function(user.xmin, user.xmax)
# -------------------------------------------
# Takes the xmin and xmax supplied by the end user and returns a more
# suitable xmin for the optimiser. This has xmin always at least one, except
# in any slots where xmax is zero. This is to avoid some "perverse" optimisations
# that can occur with xmin = 0, such as parking sparse arrivals with no desks
# open at all until more passengers arrive.
{
    # vectorise the parameters, in case one was a vector and the other a scalar
    vector.length = max(length(user.xmin), length(user.xmax))
    user.xmin[1 : vector.length] = user.xmin
    user.xmax[1 : vector.length] = user.xmax

    # replace 0 with 1 in xmin, unless xmax is 0
    stopifnot(user.xmax >= user.xmin)
    xmin = pmin(pmax(1, user.xmin), user.xmax)
    return(xmin)
}


finalise.zero.desks = function(work, capacity, user.xmin, block.size=15, qstart=0)
# -------------------------------------------
# Return an adjusted version of capacity, where ones are replaced by zeros if:
#    - the user said xmin was zero in that block; and
#    - the capacity parameter would cause completely idle desks in that block.
# This function is to apply a final adjustment post optimisation if the
# optimisation was carried out using the result of pre.opt.min above.
{
    util = process.work(work, capacity, 0, qstart)$util   # get utilisation with given capacity
    util = block.max(util, block.size)
    # get max within each block - only interested in blocks where util is entirely zero
    capacity = ifelse(util == 0 & capacity == 1 & user.xmin == 0, 0, capacity)
    return(capacity)
}


