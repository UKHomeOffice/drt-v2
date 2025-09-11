source("qsim.R")
source("qqa.R")

set.seed(7)

queue1Arrivals    <- sort(sample(0:60, 12, replace = TRUE))
queue2Arrivals <- sort(sample(0:60, 16, replace = TRUE))

prio_df <- data.frame(
  arrival.time = queue1Arrivals,
  customer.inflow.route = factor(rep("queue1", length(queue1Arrivals)),
                                 levels = c("queue1","queue2")),
  desk.route = factor(rep("customer.outflow", length(queue1Arrivals)),
                      levels = "customer.outflow"),
  desk.txtime = sample(c(1,2,3,4), length(queue1Arrivals), replace = TRUE)
)

regular_df <- data.frame(
  arrival.time = queue2Arrivals,
  customer.inflow.route = factor(rep("queue2", length(queue2Arrivals)),
                                 levels = c("queue1","queue2")),
  desk.route = factor(rep("customer.outflow", length(queue2Arrivals)),
                      levels = "customer.outflow"),
  desk.txtime = sample(c(1,2,3,4), length(queue2Arrivals), replace = TRUE)
)


db <- list(
  customer = rbind(prio_df, regular_df)[order(c(prio_df$arrival.time, regular_df$arrival.time)), ]
)


sim <- new.simulation(db)

# Two queues for the same job type; desk pool serves both (priority = order listed)
make.queue(sim, "queue1",    "desk.pool", "customer", sla = 60)
make.queue(sim, "queue2", "desk.pool", "customer", sla = 60)

# One desk pool (multiserver) serving both queues; prio served first
make.multiserver(sim,
                 multiserver.name   = "desk.pool",
                 server.type        = "desk",
                 capacity           = 0,   # capacity controlled by resource 'staff'
                 job.type           = "customer",
                 queue.names.served = c("queue1","queue2"),
                 main.queue.name    = "queue1"
)

# ---------------------------
# 3) Single resource controlling desk capacity over time
# ---------------------------
r2c <- list(desk = function(x) x)   # staff -> desks (1:1)
c2r <- list(desk = function(x) x)   # desks -> staff

make.resource(sim,
              resource.name      = "staff",
              initial.quantity   = 0,
              used.by            = c("desk.pool"),
              resource2capacity  = r2c,
              capacity2resource  = c2r
)

# Schedule: 0–15:5 desks, 15–30:7 desks, 30–45:4 desks, 45–60:10 desks
make.scheduler(sim, "staff",
               times = c(0, 15, 30, 45),
               quantities = c(5, 7, 4, 10))


sim$run()
