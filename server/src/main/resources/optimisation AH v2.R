# Branch and bound optimisation

# This is based on the final version received back from JZ, with 
# AH modifications 04/09/2013

# v2 03/02/2014 with minor modifications:
# Bug fixes in running.avg and block.mean - previous version incorporated
# unnecessary, and undocumented, rounding of the inputs.


running.avg = function(x, npoint)
# -------------------------------
# Apply an asymmetric npoint running average to x
# Each output value is the mean of the preceding npoint input values, inclusive
# The first npoint values are all set to their common mean, to avoid NAs
# If x is too short for npoint, all output values are set to the common mean
{
npoint = min(npoint, length(x))
y = as.numeric(filter(x, rep(1/npoint, npoint), method="convolution", sides=1))
y[1:npoint] = mean(x[1:npoint])
return(y)
}


block.mean = function(x, n)
# -------------------------
# Divide x into blocks of size n and return a vector the same
# length as x, where each element is the mean of the block
{
block.number = rep(1:length(x), each = n, length.out = length(x))
ave(x, block.number, FUN=mean)
}


process.work = function(work, capacity, sla, qstart)
# -------------------------------------------------

# This is based on the simul function from ContES.R
# Simulate a simple queue by continuous event simulation

# Inputs:
# work - vector of quantity of workload arriving for some series of time slots
# capacity - vector of available service capacity in the same time slots
# sla - service level agreement in minutes
# qstart - vector of work in pre-existing queue by ascending age; typically,
#    the "residual" from a previous call to this function

# Returns a list with elements:
# wait - vector of waiting times for the oldest work in the queue at end of each slot
#    Note this just gives a snapshot per slot and is unweighted, so should NOT be 
#    averaged to get an average queuing time.
# util - vector of utilisation of service capacity each slot
# residual - vector giving the remaining queue at end of final slot
# total.wait - the total waiting time, in units of work times slots waited

{
stopifnot(length(work) == length(capacity))
if(length(work) == 0)             # handle the empty case
   return(list(wait=integer(0), util=numeric(0), sla = sla, residual=qstart))

q = qstart                   # vector representing queued workload
wait = integer(length(work)) # vector to hold waiting times
util = numeric(length(work)) # vector to hold utilisation rates
total.wait = 0      # waiting time, in workload-minutes
excess.wait = 0     # amount of total.wait where time greater than sla, in workload-minutes

for(t in 1:length(work))
   {
   q = c(work[t], q)         # add workload arriving in current time slot to queue
   resource = capacity[t]    # resource available in the current time slot
   age = length(q)           # oldest work on the queue, where age 1 is new work this slot
   while(age > 0)
      {
      surplus = resource - q[age]   # how much resource would be left after processing oldest work
      if(surplus >= 0)              # can process oldest work completely
         {
         total.wait = total.wait + q[age] * (age - 1)
         if(age - 1 >= sla) excess.wait = excess.wait + q[age] * (age -1)
         q = q[-age]         # remove oldest work from queue
         resource = surplus  # update remaining resource available this slot
         }
      else
         {
         total.wait = total.wait + resource * (age - 1)
         if(age - 1 >= sla) excess.wait = excess.wait + resource * (age - 1)
         q[age] = q[age] - resource   # process as much of the oldest work as possible
         resource = 0                 # no more resource left in this time slot, so...
         break                        # ...terminate while loop - can process no more of the queue
         }
      age = age - 1           # loop over more recent work in queue
      }
   wait[t] = length(q)        # how long the oldest work in the queue has been waiting so far   
   util[t] = 1 - resource / capacity[t]   # proportion of capacity that was used this slot   
   }
return(list(wait=wait, util=util, residual=q, total.wait=total.wait, excess.wait=excess.wait))
}

### Note total.wait needs to be divided by workload per passenger if it is to be
### interpreted as total passenger-minutes lost.


churn = function(churn.start, x)
# -----------------
# sum all the positive differences of x, with the convention x[0] = churn.start
{
x.lag = c(churn.start, x)
x = c(x, -Inf)
d = x - x.lag
d = d[d > 0]
sum(d)
}


cost = function(work, capacity, sla, 
                weight.pax, weight.staff, weight.churn, weight.sla,
                overrun=500, qstart, churn.start)
# ----------------
# Calculate a cost function for processing a queue, with the given weights
# The capacity is assumed to be retained at its final value for overrun
# slots beyond the end of the time interval being assessed, in order
# to clear any residual queue.
# Passenger waiting time is counted for all the passengers in "work".
# Staff idle time is only counted for the time interval being assessed.
{
simres = process.work(c(work, rep(0, overrun)), c(capacity, rep(tail(capacity, 1), overrun)),
   sla, qstart)
stopifnot(length(simres$residual) == 0)

pax.penalty = simres$total.wait

sla.penalty = simres$excess.wait

staff.penalty = sum((1 - simres$util[1:length(capacity)]) * capacity)

churn.penalty = churn(churn.start, capacity)

total.penalty = weight.pax * pax.penalty + weight.staff * staff.penalty + 
   weight.churn * churn.penalty + weight.sla * sla.penalty

return(list(pax = pax.penalty,
            sla.p = sla.penalty,
            staff = staff.penalty,
            churn = churn.penalty,
            total = total.penalty))
}


neighbouring.points = function(x0, xmin, xmax)
# --------------------------------------------
# return vector of integers between xmin and xmax in order of distance from x0
# excluding x0 itself
{
x = xmin:xmax
x = x[(x != x0)]
x[order(abs(x - x0))]
}


branch.bound = function(starting.x, objective.func, xmin, xmax, concavity.limit)
# ----------------------------------
# Estimate the (vector) x which minimises objective.func(x),
# subject to (scalar) constraints xmin and xmax, and integer x.

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
best.so.far = objective.func(incumbent)
candidates = lapply(incumbent, neighbouring.points, xmin, xmax)
   # list of feasible x[i] to be explored, for each i

cursor = n             # index into the x vector
while(cursor > 0)      # once cursor under-runs, all subtrees have been explored, so stop
   {
   while(length(candidates[[cursor]]) > 0)   # any more candidates at current cursor position?
      {
      x[cursor] = candidates[[cursor]][1]    # pop the next candidate from the list
      candidates[[cursor]] = candidates[[cursor]][-1]
      # cat(x, "\n")
      trial.z = objective.func(x)
      if(trial.z > best.so.far + concavity.limit)        ### * (n - cursor + 1))
         {
         # current x is so bad it is not worth exploring its subtree any further
         # so discard all candidates more extreme than the current one
         if(x[cursor] > incumbent[cursor])
            candidates[[cursor]] = candidates[[cursor]][candidates[[cursor]] < x[cursor]]
         else
            candidates[[cursor]] = candidates[[cursor]][candidates[[cursor]] > x[cursor]]
         next   # proceed to next iteration of "while" loop over candidates
         }
      if(trial.z < best.so.far)
         {
         # current solution is better than the previous best, so update
         incumbent = x
         best.so.far = trial.z
         }
      if(cursor < n) cursor = cursor + 1   # subtree requires exploration, so move cursor downstream
      }
   # When inner while loop terminates, all candidates downstream of cursor have been explored,
   # so reinstate list of candidates for next exploration, and move cursor one place upstream.
   candidates[[cursor]] = neighbouring.points(incumbent[cursor], xmin, xmax)
   x[cursor] = incumbent[cursor]
   cursor = cursor - 1
   }

return(x)
}


# --------------------------------
# Application of optimisation
# --------------------------------

example=function()
{

sla = 12
weight.sla = 10
weight.pax = 1
weight.staff = 3
weight.churn = 45

# work = c(5, 0, 3, 2, 6, 7, 5, 4,2,4,6,1,3,9,13,2)       # a small test case; length must be multiple of block size

workdata = read.csv("work_e.csv")
work = subset(workdata, day==1)$work.e   # length must be multiple of block width

xmin = 1
xmax = 10
block.width = 15          # number of time slots forming the smallest block for changing desks
smoothing.width = 15
win.width = 120           # must be even multiple of block.width
win.width = min(win.width, length(work))
stopifnot(win.width %% (2 * block.width) == 0)
stopifnot(length(work) %% block.width == 0)
concavity.limit = 200
win.start = 1
win.stop = win.width
win.step = 60
qstart = 0
churn.start = 0

# Initial estimate of number of desks, in every time slot
desks = ceiling(block.mean(running.avg(work, smoothing.width), block.width))
desks = pmax(desks,xmin) 
desks = pmin(desks,xmax)

# Curry the cost function to form an objective function, 
# taking a single vector parameter.
z = function(x) cost(current.work, rep(x, each=block.width), sla, weight.pax, weight.staff, 
   weight.churn, weight.sla, qstart = qstart, churn.start = churn.start)$total
    
repeat
   {
   current.work = work[win.start:win.stop]
   block.guess = desks[seq(win.start, win.stop, block.width)]
      # starting value of desks for branch and bound, condensed so one element per block
      # e.g. if block length is 2, the vector c(4,4,1,1,3,3) condenses to c(4,1,3)
   block.optimum = branch.bound(block.guess, z, xmin, xmax, concavity.limit)
   desks[win.start:win.stop] = rep(block.optimum, each=block.width)
   cat(block.optimum, "\n"); flush.console()
   if(win.stop == length(work)) break
   qstart = process.work(
      work[win.start:(win.start + win.step - 1)], 
      desks[win.start:(win.start + win.step - 1)],
      sla, qstart)$residual
   churn.start = desks[win.start + win.step - 1]
   win.start = win.start + win.step
   win.stop = min(win.stop + win.step, length(work))
   }

return(desks)
}

# write.csv(example(),"desksresult.csv")

fragments = function()
{
plot(work, type="s")
plot(block.mean(work, 15), type="s")
points(desks.opt, type="s", col="green", lwd=2)

cost(work, desks, sla, weight.pax, weight.staff, weight.churn, weight.sla, 500, 0, 0)

}

